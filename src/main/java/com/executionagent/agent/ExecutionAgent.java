package com.executionagent.agent;

import com.executionagent.context.RepoContext;
import com.executionagent.env.ExecutionEnvironment;
import com.executionagent.exceptions.*;
import com.executionagent.models.LlmModel;
import com.executionagent.persistence.StatePersistence;
import com.executionagent.repetition.RepetitionDetector;
import com.executionagent.tools.ToolRegistry;
import com.executionagent.utils.SharedUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.*;

/**
 * Core execution agent that drives the plan-execute-summarize cycle.
 * Mirrors Python ExecutionAgent.
 */
public class ExecutionAgent {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Core dependencies
    // -------------------------------------------------------------------------
    public LlmModel model;
    public ExecutionEnvironment env;
    public ToolRegistry toolRegistry;

    // -------------------------------------------------------------------------
    // Prompts
    // -------------------------------------------------------------------------
    public String cycleInstruction;
    public String summarizeCycle;
    public String removeProgressBarsPrompt;
    public String searchWorkflowsSummaryPrompt;

    // -------------------------------------------------------------------------
    // Limits
    // -------------------------------------------------------------------------
    public int stepLimit;

    // -------------------------------------------------------------------------
    // State (carry-overs)
    // -------------------------------------------------------------------------
    public List<Object[]> commandsAndSummary = new ArrayList<>();  // [callStr, summaryMap]
    public List<String[]> writtenFiles = new ArrayList<>();         // [target, location, path, content]
    public String promptText = "";

    // -------------------------------------------------------------------------
    // Runtime substrate (set by tools)
    // -------------------------------------------------------------------------
    public String containerId;
    public String dockerTag = "";

    // -------------------------------------------------------------------------
    // Injected by Main.java
    // -------------------------------------------------------------------------
    public String workspacePath = "";
    public String projectPath = "";
    public String projectUrl = "";
    public Map<String, Object> hyperparams = new HashMap<>();
    public RepoContext repoContext;
    public String toolsDocString = "";
    public String languageGuidelines = "";

    // -------------------------------------------------------------------------
    // Conversation tail
    // -------------------------------------------------------------------------
    public List<Map<String, Object>> messages = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Cycle counter
    // -------------------------------------------------------------------------
    public int cycleCount = 0;

    // -------------------------------------------------------------------------
    // Last action/result/thoughts (for prompt construction)
    // -------------------------------------------------------------------------
    public Map<String, Object> lastAction;
    public String lastResult;
    public String lastThoughts;

    // -------------------------------------------------------------------------
    // Format error state
    // -------------------------------------------------------------------------
    public String lastFormatError;
    public String lastFailedResponse;

    // -------------------------------------------------------------------------
    // Stuck state
    // -------------------------------------------------------------------------
    public boolean commandStuck = false;
    public String currentLogfile;
    public List<String> stuckCommands = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Previous attempts (preserved across retries)
    // -------------------------------------------------------------------------
    public List<Map<String, Object>> previousAttempts = new ArrayList<>();

    // -------------------------------------------------------------------------
    // State persistence
    // -------------------------------------------------------------------------
    public StatePersistence statePersistence;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------
    public ExecutionAgent(LlmModel model, ExecutionEnvironment env, ToolRegistry toolRegistry,
                           String cycleInstruction, String summarizeCycle,
                           String removeProgressBarsPrompt, String searchWorkflowsSummaryPrompt,
                           int stepLimit) {
        this.model = model;
        this.env = env;
        this.toolRegistry = toolRegistry;
        this.cycleInstruction = cycleInstruction;
        this.summarizeCycle = summarizeCycle;
        this.removeProgressBarsPrompt = removeProgressBarsPrompt;
        this.searchWorkflowsSummaryPrompt = searchWorkflowsSummaryPrompt;
        this.stepLimit = stepLimit;
    }

