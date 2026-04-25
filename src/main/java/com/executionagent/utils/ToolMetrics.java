package com.executionagent.utils;

import java.util.*;

/** Metrics tracking for tool calls. */
public class ToolMetrics {
    private int totalCalls = 0;
    private int successfulCalls = 0;
    private int failedCalls = 0;
    private double totalDurationSeconds = 0.0;
    private final List<Double> durations = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    public synchronized void recordCall(double duration, boolean success, String error) {
        totalCalls++;
        totalDurationSeconds += duration;
        durations.add(duration);
        if (success) {
            successfulCalls++;
        } else {
            failedCalls++;
            if (error != null) errors.add(error);
        }
    }

    public synchronized double getSuccessRate() {
        if (totalCalls == 0) return 0.0;
        return (successfulCalls * 100.0) / totalCalls;
    }

    public synchronized double getAvgDurationSeconds() {
        if (totalCalls == 0) return 0.0;
        return totalDurationSeconds / totalCalls;
    }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_calls", totalCalls);
        m.put("successful_calls", successfulCalls);
        m.put("failed_calls", failedCalls);
        m.put("total_duration_seconds", totalDurationSeconds);
        m.put("avg_duration_seconds", getAvgDurationSeconds());
        m.put("success_rate_percent", getSuccessRate());
        return m;
    }
}
