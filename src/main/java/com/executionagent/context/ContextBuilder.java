package com.executionagent.context;

import com.executionagent.models.LlmModel;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds repository context by cloning the repo and collecting CI/CD files,
 * Dockerfiles, READMEs, requirement files, and performing web searches.
 * Mirrors Python ContextBuilder.
 */
public class ContextBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(ContextBuilder.class);

    private final String workspaceRoot;

    public ContextBuilder(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    // -------------------------------------------------------------------------
    // Top-level builder
    // -------------------------------------------------------------------------

    public RepoContext buildRepoContext(
            LlmModel model,
            LlmModel knowledgeModel,
            String projectPath,
            String projectUrl,
            String language,
            String searchWorkflowsSummaryPrompt) {

        RepoContext ctx = new RepoContext(projectPath, projectUrl, language);

        // Clone the repository
        boolean cloned = cloneRepo(projectPath, projectUrl);
        ctx.localRepoAvailable = cloned;
        LOG.info("Repository clone: {}", cloned ? "success" : "failed/already exists");

        Path repoPath = Path.of(workspaceRoot, projectPath);

        // Find and load workflows
        List<String> workflows = findWorkflows(repoPath);
        ctx.workflows = workflows;
        if (!workflows.isEmpty()) {
            ctx.workflowContents = loadWorkflowContents(repoPath, workflows, model);
            LOG.info("Loaded {} workflow files", ctx.workflowContents.size());
        }

        // Find Dockerfiles
        List<String> dockerfiles = findDockerfiles(repoPath);
        ctx.dockerfiles = dockerfiles;
        ctx.dockerfileContents = loadFileContents(repoPath, dockerfiles, 10000);
        LOG.info("Found {} Dockerfiles", dockerfiles.size());

        // Find README
        String readme = findReadme(repoPath);
        ctx.readmeContent = readme;

        // Find requirement files
        List<String[]> reqFiles = findRequirementFiles(repoPath, language);
        ctx.requirementFiles = reqFiles;
        LOG.info("Found {} requirement files", reqFiles.size());

        // Perform web search
        try {
            List<Map<String, Object>> searchResults = performWebSearch(
                    projectPath, knowledgeModel, 5);
            ctx.searchResults = new ArrayList<>(searchResults);
            LOG.info("Web search: {} results", searchResults.size());
        } catch (Exception e) {
            LOG.warn("Web search failed: {}", e.getMessage());
        }

        // Build unified summary
        try {
            String summary = buildUnifiedSummary(ctx, model, searchWorkflowsSummaryPrompt);
            ctx.unifiedSummary = summary;
            LOG.info("Unified summary built ({} chars)", summary != null ? summary.length() : 0);
        } catch (Exception e) {
            LOG.warn("Failed to build unified summary: {}", e.getMessage());
        }

        return ctx;
    }

    // -------------------------------------------------------------------------
    // Clone repo
    // -------------------------------------------------------------------------

    public boolean cloneRepo(String projectPath, String projectUrl) {
        Path targetDir = Path.of(workspaceRoot, projectPath);
        if (Files.exists(targetDir)) {
            LOG.info("Repository already exists at {}", targetDir);
            return true;
        }

        try {
            Files.createDirectories(targetDir.getParent());
            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", projectUrl, targetDir.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int rc = p.waitFor();
            if (rc == 0) {
                LOG.info("Cloned repository to {}", targetDir);
                return true;
            } else {
                LOG.error("git clone failed (rc={}): {}", rc, output);
                return false;
            }
        } catch (Exception e) {
            LOG.error("Clone failed: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Workflow discovery
    // -------------------------------------------------------------------------

    public List<String> findWorkflows(Path repoPath) {
        Path workflowDir = repoPath.resolve(".github/workflows");
        if (!Files.exists(workflowDir)) return new ArrayList<>();

        try (Stream<Path> stream = Files.walk(workflowDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .map(p -> repoPath.relativize(p).toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOG.warn("Error finding workflows: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<String[]> loadWorkflowContents(Path repoPath, List<String> workflows, LlmModel model) {
        List<String[]> result = new ArrayList<>();
        for (String wf : workflows) {
            try {
                String content = Files.readString(repoPath.resolve(wf), StandardCharsets.UTF_8);
                // Apply LLM filter if available
                String filtered = null;
                if (model != null) {
                    filtered = llmFilterCicdFile(wf, content, model);
                }
                if (filtered != null && !filtered.isBlank()) {
                    result.add(new String[]{wf, filtered});
                } else {
                    // Use heuristic extraction
                    String extracted = extractRelevantWorkflowParts(content);
                    if (!extracted.isBlank()) {
                        result.add(new String[]{wf, extracted});
                    }
                }
            } catch (IOException e) {
                LOG.warn("Error reading workflow {}: {}", wf, e.getMessage());
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Dockerfile discovery
    // -------------------------------------------------------------------------

    public List<String> findDockerfiles(Path repoPath) {
        List<String> result = new ArrayList<>();
        if (!Files.exists(repoPath)) return result;
        try (Stream<Path> stream = Files.walk(repoPath, 3)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.equals("Dockerfile") || name.startsWith("Dockerfile.");
                    })
                    .forEach(p -> result.add(repoPath.relativize(p).toString()));
        } catch (IOException e) {
            LOG.warn("Error finding Dockerfiles: {}", e.getMessage());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // README
    // -------------------------------------------------------------------------

    public String findReadme(Path repoPath) {
        for (String name : List.of("README.md", "README.rst", "README.txt", "README",
                "INSTALL.md", "INSTALL.rst", "CONTRIBUTING.md")) {
            Path p = repoPath.resolve(name);
            if (Files.exists(p)) {
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    return content.length() > 20000 ? content.substring(0, 20000) : content;
                } catch (IOException e) {
                    LOG.warn("Error reading {}: {}", name, e.getMessage());
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Requirement files
    // -------------------------------------------------------------------------

    public List<String[]> findRequirementFiles(Path repoPath, String language) {
        List<String> candidates = new ArrayList<>();
        String lang = language != null ? language.toLowerCase() : "";

        // Language-specific files
        if (lang.contains("python") || lang.contains("py")) {
            candidates.addAll(List.of("requirements.txt", "requirements-dev.txt", "setup.py",
                    "setup.cfg", "pyproject.toml", "Pipfile", "environment.yml"));
        } else if (lang.contains("java")) {
            candidates.addAll(List.of("pom.xml", "build.gradle", "build.gradle.kts",
                    "settings.gradle", "settings.gradle.kts"));
        } else if (lang.contains("javascript") || lang.contains("js") || lang.contains("typescript")) {
            candidates.addAll(List.of("package.json", "package-lock.json", "yarn.lock"));
        } else if (lang.contains("rust") || lang.contains("rs")) {
            candidates.addAll(List.of("Cargo.toml", "Cargo.lock"));
        } else if (lang.contains("c") || lang.contains("cpp") || lang.contains("c++")) {
            candidates.addAll(List.of("CMakeLists.txt", "Makefile", "configure.ac",
                    "meson.build", "conanfile.txt"));
        } else {
            candidates.addAll(List.of("requirements.txt", "pom.xml", "package.json",
                    "Cargo.toml", "CMakeLists.txt", "Makefile", "setup.py", "pyproject.toml"));
        }

        List<String[]> result = new ArrayList<>();
        for (String name : candidates) {
            Path p = repoPath.resolve(name);
            if (Files.exists(p)) {
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    result.add(new String[]{name, content.length() > 5000 ? content.substring(0, 5000) : content});
                } catch (IOException e) {
                    LOG.warn("Error reading {}: {}", name, e.getMessage());
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Generic file content loader
    // -------------------------------------------------------------------------

    private List<String[]> loadFileContents(Path repoPath, List<String> relativePaths, int maxChars) {
        List<String[]> result = new ArrayList<>();
        for (String rel : relativePaths) {
            try {
                String content = Files.readString(repoPath.resolve(rel), StandardCharsets.UTF_8);
                if (content.length() > maxChars) content = content.substring(0, maxChars);
                result.add(new String[]{rel, content});
            } catch (IOException e) {
                LOG.warn("Error reading {}: {}", rel, e.getMessage());
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Web search (DuckDuckGo)
    // -------------------------------------------------------------------------

    public List<Map<String, Object>> performWebSearch(
            String projectName, LlmModel knowledgeModel, int maxResults) {

        List<Map<String, Object>> results = new ArrayList<>();
        String query = projectName + " install from source run tests";

        try {
            List<Map<String, String>> rawResults = duckDuckGoSearch(query, maxResults);
            LOG.info("DuckDuckGo: {} results for '{}'", rawResults.size(), query);

            for (Map<String, String> item : rawResults) {
                String url = item.get("url");
                String title = item.get("title");
                String snippet = item.get("snippet");

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("url", url);
                result.put("title", title);
                result.put("snippet", snippet);

                // Try to fetch page content
                try {
                    String pageContent = fetchPageContent(url, 5000);
                    result.put("content", pageContent);

                    // Analyze with LLM if available
                    if (knowledgeModel != null && pageContent != null && !pageContent.isBlank()) {
                        String analysis = analyzeWebPage(projectName, url, pageContent, knowledgeModel);
                        result.put("analysis", analysis);
                    }
                } catch (Exception e) {
                    LOG.debug("Failed to fetch {}: {}", url, e.getMessage());
                }

                results.add(result);
            }
        } catch (Exception e) {
            LOG.warn("Web search failed: {}", e.getMessage());
        }

        return results;
    }

    private List<Map<String, String>> duckDuckGoSearch(String query, int maxResults) {
        List<Map<String, String>> results = new ArrayList<>();
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encodedQuery;

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; ExecutionAgent/1.0)")
                    .timeout(10000)
                    .get();

            Elements links = doc.select(".result__title a");
            Elements snippets = doc.select(".result__snippet");

            for (int i = 0; i < Math.min(maxResults, links.size()); i++) {
                Element link = links.get(i);
                String href = link.attr("href");
                String title = link.text();
                String snippet = i < snippets.size() ? snippets.get(i).text() : "";

                // Clean DuckDuckGo redirect URLs
                if (href.contains("uddg=")) {
                    try {
                        Matcher m = Pattern.compile("uddg=([^&]+)").matcher(href);
                        if (m.find()) href = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
                    } catch (Exception ignored) {}
                }

                if (href.startsWith("http")) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("url", href);
                    item.put("title", title);
                    item.put("snippet", snippet);
                    results.add(item);
                }
            }
        } catch (Exception e) {
            LOG.debug("DuckDuckGo search error: {}", e.getMessage());
        }
        return results;
    }

    private String fetchPageContent(String url, int maxChars) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; ExecutionAgent/1.0)")
                .timeout(8000)
                .get();
        String text = doc.text();
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private String analyzeWebPage(String projectName, String url, String content, LlmModel model) {
        String prompt = String.format(
                "Analyze this web page content for information useful for installing and testing the '%s' project from source.\n\n" +
                "URL: %s\n\nContent:\n%s\n\n" +
                "Extract: installation commands, dependencies, test commands, build steps. " +
                "Be concise (max 500 words).",
                projectName, url, content.substring(0, Math.min(3000, content.length())));
        try {
            Map<String, Object> response = model.query(List.of(Map.of("role", "user", "content", prompt)));
            return (String) response.getOrDefault("content", "");
        } catch (Exception e) {
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // Unified summary
    // -------------------------------------------------------------------------

    public String buildUnifiedSummary(RepoContext ctx, LlmModel model,
                                       String summaryPromptTemplate) {
        if (model == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(ctx.projectPath).append(" (").append(ctx.language).append(")\n");
        sb.append("URL: ").append(ctx.projectUrl).append("\n\n");

        if (ctx.readmeContent != null && !ctx.readmeContent.isBlank()) {
            sb.append("README (excerpt):\n").append(ctx.readmeContent, 0,
                    Math.min(3000, ctx.readmeContent.length())).append("\n\n");
        }

        for (String[] rf : ctx.requirementFiles) {
            sb.append("File: ").append(rf[0]).append("\n").append(rf[1], 0,
                    Math.min(2000, rf[1].length())).append("\n\n");
        }

        for (String[] wf : ctx.workflowContents) {
            sb.append("CI/CD (").append(wf[0]).append("):\n").append(wf[1], 0,
                    Math.min(3000, wf[1].length())).append("\n\n");
        }

        if (!ctx.searchResults.isEmpty()) {
            sb.append("Web search results:\n");
            for (Object sr : ctx.searchResults) {
                if (sr instanceof Map<?, ?> map) {
                    Object analysis = map.get("analysis");
                    Object title = map.get("title");
                    if (analysis != null && title != null) {
                        sb.append("- ").append(title).append(": ").append(analysis).append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        String projectName = ctx.projectPath;
        String prompt = (summaryPromptTemplate != null && !summaryPromptTemplate.isBlank())
                ? summaryPromptTemplate.replace("{}", projectName)
                : buildDefaultSummaryPrompt(projectName);

        prompt = prompt + "\n\n" + sb.toString().substring(0, Math.min(30000, sb.length()));

        try {
            Map<String, Object> response = model.query(
                    List.of(Map.of("role", "user", "content", prompt)));
            String content = (String) response.getOrDefault("content", "");
            return content.length() > 10000 ? content.substring(0, 10000) : content;
        } catch (Exception e) {
            LOG.warn("Failed to generate unified summary: {}", e.getMessage());
            return sb.length() > 5000 ? sb.substring(0, 5000) : sb.toString();
        }
    }

    private String buildDefaultSummaryPrompt(String projectName) {
        return String.format(
                "Based on the following information about the '%s' project, provide a concise summary of:\n" +
                "1. How to install/build the project from source\n" +
                "2. Required dependencies and their versions\n" +
                "3. How to run the test suite\n" +
                "4. Any known issues or special requirements\n\n" +
                "Be specific and include exact commands where possible.",
                projectName);
    }

    // -------------------------------------------------------------------------
    // LLM CI/CD filter
    // -------------------------------------------------------------------------

    private String llmFilterCicdFile(String filePath, String content, LlmModel model) {
        if (content == null || content.isBlank() || model == null) return null;
        try {
            String prompt = String.format(
                    "You are helping with INSTALLING A SOFTWARE PROJECT FROM SOURCE AND RUNNING ITS TEST SUITE inside a Docker container.\n\n" +
                    "FILE PATH: %s\n\nFILE CONTENT:\n```\n%s\n```\n\n" +
                    "Extract ONLY parts relevant to: installing dependencies, building from source, running tests.\n" +
                    "If not relevant, respond with exactly: NOT_RELEVANT\n" +
                    "Otherwise, extract shell commands, dependency lists, test commands, and environment requirements.",
                    filePath, content.substring(0, Math.min(30000, content.length())));

            Map<String, Object> response = model.query(List.of(Map.of("role", "user", "content", prompt)));
            String result = (String) response.getOrDefault("content", "");
            result = result.trim();
            if (result.equals("NOT_RELEVANT") || result.isBlank()) return null;
            return result;
        } catch (Exception e) {
            LOG.debug("LLM filter failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Workflow text extraction (heuristic)
    // -------------------------------------------------------------------------

    private String extractRelevantWorkflowParts(String content) {
        if (content == null || content.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        boolean inRunBlock = false;

        List<Pattern> skipPatterns = List.of(
                Pattern.compile("^\\s*on:\\s*$", Pattern.CASE_INSENSITIVE),
                Pattern.compile("^\\s*push:\\s*$"),
                Pattern.compile("^\\s*pull_request"),
                Pattern.compile("^\\s*schedule:"),
                Pattern.compile("^\\s*permissions:"),
                Pattern.compile("^\\s*concurrency:")
        );

        for (String line : content.split("\n")) {
            boolean skip = skipPatterns.stream().anyMatch(p -> p.matcher(line).find());
            if (skip && !inRunBlock) continue;

            if (line.matches("\\s*run:\\s*[|>]?\\s*")) inRunBlock = true;
            else if (!line.startsWith(" ") && !line.startsWith("\t")) inRunBlock = false;

            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }
}
