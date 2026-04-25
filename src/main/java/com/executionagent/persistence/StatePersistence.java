package com.executionagent.persistence;

import com.executionagent.agent.ExecutionAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.time.Instant;

/**
 * Saves and loads ExecutionAgent state for crash recovery.
 * Mirrors Python StatePersistence.
 */
public class StatePersistence {

    private static final Logger LOG = LoggerFactory.getLogger(StatePersistence.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path stateFile;
    private final Path backupFile;

    public StatePersistence(Path runDir) {
        this.stateFile = runDir.resolve("agent_state.json");
        this.backupFile = runDir.resolve("agent_state.json.bak");
    }

    public boolean saveState(ExecutionAgent agent) {
        try {
            AgentState state = new AgentState();
            state.commandsAndSummary = new java.util.ArrayList<>(agent.commandsAndSummary);
            state.writtenFiles = new java.util.ArrayList<>(agent.writtenFiles);
            state.dockerTag = agent.dockerTag;
            state.containerId = agent.containerId;
            state.cycleCount = agent.cycleCount;
            state.stepLimit = agent.stepLimit;
            state.projectPath = agent.projectPath;
            state.projectUrl = agent.projectUrl;
            state.workspacePath = agent.workspacePath;
            state.commandStuck = agent.commandStuck;
            state.stuckCommands = new java.util.ArrayList<>(agent.stuckCommands);
            state.previousAttempts = new java.util.ArrayList<>(agent.previousAttempts);
            state.updatedAt = Instant.now().toString();

            // Backup existing state
            if (Files.exists(stateFile)) {
                Files.copy(stateFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            }

            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(state);
            Files.writeString(stateFile, json);
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to save agent state: {}", e.getMessage());
            return false;
        }
    }

    public AgentState loadState() {
        try {
            if (!Files.exists(stateFile)) return null;
            String json = Files.readString(stateFile);
            return MAPPER.readValue(json, AgentState.class);
        } catch (Exception e) {
            LOG.warn("Failed to load agent state: {}", e.getMessage());
            return null;
        }
    }

    public boolean hasSavedState() {
        return Files.exists(stateFile);
    }

    public void clearState() {
        try {
            Files.deleteIfExists(stateFile);
            Files.deleteIfExists(backupFile);
        } catch (Exception e) {
            LOG.warn("Failed to clear state: {}", e.getMessage());
        }
    }

    public static StatePersistence create(Path runDir) {
        try {
            Files.createDirectories(runDir);
        } catch (Exception e) {
            LOG.warn("Could not create run dir: {}", e.getMessage());
        }
        return new StatePersistence(runDir);
    }
}
