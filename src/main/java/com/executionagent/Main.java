package com.executionagent;

import com.executionagent.agent.ExecutionAgent;
import com.executionagent.artifacts.ExitArtifacts;
import com.executionagent.context.ContextBuilder;
import com.executionagent.context.RepoContext;
import com.executionagent.env.ExecutionEnvironment;
import com.executionagent.exceptions.BudgetExhaustedException;
import com.executionagent.logging.TranscriptWriters;
import com.executionagent.models.LitellmModel;
import com.executionagent.persistence.StatePersistence;
import com.executionagent.tools.ToolRegistry;
import com.executionagent.tools.Tools;
import com.executionagent.trace.TraceToBash;
import com.executionagent.utils.DockerHelpers;
import com.executionagent.utils.SharedUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Main entry point for ExecutionAgent Java.
 * Mirrors Python main.py.
 *
 * Usage: java -jar execution-agent.jar --experiment-file project_meta_data.json
 */
@Command(name = "execution-agent", mixinStandardHelpOptions = true,
        description = "Run ExecutionAgent: autonomously sets up and runs test suites inside Docker containers.")
public class Main implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------
    // CLI options
    // -------------------------------------------------------------------------
    @Option(names = {"--experiment-file"}, required = true, description = "Path to project_meta_data.json")
    private String experimentFile;

    @Option(names = {"--task-file"}, description = "Optional file containing the task/instructions")
    private String taskFile;

    @Option(names = {"--task"}, description = "Optional task string (overrides --task-file)")
    private String task;

    @Option(names = {"--model"}, description = "LLM model name (default: ${DEFAULT-VALUE})",
            defaultValue = "${OPENAI_MODEL:-claude-opus-4-6}")
    private String model;

    @Option(names = {"--knowledge-model"}, description = "Knowledge model for web search analysis",
            defaultValue = "${KNOWLEDGE_MODEL:-claude-opus-4-6}")
    private String knowledgeModel;

    @Option(names = {"--api-key"}, description = "OpenAI API key (or set OPENAI_API_KEY env var)")
    private String apiKey;

    @Option(names = {"--base-url"}, description = "Base URL for OpenAI-compatible API",
            defaultValue = "${OPENAI_BASE_URL:-https://litellm.lib.ou.edu}")
    private String baseUrl;

    @Option(names = {"--workspace-root"}, description = "Workspace root directory",
            defaultValue = "execution_agent_workspace")
    private String workspaceRoot;

    @Option(names = {"--prompt-files"}, description = "Path to prompt template files",
            defaultValue = "src/execution_agent/prompt_files")
    private String promptFilesDir;

    @Option(names = {"--log-level"}, description = "Log level: DEBUG|INFO|WARNING|ERROR",
            defaultValue = "INFO")
    private String logLevel;

    @Option(names = {"--run-log-dir"}, description = "Optional directory to write run logs into")
    private String runLogDir;

    @Option(names = {"--max-retries"}, description = "Maximum retries after budget exhaustion",
            defaultValue = "2")
    private int maxRetries;

    // -------------------------------------------------------------------------
    // Global agent ref for shutdown cleanup
    // -------------------------------------------------------------------------
    private static volatile ExecutionAgent activeAgent = null;

    // -------------------------------------------------------------------------
    // Main
    // -------------------------------------------------------------------------
    public static void main(String[] args) {
        // Register shutdown hook for Docker cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ExecutionAgent a = activeAgent;
            if (a != null) {
                try {
                    DockerHelpers.cleanupContainer(a.containerId, a.dockerTag);
                    LOG.info("Cleanup complete on shutdown.");
                } catch (Exception e) {
                    System.err.println("Warning: Cleanup failed: " + e.getMessage());
                }
            }
        }, "shutdown-cleanup"));

        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    // -------------------------------------------------------------------------
    // Run
    // -------------------------------------------------------------------------
    @Override
    public Integer call() throws Exception {
        // Resolve API key
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("LITELLM_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Missing API key. Pass --api-key or set OPENAI_API_KEY env var.");
            return 1;
        }
        System.setProperty("OPENAI_API_KEY", apiKey);

        // Load experiment file
        Map<String, Object> meta = MAPPER.readValue(
                Path.of(experimentFile).toFile(),
                new TypeReference<>() {});
        String projectPath = (String) meta.get("project_path");
        String projectUrl = (String) meta.get("project_url");
        String language = (String) meta.getOrDefault("language", "unknown");
        int budget = meta.containsKey("budget") ? ((Number) meta.get("budget")).intValue() : 40;

        // Determine run directory
        Path runDir;
        if (runLogDir != null && !runLogDir.isBlank()) {
            runDir = Path.of(runLogDir);
        } else {
            String safeName = projectPath.replace(File.separator, "__").replace("/", "__");
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            runDir = Path.of(workspaceRoot, "_run_logs", safeName, ts);
        }
        Files.createDirectories(runDir);

        LOG.info("=" .repeat(80));
        LOG.info("PREPARATION PHASE - Collecting context and building main prompt");
        LOG.info("=" .repeat(80));
        LOG.info("Project:         {}", projectPath);
        LOG.info("Repo:            {}", projectUrl);
        LOG.info("Model:           {}", model);
        LOG.info("Knowledge model: {}", knowledgeModel);
        LOG.info("Run dir:         {}", runDir);

        // Determine task
        String resolvedTask;
        if (task != null && !task.isBlank()) {
            resolvedTask = task;
        } else if (taskFile != null) {
            resolvedTask = Files.readString(Path.of(taskFile), StandardCharsets.UTF_8);
        } else {
            resolvedTask = buildDefaultTask(meta);
        }

        // Load prompt templates
        LOG.info("Loading prompt templates...");
        String cycleInstruction = loadPromptFile("cycle_instruction");
        String summarizeCycle = loadPromptFile("summarize_cycle");
        String searchWorkflowsSummary = loadPromptFile("search_workflows_summary");
        String removeProgressBars = loadPromptFile("remove_progress_bars");
        String toolsList = loadPromptFile("tools_list");

        // Load language guidelines
        String languageGuidelines = loadLanguageGuidelines(language);

        // Initialize models
        LOG.info("Initializing models...");
        LitellmModel llmModel = new LitellmModel(this.model, apiKey, baseUrl, 300);
        LitellmModel knowledgeLlmModel = new LitellmModel(this.knowledgeModel, apiKey, baseUrl, 300);

        // Tool registry
        LOG.info("Registering tools...");
        ToolRegistry toolRegistry = Tools.createRegistry();

        // Execution environment
        ExecutionEnvironment env = new ExecutionEnvironment(
                workspaceRoot, projectPath,
                ExecutionEnvironment.localShellInteract(workspaceRoot, projectPath));

        // Build repository context
        LOG.info("Building repository context...");
        ContextBuilder ctxBuilder = new ContextBuilder(workspaceRoot);
        RepoContext repoContext = ctxBuilder.buildRepoContext(
                llmModel, knowledgeLlmModel,
                projectPath, projectUrl, language, searchWorkflowsSummary);
        LOG.info("Repository context built.");

        // Create agent
        ExecutionAgent agent = new ExecutionAgent(
                llmModel, env, toolRegistry,
                cycleInstruction, summarizeCycle, removeProgressBars, searchWorkflowsSummary,
                budget);

        // Inject metadata
        agent.workspacePath = workspaceRoot;
        agent.projectPath = projectPath;
        agent.projectUrl = projectUrl;
        agent.hyperparams = meta;
        agent.repoContext = repoContext;
        agent.toolsDocString = toolsList;
        agent.languageGuidelines = languageGuidelines;

        // State persistence
        StatePersistence persistence = StatePersistence.create(runDir);
        agent.statePersistence = persistence;

        // Register for shutdown cleanup
        activeAgent = agent;

        // Transcript writers
        TranscriptWriters transcript;
        try {
            transcript = new TranscriptWriters(
                    runDir.resolve("messages_transcript.txt"),
                    runDir.resolve("messages_transcript.jsonl"),
                    runDir.resolve("messages.json"));
        } catch (IOException e) {
            LOG.warn("Failed to create transcript writers: {}", e.getMessage());
            transcript = null;
        }

        LOG.info("=" .repeat(80));
        LOG.info("PREPARATION PHASE COMPLETE");
        LOG.info("=" .repeat(80));

        LOG.info("=" .repeat(80));
        LOG.info("MAIN PHASE - Starting agent execution cycles");
        LOG.info("=" .repeat(80));

        int maxAttempts = 1 + maxRetries;
        boolean finalSuccess = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            LOG.info("=" .repeat(80));
            LOG.info("ATTEMPT {} of {}", attempt, maxAttempts);
            LOG.info("=" .repeat(80));

            try {
                agent.run(resolvedTask);

                // Success!
                LOG.info("Goals accomplished on attempt {}", attempt);
                finalSuccess = true;

                // Generate exit artifacts
                try {
                    boolean ok = ExitArtifacts.generateExitArtifacts(agent, runDir, LOG);
                    if (ok) LOG.info("Exit artifacts generated.");
                    else LOG.warn("Could not generate exit artifacts (no Dockerfile found).");
                } catch (Exception e) {
                    LOG.warn("Failed to generate exit artifacts: {}", e.getMessage());
                }
                break;

            } catch (BudgetExhaustedException e) {
                LOG.warn("Attempt {} exhausted budget: {}", attempt, e.getMessage());

                // Generate attempt summary
                try {
                    Map<String, Object> summary = agent.generateAttemptSummary(3);
                    LOG.info("Attempt {} summary: {}", attempt,
                            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
                    agent.previousAttempts.add(summary);
                } catch (Exception se) {
                    LOG.error("Failed to generate attempt summary: {}", se.getMessage());
                    agent.previousAttempts.add(Map.of(
                            "problems", "Summary generation failed",
                            "actions", "Executed " + agent.commandsAndSummary.size() + " commands",
                            "lessons", "Unable to extract lessons",
                            "suggestions", "Try a different approach"));
                }

                if (attempt >= maxAttempts) {
                    LOG.error("All {} retry attempts exhausted", maxAttempts);

                    // Forced exit cycle
                    boolean forcedSuccess = runForcedExitCycle(
                            knowledgeLlmModel, agent, projectPath, projectUrl, runDir);
                    if (forcedSuccess) {
                        LOG.info("Forced exit cycle succeeded!");
                        finalSuccess = true;
                    }
                    break;
                }

                // Cleanup and reset for next attempt
                LOG.info("Cleaning up Docker resources...");
                DockerHelpers.cleanupContainer(agent.containerId, agent.dockerTag);

                LOG.info("Resetting agent state for next attempt...");
                resetAgentForRetry(agent, llmModel, env, toolRegistry,
                        cycleInstruction, summarizeCycle, removeProgressBars, searchWorkflowsSummary,
                        budget, workspaceRoot, projectPath, projectUrl, meta, repoContext, toolsList, languageGuidelines);

                LOG.info("State reset complete. {} previous attempt summaries.", agent.previousAttempts.size());

            } catch (Exception e) {
                LOG.error("Unexpected error during attempt {}: {}", attempt, e.getMessage(), e);
                break;

            } finally {
                // Flush transcript
                if (transcript != null) {
                    transcript.finalizeFullJson(agent.messages);
                }
            }
        }

        // Final cleanup
        if (transcript != null) {
            try { transcript.close(); } catch (Exception ignored) {}
        }

        // Generate replay bash script
        try {
            Path replayPath = runDir.resolve("replay_trace.sh");
            TraceToBash.saveBashScriptFromAgent(agent, replayPath);
            LOG.info("Replay script saved to: {}", replayPath);
        } catch (Exception e) {
            LOG.warn("Failed to generate replay script: {}", e.getMessage());
        }

        // Save tool metrics
        try {
            Map<String, Map<String, Object>> metrics = SharedUtils.getMetricsCollector().getAllMetrics();
            if (!metrics.isEmpty()) {
                Path metricsPath = runDir.resolve("tool_metrics.json");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(metricsPath.toFile(), metrics);
                LOG.info("Tool metrics saved to: {}", metricsPath);
                for (Map.Entry<String, Map<String, Object>> entry : metrics.entrySet()) {
                    Map<String, Object> m = entry.getValue();
                    LOG.info("  {}: {} calls, {}% success, avg {}s",
                            entry.getKey(),
                            m.get("total_calls"),
                            String.format("%.1f", (double) m.getOrDefault("success_rate_percent", 0.0)),
                            String.format("%.2f", (double) m.getOrDefault("avg_duration_seconds", 0.0)));
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to save tool metrics: {}", e.getMessage());
        }

        if (finalSuccess) {
            LOG.info("=" .repeat(80));
            LOG.info("Agent run completed successfully!");
            LOG.info("=" .repeat(80));
            return 0;
        } else {
            LOG.error("=" .repeat(80));
            LOG.error("Agent run failed to accomplish goals");
            LOG.error("=" .repeat(80));
            return 1;
        }
    }

    // -------------------------------------------------------------------------
    // Forced exit cycle (mirrors Python _run_forced_exit_cycle)
    // -------------------------------------------------------------------------
    private boolean runForcedExitCycle(LitellmModel knowledgeLlmModel, ExecutionAgent agent,
                                        String projectPath, String projectUrl, Path runDir) {
        LOG.info("=" .repeat(80));
        LOG.info("FORCED EXIT CYCLE - Attempting final solution with knowledge model");
        LOG.info("=" .repeat(80));

        // Build context from previous attempts
        StringBuilder lessonsSb = new StringBuilder();
        for (int i = 0; i < agent.previousAttempts.size(); i++) {
            try {
                lessonsSb.append("Attempt ").append(i + 1).append(": ")
                        .append(MAPPER.writeValueAsString(agent.previousAttempts.get(i))).append("\n");
            } catch (Exception ignored) {}
        }

        // Build forced exit prompt
        String lang = agent.repoContext != null ? agent.repoContext.language : "unknown";
        String prompt = "You are an expert at setting up software projects for testing. An automated agent has failed " +
                "multiple times to install and test '" + projectPath + "'. Produce a FINAL SOLUTION.\n\n" +
                "PROJECT: " + projectPath + " @ " + projectUrl + " (language: " + lang + ")\n\n" +
                "PREVIOUS LESSONS:\n" + lessonsSb + "\n\n" +
                "Produce:\n" +
                "1. A complete Dockerfile (Ubuntu 22.04 or 24.04 based)\n" +
                "2. A bash script to install dependencies and run tests\n\n" +
                "OUTPUT FORMAT (exactly):\n" +
                "```dockerfile\n<Dockerfile>\n```\n\n" +
                "```bash\n<bash script>\n```";

        String dockerfileContent = null;
        String bashScript = null;

        for (int i = 1; i <= 3; i++) {
            try {
                Map<String, Object> resp = knowledgeLlmModel.query(
                        List.of(Map.of("role", "user", "content", prompt)));
                String content = (String) resp.getOrDefault("content", "");

                dockerfileContent = extractCodeBlock(content, "dockerfile");
                bashScript = extractCodeBlock(content, "bash");

                if (dockerfileContent != null && bashScript != null) break;
            } catch (Exception e) {
                LOG.error("LLM query failed (attempt {}): {}", i, e.getMessage());
            }
        }

        if (dockerfileContent == null || bashScript == null) {
            LOG.error("Failed to extract Dockerfile and bash script from LLM");
            return false;
        }

        // Save and run
        Path forcedDir = runDir.resolve("forced_exit_cycle");
        try {
            Files.createDirectories(forcedDir);
            Files.writeString(forcedDir.resolve("Dockerfile"), dockerfileContent, StandardCharsets.UTF_8);
            Files.writeString(forcedDir.resolve("run_tests.sh"), bashScript, StandardCharsets.UTF_8);

            // Generate a unique tag
            String tag = "forced-exit-" + projectPath.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase()
                    + "-" + Long.toHexString(System.currentTimeMillis());

            // Build image
            ProcessBuilder buildPb = new ProcessBuilder(
                    "docker", "build", "-t", tag, "-f",
                    forcedDir.resolve("Dockerfile").toString(), forcedDir.toString());
            buildPb.redirectErrorStream(true);
            Process buildProc = buildPb.start();
            String buildOutput = new String(buildProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int buildRc = buildProc.waitFor();

            if (buildRc != 0) {
                LOG.error("Docker build failed:\n{}", buildOutput);
                Files.writeString(forcedDir.resolve("docker_build.log"), buildOutput, StandardCharsets.UTF_8);
                return false;
            }
            LOG.info("Docker image built: {}", tag);

            // Run tests
            ProcessBuilder runPb = new ProcessBuilder(
                    "docker", "run", "--rm",
                    "-v", forcedDir.resolve("run_tests.sh") + ":/run_tests.sh:ro",
                    tag, "bash", "/run_tests.sh");
            runPb.redirectErrorStream(true);
            Process runProc = runPb.start();
            String runOutput = new String(runProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int runRc = runProc.waitFor();

            Files.writeString(forcedDir.resolve("test_output.log"),
                    "Exit code: " + runRc + "\n\n" + runOutput, StandardCharsets.UTF_8);
            LOG.info("Test execution completed with rc={}", runRc);

            // Cleanup image
            try {
                new ProcessBuilder("docker", "rmi", tag).start().waitFor();
            } catch (Exception ignored) {}

            // Consider success if tests ran (even with failures)
            return true;

        } catch (Exception e) {
            LOG.error("Error in forced exit cycle: {}", e.getMessage(), e);
            return false;
        }
    }

    private String extractCodeBlock(String text, String lang) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "```" + lang + "\\s*\\n(.*?)```",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) return m.group(1).strip();
        return null;
    }

    // -------------------------------------------------------------------------
    // Default task
    // -------------------------------------------------------------------------
    private String buildDefaultTask(Map<String, Object> meta) {
        return "Your objective is to set up, build, install, and run the project's test suite inside a container. " +
                "You must produce a Dockerfile (written via write_to_file) that clones the repo and prepares the environment, " +
                "then run installation/build/test commands via linux_terminal until tests can be executed, and write " +
                "TEST_RESULTS.txt with outcomes. Only declare goals_accomplished once Dockerfile exists and results are recorded.\n\n" +
                "IMPORTANT: The task is considered successful if ~80% or more of the tests pass. " +
                "Having a few failing tests or errors is acceptable and expected. " +
                "Once you have a substantial majority of tests passing (~80%+), declare goals accomplished.";
    }

    // -------------------------------------------------------------------------
    // Agent reset between retries
    // -------------------------------------------------------------------------
    private void resetAgentForRetry(ExecutionAgent agent, LitellmModel llmModel,
                                     ExecutionEnvironment env, ToolRegistry toolRegistry,
                                     String cycleInstruction, String summarizeCycle,
                                     String removeProgressBars, String searchWorkflowsSummary,
                                     int stepLimit, String workspacePath, String projectPath,
                                     String projectUrl, Map<String, Object> meta,
                                     RepoContext repoContext, String toolsDoc, String langGuidelines) {
        List<Map<String, Object>> savedAttempts = new ArrayList<>(agent.previousAttempts);

        agent.commandsAndSummary = new ArrayList<>();
        agent.writtenFiles = new ArrayList<>();
        agent.messages = new ArrayList<>();
        agent.cycleCount = 0;
        agent.lastAction = null;
        agent.lastResult = null;
        agent.lastThoughts = null;
        agent.lastFormatError = null;
        agent.lastFailedResponse = null;
        agent.commandStuck = false;
        agent.currentLogfile = null;
        agent.stuckCommands = new ArrayList<>();
        agent.containerId = null;
        agent.dockerTag = "";

        agent.previousAttempts = savedAttempts;
        agent.model = llmModel;
        agent.env = env;
        agent.toolRegistry = toolRegistry;
        agent.workspacePath = workspacePath;
        agent.projectPath = projectPath;
        agent.projectUrl = projectUrl;
        agent.hyperparams = meta;
        agent.repoContext = repoContext;
        agent.toolsDocString = toolsDoc;
        agent.languageGuidelines = langGuidelines;
        agent.cycleInstruction = cycleInstruction;
        agent.summarizeCycle = summarizeCycle;
        agent.removeProgressBarsPrompt = removeProgressBars;
        agent.searchWorkflowsSummaryPrompt = searchWorkflowsSummary;
        agent.stepLimit = stepLimit;
    }

    // -------------------------------------------------------------------------
    // Prompt file loading
    // -------------------------------------------------------------------------
    private String loadPromptFile(String name) {
        // Try from filesystem first
        Path fsPath = Path.of(promptFilesDir, name);
        if (Files.exists(fsPath)) {
            try {
                return Files.readString(fsPath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.warn("Error reading prompt file {}: {}", fsPath, e.getMessage());
            }
        }

        // Try from classpath resources
        String resourcePath = "/prompt_files/" + name;
        try (InputStream is = Main.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOG.warn("Error reading classpath resource {}: {}", resourcePath, e.getMessage());
        }

        LOG.warn("Prompt file not found: {} (tried {} and classpath {})", name, fsPath, resourcePath);
        return "";
    }

    private String loadLanguageGuidelines(String language) {
        if (language == null || language.isBlank()) return "";

        Map<String, String> langMap = new HashMap<>(Map.of(
                "python", "python_guidelines",
                "py", "python_guidelines",
                "java", "java_guidelines",
                "javascript", "javascript_guidelines",
                "js", "javascript_guidelines",
                "typescript", "javascript_guidelines",
                "ts", "javascript_guidelines",
                "c", "c_guidelines",
                "c++", "cpp_guidelines",
                "cpp", "cpp_guidelines"
        ));
        langMap.put("rust", "rust_guidelines");

        String key = language.toLowerCase().strip();
        String guidelineName = langMap.getOrDefault(key, key + "_guidelines");
        String content = loadPromptFile(guidelineName);
        if (!content.isBlank()) {
            LOG.info("Loaded language guidelines for: {} ({})", language, guidelineName);
        }
        return content;
    }
}