    // -------------------------------------------------------------------------
    // Message helpers
    // -------------------------------------------------------------------------
    public void addMessage(String role, String content, String tag) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        msg.put("tag", tag);
        messages.add(msg);
    }

    public void addMessage(String role, String content) {
        addMessage(role, content, null);
    }

    // -------------------------------------------------------------------------
    // Command history helpers
    // -------------------------------------------------------------------------
    private List<Map<String, Object>> lastCommands() {
        List<Map<String, Object>> cmds = new ArrayList<>();
        int start = Math.max(0, commandsAndSummary.size() - 20);
        for (int i = start; i < commandsAndSummary.size(); i++) {
            Object[] entry = commandsAndSummary.get(i);
            String callStr = (String) entry[0];
            Matcher m = Pattern.compile("Call to tool (\\S+) with arguments (.*)$").matcher(callStr);
            if (!m.find()) continue;
            String name = m.group(1);
            String rawArgs = m.group(2);
            Map<String, Object> args;
            try {
                args = MAPPER.readValue(rawArgs, new TypeReference<>() {});
            } catch (Exception e) {
                args = new HashMap<>();
            }
            Map<String, Object> cmd = new LinkedHashMap<>();
            cmd.put("name", name);
            cmd.put("args", args);
            cmds.add(cmd);
        }
        return cmds;
    }

    // -------------------------------------------------------------------------
    // Prompt construction
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public String buildInstancePrompt(String task) {
        RepoContext ctx = repoContext;
        List<String> parts = new ArrayList<>();

        if (ctx != null) {
            parts.add("Project path: " + ctx.projectPath);
            parts.add("Project URL: " + ctx.projectUrl);
            parts.add("Primary language: " + ctx.language);
            parts.add("");

            if (languageGuidelines != null && !languageGuidelines.isBlank()) {
                parts.add("Language-specific guidelines for " + ctx.language + ":");
                parts.add(languageGuidelines);
                parts.add("");
            }

            if (ctx.problemsMemory != null && !ctx.problemsMemory.isBlank()) {
                parts.add("Problems memory (from prior runs):");
                parts.add("```");
                parts.add(ctx.problemsMemory);
                parts.add("```");
                parts.add("");
            }

            // Include relevant CI/CD workflow
            if (!ctx.workflowContents.isEmpty()) {
                List<String[]> selected = selectRelevantWorkflows(ctx.workflowContents);
                if (!selected.isEmpty()) {
                    parts.add("Most relevant CI/CD workflow (Linux build/test):");
                    for (String[] wf : selected) {
                        parts.add("\nFile: " + wf[0] + "\n```yaml\n" + wf[1] + "\n```");
                    }
                    parts.add("");
                }
            }

            if (!ctx.dockerfileContents.isEmpty()) {
                parts.add("Existing Dockerfiles found in the repository:");
                for (String[] df : ctx.dockerfileContents) {
                    if (df[1] != null && !df[1].isBlank()) {
                        parts.add("\nFile: " + df[0] + "\n```dockerfile\n" + df[1] + "\n```");
                    }
                }
                parts.add("");
            }

            if (ctx.unifiedSummary != null && !ctx.unifiedSummary.isBlank()) {
                parts.add("Setup/Build/Test Summary:");
                parts.add("```");
                parts.add(ctx.unifiedSummary);
                parts.add("```");
                parts.add("");
            }
        }

        // Previous attempts
        if (!previousAttempts.isEmpty()) {
            parts.add("=".repeat(80));
            parts.add("CRITICAL: PREVIOUS ATTEMPT SUMMARIES");
            parts.add("=".repeat(80));
            parts.add("");
            parts.add("The following attempts have ALREADY been made. YOU MUST learn from them.");
            parts.add("");

            for (int idx = 0; idx < previousAttempts.size(); idx++) {
                Map<String, Object> attempt = previousAttempts.get(idx);
                parts.add("=".repeat(40));
                parts.add("ATTEMPT " + (idx + 1) + " OF " + previousAttempts.size());
                parts.add("=".repeat(40));
                parts.add("");

                addAttemptSection(parts, attempt, "problems", "## PROBLEMS ENCOUNTERED:");
                addAttemptSection(parts, attempt, "actions", "## SEQUENCE OF ACTIONS TAKEN:");
                addAttemptSection(parts, attempt, "progress", "## PROGRESS MADE:");
                addAttemptSection(parts, attempt, "lessons", "## KEY LESSONS LEARNED:");
                addAttemptSection(parts, attempt, "suggestions", "## SUGGESTIONS FOR THIS ATTEMPT:");

                if (attempt.containsKey("dockerfile_recommendation")) {
                    parts.add("## RECOMMENDED DOCKERFILE:");
                    parts.add("```dockerfile");
                    parts.add(toStr(attempt.get("dockerfile_recommendation")));
                    parts.add("```");
                    parts.add("");
                }
                if (attempt.containsKey("dockerfile_used")) {
                    parts.add("## DOCKERFILE USED:");
                    parts.add("```dockerfile");
                    parts.add(toStr(attempt.get("dockerfile_used")));
                    parts.add("```");
                    parts.add("");
                }
            }

            parts.add("=".repeat(80));
            parts.add("END OF PREVIOUS ATTEMPTS - NOW START YOUR NEW ATTEMPT");
            parts.add("=".repeat(80));
            parts.add("");
        }

        // Container state awareness
        parts.add("=".repeat(60));
        parts.add("CURRENT STATE:");
        if (containerId != null && !containerId.isBlank()) {
            parts.add("You are currently operating INSIDE a Docker container.");
            parts.add("The container is running and ready for commands.");
        } else {
            parts.add("You have NOT yet created a Docker container.");
            parts.add("Your first priority should be to write a Dockerfile.");
            parts.add("Until a container is created, only limited commands are available (ls, cat, grep, find, etc.).");
            if (ctx != null && ctx.localRepoAvailable) {
                parts.add("");
                parts.add("LOCAL REPOSITORY ACCESS:");
                parts.add("The repository has been cloned locally to: " + workspacePath + "/" + ctx.projectPath);
                parts.add("You can explore it with terminal commands before creating your Dockerfile.");
            }
        }
        parts.add("=".repeat(60));
        parts.add("");

        // Command history
        if (!commandsAndSummary.isEmpty()) {
            parts.add("History of executed commands and summaries (most recent last):");
            int start = Math.max(0, commandsAndSummary.size() - 20);
            for (int i = start; i < commandsAndSummary.size(); i++) {
                Object[] entry = commandsAndSummary.get(i);
                String callStr = (String) entry[0];
                Object summ = entry[1];
                String summStr;
                try {
                    summStr = MAPPER.writeValueAsString(summ);
                } catch (Exception e) {
                    summStr = String.valueOf(summ);
                }
                parts.add("- " + callStr + "\n  Summary: " + summStr);
            }
            parts.add("");
        }

        parts.add("High-level objective:");
        parts.add(task);
        parts.add("");

        parts.add("Tool interface reminder:");
        parts.add(toolsDocString != null ? toolsDocString : "");
        parts.add("");

        parts.add("Planning instruction:");
        parts.add(cycleInstruction.trim());

        parts.add("\nIMPORTANT: Output ONLY a single JSON object for the Response schema. No extra text.");
        return String.join("\n", parts);
    }

    private List<String[]> selectRelevantWorkflows(List<String[]> workflows) {
        List<String[]> linuxWorkflows = new ArrayList<>();
        for (String[] wf : workflows) {
            String content = wf[1].toLowerCase();
            boolean isLinux = content.contains("ubuntu") || content.contains("runs-on: ubuntu");
            boolean isTestBuild = content.contains("test") || content.contains("build");
            if (isLinux && isTestBuild) linuxWorkflows.add(wf);
        }
        if (linuxWorkflows.isEmpty()) linuxWorkflows = workflows;
        return linuxWorkflows.subList(0, Math.min(2, linuxWorkflows.size()));
    }

    private void addAttemptSection(List<String> parts, Map<String, Object> attempt,
                                    String key, String header) {
        if (attempt.containsKey(key)) {
            parts.add(header);
            parts.add(toStr(attempt.get(key)));
            parts.add("");
        }
    }

    private String toStr(Object val) {
        if (val == null) return "";
        if (val instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(val);
        } catch (Exception e) {
            return val.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Previous cycle messages
    // -------------------------------------------------------------------------
    private List<Map<String, String>> buildPreviousCycleMessages() {
        List<Map<String, String>> msgs = new ArrayList<>();
        if (lastAction == null) return msgs;

        // Assistant message: last thoughts + last command
        List<String> assistantParts = new ArrayList<>();
        if (lastThoughts != null && !lastThoughts.isBlank()) {
            assistantParts.add("Here are my thoughts from the previous cycle:\n" + lastThoughts);
            assistantParts.add("");
        }
        assistantParts.add("The last command I suggested was:");
        assistantParts.add("Tool: '" + lastAction.get("name") + "'");
        String argsStr;
        try {
            argsStr = MAPPER.writeValueAsString(lastAction.getOrDefault("args", Map.of()));
        } catch (Exception e) {
            argsStr = "{}";
        }
        assistantParts.add("Arguments: " + argsStr);

        msgs.add(Map.of("role", "assistant", "content", String.join("\n", assistantParts)));

        // User message: result
        List<String> userParts = new ArrayList<>();
        userParts.add("I have executed the last command and here is the result:");
        userParts.add("");
        userParts.add("```");
        userParts.add(lastResult != null ? lastResult : "(no output)");
        userParts.add("```");
        userParts.add("");

        if (lastFormatError != null) {
            userParts.add("=".repeat(60));
            userParts.add("ERROR: Your last response was NOT valid and could NOT be parsed.");
            userParts.add("Parsing error: " + lastFormatError);
            userParts.add("Please provide a new response with CORRECT JSON format.");
            userParts.add("=".repeat(60));
            userParts.add("");
        }

        msgs.add(Map.of("role", "user", "content", String.join("\n", userParts)));
        return msgs;
    }

    // -------------------------------------------------------------------------
    // Plan next action
    // -------------------------------------------------------------------------
    public Map<String, Object> planNextAction(String task, int maxRetries) {
        String systemMsg = "You are an AI assistant specialized in automatically setting up a given project and making it ready to run (by installing dependencies and making the correct configurations). Your role involves automating the process of gathering project information/requirements and dependencies, setting up the execution environment, and running test suites. You should always gather essential details such as language and version, dependencies, and testing frameworks; Following that you set up the environment and execute test suites based on collected information; Finally, you assess test outcomes, identify failing cases. Your personality is characterized by efficiency, attention to detail, and a commitment to streamlining the installation and tests execution of the given project. MAIN TASK := SETUP AND RUN TESTS OF THE TARGET PROJECT INSIDE A DOCKER CONTAINER.";
        String userMsg = buildInstancePrompt(task);
        promptText = systemMsg + "\n\n" + userMsg;

        List<Map<String, String>> baseMessages = new ArrayList<>();
        baseMessages.add(Map.of("role", "system", "content", systemMsg));
        baseMessages.add(Map.of("role", "user", "content", userMsg));
        baseMessages.addAll(buildPreviousCycleMessages());

        FormatErrorException lastError = null;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            List<Map<String, String>> msgs = new ArrayList<>(baseMessages);

            if (lastError != null) {
                msgs.add(Map.of("role", "assistant", "content", lastFailedResponse != null ? lastFailedResponse : ""));
                msgs.add(Map.of("role", "user", "content",
                        "ERROR: Your response was NOT valid and could NOT be parsed.\n" +
                        "Parsing error: " + lastError.getMessage() + "\n" +
                        "Please provide a new response with CORRECT JSON format. " +
                        "Output ONLY a single JSON object matching the Response schema."));
            }

            LOG.info("Querying LLM for next action (cycle={}, attempt={}/{})", cycleCount + 1, attempt + 1, maxRetries);
            Map<String, Object> resp = model.query(msgs, 1.0);
            String content = (String) resp.getOrDefault("content", "");

            addMessage("assistant", content);

            try {
                Map<String, Object> payload = extractJsonObject(content);
                Object cmdObj = payload.get("command");
                if (!(cmdObj instanceof Map)) {
                    throw new FormatErrorException("Missing or invalid 'command' field");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> cmd = (Map<String, Object>) cmdObj;
                String name = (String) cmd.get("name");
                if (name == null || name.isBlank()) {
                    throw new FormatErrorException("Missing or invalid command.name");
                }
                Object argsObj = cmd.getOrDefault("args", new HashMap<>());
                if (!(argsObj instanceof Map)) argsObj = new HashMap<>();
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) argsObj;

                Map<String, Object> normArgs = toolRegistry.normalizeAndValidate(name, args);

                // Success: clear format error
                lastFormatError = null;

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("tool_name", name);
                result.put("tool_args", normArgs);
                result.put("raw", payload);
                return result;

            } catch (FormatErrorException e) {
                lastError = e;
                lastFailedResponse = content;
                LOG.warn("Format error on attempt {}/{}: {}", attempt + 1, maxRetries, e.getMessage());
            } catch (Exception e) {
                lastError = new FormatErrorException("Parse error: " + e.getMessage(), e);
                lastFailedResponse = content;
                LOG.warn("Parse error on attempt {}/{}: {}", attempt + 1, maxRetries, e.getMessage());
            }
        }

        lastFormatError = lastError != null ? lastError.getMessage() : "Unknown format error";
        throw new FormatErrorException("Failed after " + maxRetries + " attempts. Last error: " +
                (lastError != null ? lastError.getMessage() : "unknown"));
    }

    // -------------------------------------------------------------------------
    // JSON extraction
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractJsonObject(String text) {
        if (text == null) text = "";
        text = text.trim();

        // Try fenced JSON block first
        Pattern fenced = Pattern.compile("```json\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = fenced.matcher(text);
        String candidate = null;
        if (m.find()) {
            candidate = m.group(1).trim();
        } else {
            candidate = extractJsonSubstring(text);
        }

        if (candidate == null || candidate.isBlank()) {
            throw new FormatErrorException("No JSON object found in model output.");
        }

        try {
            Object parsed = MAPPER.readValue(candidate, Object.class);
            if (parsed instanceof Map) {
                return fixEscapedNewlines((Map<String, Object>) parsed);
            }
            throw new FormatErrorException("Top-level JSON must be an object.");
        } catch (Exception e) {
            throw new FormatErrorException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    private static String extractJsonSubstring(String text) {
        int start = text.indexOf('{');
        if (start == -1) return null;

        int depth = 0;
        boolean inString = false;
        boolean escapeNext = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escapeNext) { escapeNext = false; continue; }
            if (c == '\\' && inString) { escapeNext = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return text.substring(start);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fixEscapedNewlines(Map<String, Object> obj) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : obj.entrySet()) {
            result.put(entry.getKey(), fixValue(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object fixValue(Object val) {
        if (val instanceof String s) {
            return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
        } else if (val instanceof Map) {
            return fixEscapedNewlines((Map<String, Object>) val);
        } else if (val instanceof List) {
            List<Object> list = new ArrayList<>();
            for (Object item : (List<?>) val) list.add(fixValue(item));
            return list;
        }
        return val;
    }

    // -------------------------------------------------------------------------
    // Summarize last command
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Map<String, Object> summarizeLastCommand(String lastOutput) {
        String truncated = SharedUtils.smartTruncateOutput(lastOutput != null ? lastOutput : "",
                30000, 5000, 25000);
        String prompt = summarizeCycle.trim() + "\n\n" + truncated;

        LOG.info("Querying LLM for summarization (cycle={})", cycleCount);
        Map<String, Object> resp = model.query(List.of(Map.of("role", "user", "content", prompt)), 1.0);
        String txt = (String) resp.getOrDefault("content", "");

        try {
            return extractJsonObject(txt);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("summary", txt.length() > 2000 ? txt.substring(0, 2000) : txt);
            fallback.put("Setup details:", "");
            fallback.put("Meaningful next setps", "");
            return fallback;
        }
    }

    // -------------------------------------------------------------------------
    // Execute action
    // -------------------------------------------------------------------------
    public Map<String, Object> executeAction(String name, Map<String, Object> args) {
        LOG.info("[EXECUTE] Starting execution of tool '{}'", name);

        // Repetition check
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("name", name);
        candidate.put("args", args);
        if (RepetitionDetector.isRepetition(lastCommands(), candidate)) {
            throw new FormatErrorException(
                    "Repetition detected in the last commands. Choose a different tool and/or a different approach.");
        }

        Object result = toolRegistry.call(name, args, this);
        LOG.info("[EXECUTE] Tool '{}' completed", name);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("output", String.valueOf(result));
        out.put("returncode", 0);
        out.put("tool_name", name);
        out.put("tool_args", args);
        return out;
    }

    // -------------------------------------------------------------------------
    // One cycle
    // -------------------------------------------------------------------------
    public Map<String, Object> runOneCycle(String task) {
        cycleCount++;
        LOG.info("[CYCLE] Starting cycle {}", cycleCount);

        LOG.info("[CYCLE] Phase 1: Planning next action...");
        Map<String, Object> planned = planNextAction(task, 5);
        String name = (String) planned.get("tool_name");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) planned.get("tool_args");
        LOG.info("[CYCLE] Phase 1 complete: tool='{}'", name);

        LOG.info("[CYCLE] Phase 2: Executing action...");
        Map<String, Object> raw = executeAction(name, args);
        LOG.info("[CYCLE] Phase 2 complete: output length={}", ((String) raw.get("output")).length());

        LOG.info("[CYCLE] Phase 3: Summarizing output...");
        Map<String, Object> summary = summarizeLastCommand((String) raw.get("output"));
        LOG.info("[CYCLE] Phase 3 complete: summary keys={}", summary.keySet());

        // Store state for next cycle
        lastAction = Map.of("name", name, "args", args);
        lastResult = SharedUtils.smartTruncateOutput((String) raw.get("output"));
        @SuppressWarnings("unchecked")
        Map<String, Object> rawPayload = (Map<String, Object>) planned.get("raw");
        lastThoughts = rawPayload != null ? (String) rawPayload.getOrDefault("thoughts", "") : "";

        String argsStr;
        try {
            argsStr = MAPPER.writeValueAsString(args);
        } catch (Exception e) {
            argsStr = args.toString();
        }
        commandsAndSummary.add(new Object[]{"Call to tool " + name + " with arguments " + argsStr, summary});

        LOG.info("[CYCLE] Cycle {} complete", cycleCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool_call", Map.of("command", Map.of("name", name, "args", args)));
        result.put("result", raw);
        result.put("summary", summary);
        return result;
    }

    // -------------------------------------------------------------------------
    // Main loop
    // -------------------------------------------------------------------------
    public void run(String task) {
        for (int step = 1; step <= stepLimit; step++) {
            try {
                runOneCycle(task);

                if (statePersistence != null) {
                    statePersistence.saveState(this);
                }

            } catch (GoalsAccomplishedException e) {
                if (statePersistence != null) statePersistence.saveState(this);
                return;

            } catch (FormatErrorException e) {
                String note = "Internal note (format error after retries exhausted) at step " + step;
                commandsAndSummary.add(new Object[]{note, Map.of("summary", e.getMessage())});
                addMessage("developer", e.getMessage(), "format_error");
                continue;

            } catch (Exception e) {
                lastFormatError = null;
                String note = "Internal note (unexpected error) at step " + step;
                commandsAndSummary.add(new Object[]{note, Map.of("summary", e.getClass().getSimpleName() + ": " + e.getMessage())});
                addMessage("developer", e.getClass().getSimpleName() + ": " + e.getMessage(), "unexpected_error");
                LOG.error("Unexpected error at step {}: {}", step, e.getMessage(), e);

                if (statePersistence != null) statePersistence.saveState(this);
                continue;
            }
        }

        commandsAndSummary.add(new Object[]{"Run stopped", Map.of("summary", "Step limit (" + stepLimit + ") reached.")});
        if (statePersistence != null) statePersistence.saveState(this);
        throw new BudgetExhaustedException("Step limit (" + stepLimit + ") reached without accomplishing goals.");
    }

    // -------------------------------------------------------------------------
    // Generate attempt summary
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateAttemptSummary(int maxRetries) {
        // Build detailed action sequence
        List<Map<String, Object>> detailedActions = new ArrayList<>();
        List<Map<String, Object>> errorOutputs = new ArrayList<>();
        List<Map<String, Object>> successfulSteps = new ArrayList<>();
        String dockerfileContent = "";

        for (int idx = 0; idx < commandsAndSummary.size(); idx++) {
            Object[] entry = commandsAndSummary.get(idx);
            String callStr = (String) entry[0];
            Object summaryObj = entry[1];

            Map<String, Object> actionEntry = new LinkedHashMap<>();
            actionEntry.put("step", idx + 1);
            actionEntry.put("command", callStr);

            if (summaryObj instanceof Map<?, ?> summaryMap) {
                Map<String, Object> summary = (Map<String, Object>) summaryMap;
                actionEntry.put("summary", summary.getOrDefault("summary", ""));
                actionEntry.put("setup_details", summary.getOrDefault("Setup details:", ""));
                actionEntry.put("next_steps", summary.getOrDefault("Meaningful next setps", ""));

                String summaryText = String.valueOf(summary.getOrDefault("summary", "")).toLowerCase();
                if (summaryText.contains("error") || summaryText.contains("failed") ||
                        summaryText.contains("not found") || summaryText.contains("cannot") ||
                        summaryText.contains("missing")) {
                    errorOutputs.add(Map.of("step", idx + 1, "command", callStr,
                            "error_summary", summary.getOrDefault("summary", "")));
                } else {
                    successfulSteps.add(Map.of("step", idx + 1, "command", callStr,
                            "result", String.valueOf(summary.getOrDefault("summary", "")).substring(0,
                                    Math.min(200, String.valueOf(summary.getOrDefault("summary", "")).length()))));
                }
            }
            detailedActions.add(actionEntry);
        }

        // Check for Dockerfile
        for (String[] wf : writtenFiles) {
            if (wf.length >= 4 && "dockerfile".equalsIgnoreCase(wf[0])) {
                dockerfileContent = wf[3];
            }
        }

        // Build prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are analyzing a failed attempt to set up and run tests.\n\n");
        prompt.append("PROJECT: ").append(projectUrl).append(" / ").append(projectPath).append("\n");
        prompt.append("Cycles: ").append(commandsAndSummary.size()).append("\n\n");

        if (!dockerfileContent.isBlank()) {
            prompt.append("Dockerfile used:\n```dockerfile\n").append(dockerfileContent).append("\n```\n\n");
        }

        prompt.append("Recent actions (last 30):\n");
        int start = Math.max(0, detailedActions.size() - 30);
        for (int i = start; i < detailedActions.size(); i++) {
            Map<String, Object> a = detailedActions.get(i);
            prompt.append("### Step ").append(a.get("step")).append("\n");
            prompt.append("Command: ").append(a.get("command")).append("\n");
            if (a.get("summary") != null) {
                prompt.append("Result: ").append(a.get("summary")).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("\nProvide a JSON analysis with: problems, actions, progress, lessons, suggestions, dockerfile_recommendation.\n");
        prompt.append("Be specific with exact commands and error messages.\n");
        prompt.append("Respond ONLY with a valid JSON object.");

        Exception lastError = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                Map<String, Object> resp = model.query(
                        List.of(Map.of("role", "user", "content", prompt.toString())), 0.7);
                String content = (String) resp.getOrDefault("content", "");
                Map<String, Object> summary = extractJsonObject(content);

                List<String> required = List.of("problems", "actions", "lessons", "suggestions");
                if (required.stream().allMatch(summary::containsKey)) {
                    if (!dockerfileContent.isBlank() && !summary.containsKey("dockerfile_used")) {
                        summary.put("dockerfile_used", dockerfileContent);
                    }
                    return summary;
                }
                lastError = new RuntimeException("Missing required fields: " + summary.keySet());
            } catch (Exception e) {
                lastError = e;
                LOG.warn("Attempt summary retry {}/{}: {}", attempt + 1, maxRetries, e.getMessage());
            }
        }

        // Fallback
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("problems", "Failed to extract: " + (lastError != null ? lastError.getMessage() : "unknown"));
        fallback.put("actions", "Executed " + detailedActions.size() + " steps");
        fallback.put("progress", successfulSteps.size() + " succeeded, " + errorOutputs.size() + " failed");
        fallback.put("lessons", "Review logs manually");
        fallback.put("suggestions", "Try a different approach");
        fallback.put("dockerfile_used", dockerfileContent);
        return fallback;
    }
}
