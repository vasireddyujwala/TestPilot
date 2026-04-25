package com.executionagent.tools;

import com.executionagent.agent.ExecutionAgent;
import com.executionagent.exceptions.GoalsAccomplishedException;
import com.executionagent.utils.DockerHelpers;
import com.executionagent.utils.SharedUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * The five registered tools for ExecutionAgent.
 * Mirrors Python tools.py: linux_terminal, read_file, write_to_file,
 * search_docker_image, goals_accomplished.
 */
public class Tools {

    private static final Logger LOG = LoggerFactory.getLogger(Tools.class);

    // Commands allowed before a container exists
    private static final Set<String> PRE_CONTAINER_CMDS = Set.of(
            "tree", "ls", "cat", "head", "tail", "more", "less", "grep", "find");

    // Commands that are blocked inside a container
    private static final Set<String> BLOCKED_INTERACTIVE = Set.of(
            "vim", "vi", "nano", "emacs", "less", "more", "python", "ipython",
            "python3", "irb", "node", "mysql", "psql", "redis-cli");

    // -------------------------------------------------------------------------
    // linux_terminal
    // -------------------------------------------------------------------------

    public static Object linuxTerminal(Map<String, Object> args, Object agentObj) {
        long startTime = System.currentTimeMillis();
        String toolName = "linux_terminal";

        ExecutionAgent agent = (ExecutionAgent) agentObj;
        String command = (String) args.getOrDefault("command", "");
        String cwd = (String) args.getOrDefault("cwd", "");
        Object timeoutObj = args.get("timeout");
        int timeout = timeoutObj instanceof Number n ? n.intValue() : SharedUtils.DEFAULT_EXEC_TIMEOUT;

        try {
            String result = doLinuxTerminal(command, cwd, timeout, agent);
            double dur = (System.currentTimeMillis() - startTime) / 1000.0;
            SharedUtils.getMetricsCollector().record(toolName, dur, true, null);
            return result;
        } catch (GoalsAccomplishedException e) {
            throw e;
        } catch (Exception e) {
            double dur = (System.currentTimeMillis() - startTime) / 1000.0;
            SharedUtils.getMetricsCollector().record(toolName, dur, false, e.getMessage());
            throw e;
        }
    }

    private static String doLinuxTerminal(String command, String cwd, int timeout, ExecutionAgent agent) {
        command = command.trim();

        // Handle stuck command protocol
        if (agent.commandStuck) {
            return handleStuckCommand(command, agent);
        }

        String containerId = agent.containerId;

        if (containerId == null || containerId.isBlank()) {
            // Pre-container mode: only allow safe read-only commands
            return runPreContainerCommand(command, cwd, agent);
        }

        // In-container mode: run via screen
        return runInContainerViaScreen(command, timeout, agent);
    }

    private static String runPreContainerCommand(String command, String cwd, ExecutionAgent agent) {
        String baseCmd = command.trim().split("\\s+")[0];

        if (!PRE_CONTAINER_CMDS.contains(baseCmd)) {
            return "❌ Pre-container mode: only safe read commands are allowed (" +
                    String.join(", ", new TreeSet<>(PRE_CONTAINER_CMDS)) + ").\n" +
                    "Please write a Dockerfile first (using write_to_file with filename='Dockerfile').";
        }

        // Block blocked interactive commands
        for (String blocked : BLOCKED_INTERACTIVE) {
            if (command.contains(blocked)) {
                return "❌ Interactive commands are not allowed: " + blocked;
            }
        }

        // Execute locally
        try {
            Path localPath = Path.of(agent.workspacePath, agent.projectPath);
            if (!Files.exists(localPath)) {
                localPath = Path.of(agent.workspacePath);
            }

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(localPath.toFile().exists() ? localPath.toFile() : new File(agent.workspacePath));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int rc = p.waitFor();
            return output + "\n[Exit code: " + rc + "]";
        } catch (Exception e) {
            return "❌ Error running command: " + e.getMessage();
        }
    }

