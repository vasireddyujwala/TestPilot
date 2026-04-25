package com.executionagent.utils;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utilities: Docker primitives, text processing, constants.
 * Mirrors Python shared_utils.py.
 */
public class SharedUtils {

    private static final Logger LOG = LoggerFactory.getLogger(SharedUtils.class);

    // -------------------------------------------------------------------------
    // Configurable constants (overridable via environment variables)
    // -------------------------------------------------------------------------
    public static final String SCREEN_SESSION =
            getEnv("EXECUTION_AGENT_SCREEN_SESSION", "exec_agent_screen");

    public static final String RUN_DIR =
            getEnv("EXECUTION_AGENT_RUN_DIR", "/tmp/screen_runs");

    public static final String LOG_DIR =
            getEnv("EXECUTION_AGENT_LOG_DIR", "/tmp");

    public static final int STUCK_TIMEOUT_SECONDS =
            Integer.parseInt(getEnv("EXECUTION_AGENT_STUCK_TIMEOUT", "300"));

    public static final double POLL_INTERVAL_SECONDS =
            Double.parseDouble(getEnv("EXECUTION_AGENT_POLL_INTERVAL", "0.5"));

    public static final int MAX_TAIL_BYTES =
            Integer.parseInt(getEnv("EXECUTION_AGENT_MAX_TAIL_BYTES", "2000000"));

    public static final int DEFAULT_EXEC_TIMEOUT =
            Integer.parseInt(getEnv("EXECUTION_AGENT_EXEC_TIMEOUT", "60"));

    public static final Pattern ANSI_ESCAPE_RE =
            Pattern.compile("(?:\\[[0-?]*[ -/]*[@-~]|\\][^]*|[@-Z\\\\-_])");

    public static final Pattern RC_MARKER_RE =
            Pattern.compile("<<RC:[^:]+:(-?\\d+)>>");

    // -------------------------------------------------------------------------
    // Docker client (singleton)
    // -------------------------------------------------------------------------
    private static volatile DockerClient _dockerClient;

    public static DockerClient getDockerClient() {
        if (_dockerClient == null) {
            synchronized (SharedUtils.class) {
                if (_dockerClient == null) {
                    DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
                    DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                            .dockerHost(config.getDockerHost())
                            .sslConfig(config.getSSLConfig())
                            .build();
                    _dockerClient = DockerClientImpl.getInstance(config, httpClient);
                }
            }
        }
        return _dockerClient;
    }

    // -------------------------------------------------------------------------
    // Container exec
    // -------------------------------------------------------------------------

    /** Execute a command inside a running container. Returns [exitCode, output]. */
    public static int[] execInContainer(com.github.dockerjava.api.model.Container container,
                                         String cmd, int timeoutSeconds) {
        return execInContainerById(container.getId(), cmd, timeoutSeconds);
    }

    public static int[] execInContainerById(String containerId, String cmd, int timeoutSeconds) {
        try {
            ExecCreateCmdResponse exec = getDockerClient()
                    .execCreateCmd(containerId)
                    .withCmd("/bin/sh", "-lc", cmd)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withTty(false)
                    .exec();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.Frame> callback =
                    new com.github.dockerjava.api.async.ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(com.github.dockerjava.api.model.Frame frame) {
                            if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDOUT) {
                                try { stdout.write(frame.getPayload()); } catch (IOException ignored) {}
                            } else {
                                try { stderr.write(frame.getPayload()); } catch (IOException ignored) {}
                            }
                        }
                    };

