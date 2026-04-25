package com.executionagent.utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Singleton metrics collector for all tools. Mirrors Python MetricsCollector. */
public class MetricsCollector {
    private final Map<String, ToolMetrics> metrics = new ConcurrentHashMap<>();

    public ToolMetrics getOrCreate(String toolName) {
        return metrics.computeIfAbsent(toolName, k -> new ToolMetrics());
    }

    public void record(String toolName, double duration, boolean success, String error) {
        getOrCreate(toolName).recordCall(duration, success, error);
    }

    public Map<String, Map<String, Object>> getAllMetrics() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        metrics.forEach((k, v) -> result.put(k, v.toMap()));
        return result;
    }
}
