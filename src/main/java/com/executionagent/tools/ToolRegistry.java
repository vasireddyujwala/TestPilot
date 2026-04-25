package com.executionagent.tools;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Simple tool registry for ExecutionAgent.
 * Mirrors Python ToolRegistry.
 */
public class ToolRegistry {

    private final Map<String, List<String>> commandsSchema;
    private final Map<String, ToolFunction> tools = new LinkedHashMap<>();

    @FunctionalInterface
    public interface ToolFunction {
        Object call(Map<String, Object> args, Object agent);
    }

    public ToolRegistry(Map<String, List<String>> commandsSchema) {
        this.commandsSchema = commandsSchema != null ? commandsSchema : new HashMap<>();
    }

    public void register(String name, ToolFunction fn) {
        tools.put(name, fn);
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public ToolFunction get(String name) {
        return tools.get(name);
    }

    /**
     * Validate required arguments and return normalized copy.
     * Throws IllegalArgumentException if tool is unknown or required args are missing.
     */
    public Map<String, Object> normalizeAndValidate(String name, Map<String, Object> args) {
        if (!tools.containsKey(name)) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }

        List<String> required = commandsSchema.getOrDefault(name, Collections.emptyList());
        List<String> missing = new ArrayList<>();
        for (String r : required) {
            if (!args.containsKey(r)) missing.add(r);
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Tool '" + name + "' missing required arguments: " + missing);
        }

        return new HashMap<>(args);
    }

    /**
     * Call a registered tool by name.
     * Throws IllegalArgumentException if the tool is not registered.
     */
    public Object call(String name, Map<String, Object> args, Object agent) {
        if (!tools.containsKey(name)) {
            throw new IllegalArgumentException("Tool '" + name + "' is not registered");
        }
        return tools.get(name).call(args, agent);
    }

    public Map<String, ToolFunction> getTools() {
        return Collections.unmodifiableMap(tools);
    }
}