            getDockerClient().execStartCmd(exec.getId()).exec(callback);
            callback.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);

            Long exitCode = getDockerClient().inspectExecCmd(exec.getId()).exec().getExitCodeLong();
            String output = stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8);
            return new int[]{exitCode != null ? exitCode.intValue() : -1, 0};
        } catch (Exception e) {
            LOG.error("Error executing command in container: {}", e.getMessage());
            return new int[]{-1, 0};
        }
    }

    /** Execute a command and return [exitCode, outputString] as Object[]. */
    public static Object[] execInContainerWithOutput(String containerId, String cmd, int timeoutSeconds) {
        try {
            ExecCreateCmdResponse exec = getDockerClient()
                    .execCreateCmd(containerId)
                    .withCmd("/bin/sh", "-lc", cmd)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withTty(false)
                    .exec();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.Frame> callback =
                    new com.github.dockerjava.api.async.ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(com.github.dockerjava.api.model.Frame frame) {
                            if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDOUT) {
                                try { stdout.write(frame.getPayload()); } catch (IOException ignored) {}
                            } else {
                                try { stderr.write(frame.getPayload()); } catch (IOException ignored) {}
                            }
                        }
                    };

            getDockerClient().execStartCmd(exec.getId()).exec(callback);
            callback.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);

            Long exitCode = getDockerClient().inspectExecCmd(exec.getId()).exec().getExitCodeLong();
            String output = stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8);
            return new Object[]{exitCode != null ? exitCode.intValue() : -1, output};
        } catch (Exception e) {
            LOG.error("Error executing command in container: {}", e.getMessage());
            return new Object[]{-1, "Error: " + e.getMessage()};
        }
    }

    /** Read the tail of a file from container (up to maxBytes). */
    public static String readFileTail(String containerId, String path, int maxBytes) {
        String cmd = String.format("tail -c %d %s 2>/dev/null || echo ''", maxBytes, shellQuote(path));
        Object[] result = execInContainerWithOutput(containerId, cmd, DEFAULT_EXEC_TIMEOUT);
        return (String) result[1];
    }

    /** Write content to a file inside the container using base64 encoding. */
    public static String writeFileToContainer(String containerId, String filePath, String content) {
        try {
            String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
            String dir = filePath.contains("/") ? filePath.substring(0, filePath.lastIndexOf('/')) : ".";
            String mkdirCmd = String.format("mkdir -p %s", shellQuote(dir));
            execInContainerWithOutput(containerId, mkdirCmd, DEFAULT_EXEC_TIMEOUT);

            String writeCmd = String.format("echo %s | base64 -d > %s", encoded, shellQuote(filePath));
            Object[] result = execInContainerWithOutput(containerId, writeCmd, DEFAULT_EXEC_TIMEOUT);
            int rc = (int) result[0];
            if (rc != 0) {
                return "Error: failed to write file, rc=" + rc;
            }
            return null;
        } catch (Exception e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    /** Read a file from the container. */
    public static String readFileFromContainer(String containerId, String filePath) {
        Object[] result = execInContainerWithOutput(containerId, "cat " + shellQuote(filePath), DEFAULT_EXEC_TIMEOUT);
        return (String) result[1];
    }

    // -------------------------------------------------------------------------
    // Text processing
    // -------------------------------------------------------------------------

    public static String stripAnsiCodes(String text) {
        if (text == null) return "";
        return ANSI_ESCAPE_RE.matcher(text).replaceAll("");
    }

    public static boolean hasReturnCodeMarker(String text) {
        return text != null && RC_MARKER_RE.matcher(text).find();
    }

    public static Integer extractReturnCode(String text) {
        if (text == null) return null;
        Matcher m = RC_MARKER_RE.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /**
     * Smart truncation: keeps head + middle sample + tail of large strings.
     * Mirrors Python _smart_truncate_output.
     */
    public static String smartTruncateOutput(String s, int headChars, int middleChars, int tailChars) {
        if (s == null) s = "";
        int totalKeep = headChars + middleChars + tailChars;
        if (s.length() <= totalKeep + 200) return s;

        int headEnd = headChars;
        int tailStart = s.length() - tailChars;

        int middleRegionStart = headEnd;
        int middleRegionEnd = tailStart;
        int middleRegionSize = middleRegionEnd - middleRegionStart;

        String skipMarker = "\n\n... [output truncated: %d characters omitted] ...\n\n";

        if (middleRegionSize <= middleChars) {
            int skipped = s.length() - headChars - tailChars;
            return s.substring(0, headChars)
                    + String.format(skipMarker, skipped)
                    + s.substring(s.length() - tailChars);
        }

        int middleSampleStart = middleRegionStart + (middleRegionSize - middleChars) / 2;
        int middleSampleEnd = middleSampleStart + middleChars;

        int skippedAfterHead = middleSampleStart - headEnd;
        int skippedAfterMiddle = tailStart - middleSampleEnd;

        StringBuilder sb = new StringBuilder();
        sb.append(s, 0, headEnd);
        if (skippedAfterHead > 0) sb.append(String.format(skipMarker, skippedAfterHead));
        sb.append(s, middleSampleStart, middleSampleEnd);
        if (skippedAfterMiddle > 0) sb.append(String.format(skipMarker, skippedAfterMiddle));
        sb.append(s.substring(tailStart));
        return sb.toString();
    }

    public static String smartTruncateOutput(String s) {
        return smartTruncateOutput(s, 10000, 2000, 10000);
    }

    /** Shell-quote a string for safe use in shell commands. */
    public static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------
    private static String getEnv(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }

    // -------------------------------------------------------------------------
    // Metrics collector (singleton)
    // -------------------------------------------------------------------------
    private static final com.executionagent.utils.MetricsCollector METRICS =
            new com.executionagent.utils.MetricsCollector();

    public static com.executionagent.utils.MetricsCollector getMetricsCollector() {
        return METRICS;
    }
}
