package com.executionagent.models;

import java.util.List;
import java.util.Map;

/** Interface for LLM model implementations. */
public interface LlmModel {
    /**
     * Query the LLM with a list of messages.
     *
     * @param messages List of {role, content} maps
     * @param temperature Sampling temperature (0.0 - 2.0)
     * @return Map containing at minimum {"content": String}
     */
    Map<String, Object> query(List<Map<String, String>> messages, double temperature);

    default Map<String, Object> query(List<Map<String, String>> messages) {
        return query(messages, 1.0);
    }
}
