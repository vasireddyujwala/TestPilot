package com.executionagent.artifacts;

import com.executionagent.agent.ExecutionAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Generates success artifacts for a completed run.
 * Mirrors Python exit_artifacts.py.
 */
public class ExitArtifacts {

    private static final Logger LOG = LoggerFactory.getLogger(ExitArtifacts.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Generate success artifacts: Dockerfile, commands.sh, launch.sh, manifest.json.
     * Returns true if artifacts were successfully generated.
     */
    public static boolean generateExitArtifacts(ExecutionAgent agent, Path runDir, Logger log) {
        // Find the Dockerfile
        String dockerfileContent = null;
        for (String[] wf : agent.writtenFiles) {
            if (wf.length >= 4 && "dockerfile".equalsIgnoreCase(wf[0])) {
                dockerfileContent = wf[3];
            }
        }

        if (dockerfileContent == null || dockerfileContent.isBlank()) {
            log.warn("No Dockerfile found in written files — cannot generate exit artifacts");
            return false;
        }

        Path artifactsDir = runDir.resolve("success_artifacts");
        try {
            Files.createDirectories(artifactsDir);
        } catch (IOException e) {
            log.error("Failed to create artifacts dir: {}", e.getMessage());
            return false;
        }

        try {
            // Write Dockerfile
            Files.writeString(artifactsDir.resolve("Dockerfile"), dockerfileContent, StandardCharsets.UTF_8);

            // Collect all in-container linux_terminal commands
            List<String> commands = new ArrayList<>();
            for (Object[] entry : agent.commandsAndSummary) {
                String callStr = (String) entry[0];
                if (callStr.startsWith("Call to tool linux_terminal with arguments")) {
                    try {
                        String argsJson = callStr.substring("Call to tool linux_terminal with arguments ".length());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> args = MAPPER.readValue(argsJson, Map.class);
                        String cmd = (String) args.get("command");
                        if (cmd != null) commands.add(cmd);
                    } catch (Exception ignored) {}
                }
            }

            // Write commands.sh
            StringBuilder cmdsScript = new StringBuilder("#!/bin/bash\nset -e\n\n");
            for (String cmd : commands) {
                cmdsScript.append(cmd).append("\n");
            }
            Files.writeString(artifactsDir.resolve("commands.sh"), cmdsScript.toString(), StandardCharsets.UTF_8);

            // Write launch.sh — self-contained build + run script
            String imageTag = agent.dockerTag != null && !agent.dockerTag.isBlank()
                    ? agent.dockerTag : "exec-agent-" + agent.projectPath.replace("/", "-");
            StringBuilder launchScript = new StringBuilder();
            launchScript.append("#!/bin/bash\nset -e\n\n");
            launchScript.append("SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\n");
            launchScript.append("IMAGE_TAG=\"").append(imageTag).append("\"\n\n");
            launchScript.append("echo 'Building Docker image...'\n");
            launchScript.append("docker build -t \"$IMAGE_TAG\" -f \"$SCRIPT_DIR/Dockerfile\" \"$SCRIPT_DIR\"\n\n");
            launchScript.append("echo 'Starting container...'\n");
            launchScript.append("CONTAINER_ID=$(docker run -d \"$IMAGE_TAG\" tail -f /dev/null)\n");
            launchScript.append("trap 'docker rm -f \"$CONTAINER_ID\" >/dev/null 2>&1' EXIT\n\n");
            launchScript.append("echo 'Running commands inside container...'\n");
            for (String cmd : commands) {
                launchScript.append("docker exec \"$CONTAINER_ID\" bash -lc ").append(shellQuote(cmd)).append("\n");
            }
            launchScript.append("\necho 'Done!'\n");
            Files.writeString(artifactsDir.resolve("launch.sh"), launchScript.toString(), StandardCharsets.UTF_8);
            new File(artifactsDir.resolve("launch.sh").toString()).setExecutable(true);

            // Write manifest.json
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("project_path", agent.projectPath);
            manifest.put("project_url", agent.projectUrl);
            manifest.put("docker_tag", imageTag);
            manifest.put("container_id", agent.containerId);
            manifest.put("cycle_count", agent.cycleCount);
            manifest.put("commands_count", commands.size());
            manifest.put("generated_at", Instant.now().toString());
            Files.writeString(artifactsDir.resolve("manifest.json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                    StandardCharsets.UTF_8);

            log.info("Exit artifacts generated in: {}", artifactsDir);
            return true;
        } catch (Exception e) {
            log.error("Failed to generate exit artifacts: {}", e.getMessage(), e);
            return false;
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