    private static String runInContainerViaScreen(String command, int timeout, ExecutionAgent agent) {
        String containerId = agent.containerId;

        // Block dangerous commands
        if (command.equals("exit") || command.startsWith("docker ")) {
            return "❌ Command not allowed in container: " + command;
        }

        // Preprocess: inject -y for apt commands
        command = preprocessCommand(command);

        LOG.info("[linux_terminal] Executing in container: {}", command.substring(0, Math.min(200, command.length())));

        // Use DockerHelpers screen execution
        Object[] result = DockerHelpers.execInScreenAndGetLog(containerId, command);
        int rc = (int) result[0];
        String output = (String) result[1];
        String logfile = (String) result[2];
        boolean stuck = (boolean) result[3];

        agent.currentLogfile = logfile;

        if (stuck) {
            agent.commandStuck = true;
            agent.stuckCommands.add(command);
            return SharedUtils.stripAnsiCodes(output) + "\n\n" +
                    "⚠️ Command appears stuck (no output change for " + SharedUtils.STUCK_TIMEOUT_SECONDS + "s).\n" +
                    "Options: WAIT, TERMINATE, or WRITE:<input>";
        }

        agent.commandStuck = false;

        // Append environment diff
        String envDiff = getEnvDiff(containerId);
        String cleanOutput = SharedUtils.stripAnsiCodes(output);
        String finalOutput = cleanOutput;
        if (!envDiff.isBlank()) {
            finalOutput += "\n\n--- Environment changes ---\n" + envDiff;
        }

        return finalOutput + "\n[Exit code: " + rc + "]";
    }

    private static String handleStuckCommand(String command, ExecutionAgent agent) {
        String containerId = agent.containerId;
        if (containerId == null) {
            agent.commandStuck = false;
            return "Container not available.";
        }

        command = command.trim();

        if (command.equalsIgnoreCase("WAIT")) {
            // Poll for more output
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String output = agent.currentLogfile != null
                    ? SharedUtils.readFileTail(containerId, agent.currentLogfile, SharedUtils.MAX_TAIL_BYTES)
                    : "";
            if (SharedUtils.hasReturnCodeMarker(output)) {
                agent.commandStuck = false;
                Integer rc = SharedUtils.extractReturnCode(output);
                return SharedUtils.stripAnsiCodes(output) + "\n[Exit code: " + rc + "]";
            }
            return SharedUtils.stripAnsiCodes(output) + "\n⚠️ Still running. Options: WAIT, TERMINATE, WRITE:<input>";

        } else if (command.equalsIgnoreCase("TERMINATE")) {
            // Kill the stuck process
            SharedUtils.execInContainerWithOutput(containerId,
                    "screen -S " + SharedUtils.SCREEN_SESSION + " -X stuff $'\\003'", 5);
            agent.commandStuck = false;
            return "✅ Sent interrupt (Ctrl+C) to stuck command.";

        } else if (command.startsWith("WRITE:")) {
            String input = command.substring(6);
            String stuffCmd = String.format("screen -S %s -X stuff $'%s\\n'",
                    SharedUtils.SCREEN_SESSION,
                    input.replace("'", "'\\''"));
            SharedUtils.execInContainerWithOutput(containerId, stuffCmd, 5);
            agent.commandStuck = false;
            return "✅ Sent input to stuck command: " + input;

        } else {
            return "⚠️ Command appears stuck. Use WAIT, TERMINATE, or WRITE:<input>.";
        }
    }

    private static String preprocessCommand(String cmd) {
        // Auto-inject -y for apt-get install
        if (cmd.matches(".*apt-get\\s+install(?!.*-y).*")) {
            cmd = cmd.replace("apt-get install", "apt-get install -y");
        }
        if (cmd.matches(".*apt\\s+install(?!.*-y).*")) {
            cmd = cmd.replace("apt install", "apt install -y");
        }
        // Set DEBIAN_FRONTEND for apt operations
        if ((cmd.contains("apt-get") || cmd.contains("apt ")) && !cmd.contains("DEBIAN_FRONTEND")) {
            cmd = "DEBIAN_FRONTEND=noninteractive " + cmd;
        }
        return cmd;
    }

    private static String getEnvDiff(String containerId) {
        // Simple env check - just return empty for now (full implementation would track changes)
        return "";
    }

    // -------------------------------------------------------------------------
    // read_file
    // -------------------------------------------------------------------------

