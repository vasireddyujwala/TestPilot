package com.executionagent.utils;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.api.async.ResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Docker lifecycle helpers: build, start, exec via screen, cleanup.
 * Mirrors Python docker_helpers_static.py.
 */
public class DockerHelpers {

    private static final Logger LOG = LoggerFactory.getLogger(DockerHelpers.class);

    public record ContainerInfo(String id, String tag) {}

    // -------------------------------------------------------------------------
    // Image build
    // -------------------------------------------------------------------------

    /**
     * Build a Docker image from a directory containing a Dockerfile.
     * Returns the build log string.
     */
    public static String buildImage(Path dockerfileDir, String tag) throws Exception {
        LOG.info("Building Docker image tag={} from dir={}", tag, dockerfileDir);
        StringBuilder log = new StringBuilder();

        DockerClient client = SharedUtils.getDockerClient();
        BuildImageResultCallback callback = new BuildImageResultCallback() {
            @Override
            public void onNext(BuildResponseItem item) {
                String stream = item.getStream();
                if (stream != null) {
                    log.append(stream);
                    if (stream.trim().length() > 0) {
                        LOG.debug("[DOCKER BUILD] {}", stream.trim());
                    }
                }
            }
        };

        String imageId = client.buildImageCmd(dockerfileDir.toFile())
                .withTags(Collections.singleton(tag))
                .exec(callback)
                .awaitImageId();

        LOG.info("Docker image built successfully: imageId={}", imageId);
        return log.toString();
    }

    // -------------------------------------------------------------------------
    // Container start
    // -------------------------------------------------------------------------

    /**
     * Start a container from a given image tag.
     * Returns ContainerInfo (id, tag).
     */
    public static ContainerInfo startContainer(String imageTag) throws Exception {
        LOG.info("Starting container from image={}", imageTag);
        DockerClient client = SharedUtils.getDockerClient();

        CreateContainerResponse container = client.createContainerCmd(imageTag)
                .withTty(true)
                .withStdinOpen(true)
                .exec();

        String containerId = container.getId();
        client.startContainerCmd(containerId).exec();
        LOG.info("Container started: id={}", containerId);

        // Set up GNU screen session inside the container
        String screenLog = createScreenSession(containerId);
        LOG.info("Screen session created: {}", screenLog);

        return new ContainerInfo(containerId, imageTag);
    }

    // -------------------------------------------------------------------------
    // Screen session
    // -------------------------------------------------------------------------

    /**
     * Install bash/screen/psmisc and start a detached GNU screen session.
     * Returns a status message.
     */
    public static String createScreenSession(String containerId) {
        String screenSession = SharedUtils.SCREEN_SESSION;
        String runDir = SharedUtils.RUN_DIR;

        // Detect package manager and install dependencies
        String pkgMgr = detectPackageManager(containerId);
        if (pkgMgr != null) {
            String installCmd = buildInstallCmd(pkgMgr, List.of("bash", "screen", "psmisc", "procps"));
            Object[] result = SharedUtils.execInContainerWithOutput(containerId, installCmd, 120);
            LOG.debug("Package install result (rc={})", result[0]);
        }

        // Create run directory inside container
        Object[] mkdirResult = SharedUtils.execInContainerWithOutput(containerId,
                "mkdir -p " + runDir, SharedUtils.DEFAULT_EXEC_TIMEOUT);

        // Start screen session
        String screenCmd = String.format(
                "screen -dmS %s bash -l 2>/dev/null; sleep 0.5; screen -list", screenSession);
        Object[] screenResult = SharedUtils.execInContainerWithOutput(containerId, screenCmd, 30);
        String output = (String) screenResult[1];
        LOG.info("Screen session output: {}", output.substring(0, Math.min(200, output.length())));
        return "Screen session created: " + output.substring(0, Math.min(100, output.length()));
    }

