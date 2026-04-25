package com.executionagent.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serializable snapshot of ExecutionAgent state for crash recovery.
 * Mirrors Python AgentState dataclass.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentState {
    public List<Object> commandsAndSummary = new ArrayList<>();
    public List<String[]> writtenFiles = new ArrayList<>();

    public String dockerTag;
    public String containerId;

    public int cycleCount;
    public int stepLimit;

    public String projectPath;
    public String projectUrl;
    public String workspacePath;

    public boolean commandStuck;
    public boolean analysisSucceeded;

    public List<String> stuckCommands = new ArrayList<>();
    public List<Map<String, Object>> previousAttempts = new ArrayList<>();

    public String createdAt = Instant.now().toString();
    public String updatedAt = Instant.now().toString();
}