    public static Object readFile(Map<String, Object> args, Object agentObj) {
        long startTime = System.currentTimeMillis();
        ExecutionAgent agent = (ExecutionAgent) agentObj;
        String filePath = (String) args.getOrDefault("file_path", "");

        try {
            String result = doReadFile(filePath, agent);
            double dur = (System.currentTimeMillis() - startTime) / 1000.0;
            SharedUtils.getMetricsCollector().record("read_file", dur, true, null);
            return result;
        } catch (Exception e) {
            double dur = (System.currentTimeMillis() - startTime) / 1000.0;
            SharedUtils.getMetricsCollector().record("read_file", dur, false, e.getMessage());
            return "❌ Error reading file: " + e.getMessage();
        }
    }

    private static String doReadFile(String filePath, ExecutionAgent agent) {
        String containerId = agent.containerId;

        if (containerId != null && !containerId.isBlank()) {
            // Read from container
            String content = SharedUtils.readFileFromContainer(containerId, filePath);
            if (content == null) return "❌ File not found or unreadable: " + filePath;

            // Convert XML to YAML if needed
            if (filePath.endsWith(".xml")) {
                content = tryConvertXmlToYaml(content);
            }

            // Truncate if too large
            if (content.length() > 50000) {
                content = content.substring(0, 25000)
                        + "\n\n...[truncated: " + content.length() + " total chars]...\n\n"
                        + content.substring(content.length() - 25000);
            }
            return content;
        }

        // Read locally
        try {
            Path p = Path.of(filePath);
            if (!p.isAbsolute()) {
                p = Path.of(agent.workspacePath, agent.projectPath, filePath);
            }
            if (!Files.exists(p)) {
                return "❌ File not found: " + filePath;
            }
            String content = Files.readString(p, StandardCharsets.UTF_8);
            if (content.length() > 50000) {
                content = content.substring(0, 50000) + "\n...[truncated]...";
            }
            return content;
        } catch (Exception e) {
            return "❌ Error reading file: " + e.getMessage();
        }
    }

    private static String tryConvertXmlToYaml(String xml) {
        // Simple best-effort conversion - just return the original if conversion fails
        return xml;
    }

    // -------------------------------------------------------------------------
    // write_to_file
    // -------------------------------------------------------------------------

    public static Object writeToFile(Map<String, Object> args, Object agentObj) {
        long startTime = System.currentTimeMillis();
        ExecutionAgent agent = (ExecutionAgent) agentObj;

        // Accept both filename/text and file_path/content parameter names
        String filename = getStringArg(args, "filename", "file_path", "path");
        String text = getStringArg(args, "text", "content");

        try {
            String result = doWriteToFile(filename, text, agent);
            double dur = (System.currentTimeMillis() - startTime) / 1000.0;
            SharedUtils.getMetricsCollector().record("write_to_file", dur, !result.startsWith("❌"), null);
            return result;
        } catch (GoalsAccomplishedException e) {
            throw e;
        } catch (Exception e) {
            double dur = (System.currentTimeMillis() - startTime) / 1000.0;
            SharedUtils.getMetricsCollector().record("write_to_file", dur, false, e.getMessage());
            return "❌ Error writing file: " + e.getMessage();
        }
    }

    private static String getStringArg(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            Object val = args.get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        return "";
    }

    private static String doWriteToFile(String filename, String text, ExecutionAgent agent) throws Exception {
        if (filename.isBlank()) return "❌ Missing filename argument.";
        if (text == null) text = "";

        boolean isDockerfile = filename.toLowerCase().equals("dockerfile")
                || filename.toLowerCase().endsWith("/dockerfile");

        // Check if COPY instruction is used (blocked)
        if (isDockerfile && text.contains("\nCOPY ")) {
            return "❌ COPY instruction is not allowed in Dockerfile. Use RUN git clone instead.";
        }

        String containerId = agent.containerId;

        if (isDockerfile && containerId != null && !containerId.isBlank()) {
            return "❌ Cannot modify Dockerfile once a container has been created.";
        }

        if (isDockerfile) {
            // Build Docker image and start container
            return buildDockerfileAndStartContainer(filename, text, agent);
        }

        // Write to container if it exists
        if (containerId != null && !containerId.isBlank()) {
            String error = SharedUtils.writeFileToContainer(containerId, filename, text);
            if (error != null) return "❌ " + error;

            // Record the written file
            agent.writtenFiles.add(new String[]{"file", "container", filename, text});
            return "✅ File written to container: " + filename;
        }

        // Write locally (pre-container)
        Path localPath = filename.startsWith("/")
                ? Path.of(filename)
                : Path.of(agent.workspacePath, filename);

        Files.createDirectories(localPath.getParent());
        Files.writeString(localPath, text, StandardCharsets.UTF_8);
        agent.writtenFiles.add(new String[]{"file", "local", localPath.toString(), text});
        return "✅ File written locally: " + localPath;
    }