    /**
     * Execute a command inside the container via a persistent GNU screen session.
     * Returns [exitCode, output, logfile, stuckFlag].
     */
    public static Object[] execInScreenAndGetLog(String containerId, String cmd) {
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String logFile = SharedUtils.RUN_DIR + "/run_" + runId + ".log";
        String screenSession = SharedUtils.SCREEN_SESSION;

        // Build the full command with RC marker
        String markerCmd = String.format(
                "{ %s; echo '<<RC:%s:'$?'>>'; } 2>&1 | tee %s",
                cmd, runId, logFile);
        String stuffCmd = String.format(
                "screen -S %s -X stuff $'%s\\n'",
                screenSession,
                markerCmd.replace("'", "'\\''").replace("\\", "\\\\").replace("\n", "\\n"));

        // Stuff the command into screen
        Object[] stuffResult = SharedUtils.execInContainerWithOutput(containerId, stuffCmd, 10);
        if ((int) stuffResult[0] != 0) {
            // Screen may have died; try to restart and retry once
            createScreenSession(containerId);
            stuffResult = SharedUtils.execInContainerWithOutput(containerId, stuffCmd, 10);
        }

        // Poll until RC marker appears or timeout
        long deadline = System.currentTimeMillis() + (long) SharedUtils.STUCK_TIMEOUT_SECONDS * 1000;
        String lastOutput = "";
        long lastChangeTime = System.currentTimeMillis();
        boolean stuck = false;

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep((long) (SharedUtils.POLL_INTERVAL_SECONDS * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            String currentOutput = SharedUtils.readFileTail(containerId, logFile, SharedUtils.MAX_TAIL_BYTES);
            currentOutput = SharedUtils.stripAnsiCodes(currentOutput);

            if (SharedUtils.hasReturnCodeMarker(currentOutput)) {
                Integer rc = SharedUtils.extractReturnCode(currentOutput);
                return new Object[]{rc != null ? rc : 0, currentOutput, logFile, false};
            }

            if (!currentOutput.equals(lastOutput)) {
                lastOutput = currentOutput;
                lastChangeTime = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastChangeTime > (long) SharedUtils.STUCK_TIMEOUT_SECONDS * 1000) {
                stuck = true;
                break;
            }
        }

        return new Object[]{-1, lastOutput, logFile, stuck};
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    public static void cleanupContainer(String containerId, String dockerTag) {
        if (containerId == null && dockerTag == null) return;
        DockerClient client = SharedUtils.getDockerClient();

        if (containerId != null && !containerId.isBlank()) {
            try {
                client.stopContainerCmd(containerId).withTimeout(10).exec();
                LOG.info("Container stopped: {}", containerId);
            } catch (Exception e) {
                LOG.debug("Stop container (ignored): {}", e.getMessage());
            }
            try {
                client.removeContainerCmd(containerId).withForce(true).exec();
                LOG.info("Container removed: {}", containerId);
            } catch (Exception e) {
                LOG.debug("Remove container (ignored): {}", e.getMessage());
            }
        }

        if (dockerTag != null && !dockerTag.isBlank()) {
            try {
                client.removeImageCmd(dockerTag).withForce(true).exec();
                LOG.info("Image removed: {}", dockerTag);
            } catch (Exception e) {
                LOG.debug("Remove image (ignored): {}", e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Package manager detection
    // -------------------------------------------------------------------------

    private static String detectPackageManager(String containerId) {
        for (String pm : List.of("apt-get", "apk", "dnf", "yum", "microdnf")) {
            Object[] result = SharedUtils.execInContainerWithOutput(containerId,
                    "command -v " + pm + " >/dev/null 2>&1 && echo found", 5);
            String out = (String) result[1];
            if (out.contains("found")) return pm;
        }
        return null;
    }

    private static String buildInstallCmd(String pkgMgr, List<String> packages) {
        String pkgList = String.join(" ", packages);
        return switch (pkgMgr) {
            case "apt-get" -> "DEBIAN_FRONTEND=noninteractive apt-get install -y " + pkgList + " 2>/dev/null || true";
            case "apk" -> "apk add --no-cache " + pkgList + " 2>/dev/null || true";
            case "dnf", "yum" -> pkgMgr + " install -y " + pkgList + " 2>/dev/null || true";
            case "microdnf" -> "microdnf install -y " + pkgList + " 2>/dev/null || true";
            default -> "true";
        };
    }

    // -------------------------------------------------------------------------
    // Docker search
    // -------------------------------------------------------------------------

    public static String searchDockerImage(String searchTerm) {
        try {
            List<SearchItem> items = SharedUtils.getDockerClient()
                    .searchImagesCmd(searchTerm)
                    .withLimit(10)
                    .exec();

            StringBuilder sb = new StringBuilder("Docker Hub search results for: " + searchTerm + "\n\n");
            for (SearchItem item : items) {
                sb.append(String.format("Name: %s\nDescription: %s\nStars: %d\nOfficial: %s\n\n",
                        item.getName(),
                        item.getDescription() != null ? item.getDescription() : "",
                        item.getStarCount() != null ? item.getStarCount() : 0,
                        Boolean.TRUE.equals(item.isOfficial()) ? "yes" : "no"));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error searching Docker images: " + e.getMessage();
        }
    }
}
