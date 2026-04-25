package com.executionagent.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Execution environment wrapper (local subprocess or container).
 * Mirrors Python ExecutionEnvironment.
 */
public class ExecutionEnvironment {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionEnvironment.class);

    public final String workspacePath;
    public final String projectPath;
    private final Function<String, String[]> shellInteractFn;

    /** Container ID once a container is started. */
    public String containerId;

    public ExecutionEnvironment(String workspacePath, String projectPath,
                                 Function<String, String[]> shellInteractFn) {
        this.workspacePath = workspacePath;
        this.projectPath = projectPath;
        this.shellInteractFn = shellInteractFn;
    }

    /**
     * Execute a command. Returns [output, cwd].
     * Uses local shell if no container, otherwise delegates via screen.
     */
    public String[] execute(String command) {
        try {
            return shellInteractFn.apply(command);
        } catch (Exception e) {
            LOG.error("Error executing command: {}", e.getMessage());
            return new String[]{"Error: " + e.getMessage(), workspacePath + "/" + projectPath};
        }
    }

    /** Create local shell interact function. */
    public static Function<String, String[]> localShellInteract(String workspacePath, String projectPath) {
        return cmd -> {
            try {
                File cwd = new File(workspacePath, projectPath);
                if (!cwd.exists()) cwd = new File(workspacePath);

                ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
                pb.directory(cwd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor();
                return new String[]{output, cwd.getAbsolutePath()};
            } catch (Exception e) {
                return new String[]{"Error: " + e.getMessage(), workspacePath};
            }
        };
    }
}