    private static String buildDockerfileAndStartContainer(String filename, String text, ExecutionAgent agent) throws Exception {
        // Save Dockerfile to workspace
        Path workDir = Path.of(agent.workspacePath, "dockerfile_build_" + System.currentTimeMillis());
        Files.createDirectories(workDir);
        Files.writeString(workDir.resolve("Dockerfile"), text, StandardCharsets.UTF_8);

        // Generate a unique tag
        String tag = "exec-agent-" + agent.projectPath.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase()
                + "-" + Long.toHexString(System.currentTimeMillis());
        agent.dockerTag = tag;

        LOG.info("Building Docker image: tag={}", tag);
        String buildLog;
        try {
            buildLog = DockerHelpers.buildImage(workDir, tag);
        } catch (Exception e) {
            return "❌ Docker build failed: " + e.getMessage();
        }

        LOG.info("Starting container from image: {}", tag);
        DockerHelpers.ContainerInfo info;
        try {
            info = DockerHelpers.startContainer(tag);
        } catch (Exception e) {
            return "❌ Failed to start container: " + e.getMessage();
        }

        agent.containerId = info.id();

        // Record the Dockerfile
        agent.writtenFiles.add(new String[]{"dockerfile", "local", filename, text});

        return "✅ Dockerfile written, Docker image built (" + tag + "), and container started (id=" + info.id() + ").\n"
                + "Build log (last 500 chars):\n"
                + buildLog.substring(Math.max(0, buildLog.length() - 500));
    }

    // -------------------------------------------------------------------------
    // search_docker_image
    // -------------------------------------------------------------------------

    public static Object searchDockerImage(Map<String, Object> args, Object agentObj) {
        long startTime = System.currentTimeMillis();
        String searchTerm = (String) args.getOrDefault("search_term", "");
        try {
            String result = DockerHelpers.searchDockerImage(searchTerm);
            double dur = (System.currentTimeMillis() - startTime) / 1000.0;
            SharedUtils.getMetricsCollector().record("search_docker_image", dur, true, null);
            return result;
        } catch (Exception e) {
            double dur = (System.currentTimeMillis() - startTime) / 1000.0;
            SharedUtils.getMetricsCollector().record("search_docker_image", dur, false, e.getMessage());
            return "❌ Docker image search failed: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // goals_accomplished
    // -------------------------------------------------------------------------

    public static Object goalsAccomplished(Map<String, Object> args, Object agentObj) {
        String reason = (String) args.getOrDefault("reason", "Goals accomplished.");
        LOG.info("Goals accomplished: {}", reason);
        throw new GoalsAccomplishedException(reason);
    }

    // -------------------------------------------------------------------------
    // Register all tools into a ToolRegistry
    // -------------------------------------------------------------------------

    public static ToolRegistry createRegistry() {
        Map<String, List<String>> schema = new LinkedHashMap<>();
        schema.put("linux_terminal", List.of("command"));
        schema.put("read_file", List.of("file_path"));
        schema.put("write_to_file", List.of("filename", "text"));
        schema.put("search_docker_image", List.of("search_term"));
        schema.put("goals_accomplished", List.of("reason"));

        ToolRegistry registry = new ToolRegistry(schema);
        registry.register("linux_terminal", Tools::linuxTerminal);
        registry.register("read_file", Tools::readFile);
        registry.register("write_to_file", Tools::writeToFile);
        registry.register("search_docker_image", Tools::searchDockerImage);
        registry.register("goals_accomplished", Tools::goalsAccomplished);
        return registry;
    }
}
