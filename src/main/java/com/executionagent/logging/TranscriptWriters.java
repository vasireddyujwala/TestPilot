package com.executionagent.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Writes agent message transcripts to text, JSONL, and JSON files.
 * Mirrors Python TranscriptWriters.
 */
public class TranscriptWriters implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptWriters.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Path textPath;
    private final Path jsonlPath;
    private final Path jsonPath;

    private final BufferedWriter textWriter;
    private final BufferedWriter jsonlWriter;

    public TranscriptWriters(Path textPath, Path jsonlPath, Path jsonPath) throws IOException {
        this.textPath = textPath;
        this.jsonlPath = jsonlPath;
        this.jsonPath = jsonPath;
        this.textWriter = Files.newBufferedWriter(textPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.jsonlWriter = Files.newBufferedWriter(jsonlPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public void writeMessage(int index, Map<String, Object> msg) {
        String ts = LocalDateTime.now().format(TS_FMT);
        String role = String.valueOf(msg.getOrDefault("role", "")).toUpperCase();
        Object tagObj = msg.get("tag");
        String tag = tagObj != null ? String.valueOf(tagObj).strip() : "";
        String content = String.valueOf(msg.getOrDefault("content", ""));

        // Text transcript
        try {
            String header = "[" + ts + "] #" + String.format("%04d", index) + " " + role;
            if (!tag.isEmpty()) header += " (tag=" + tag + ")";
            textWriter.write(header + "\n");
            textWriter.write(content.stripTrailing() + "\n\n");
            textWriter.flush();
        } catch (IOException e) {
            LOG.debug("Error writing text transcript: {}", e.getMessage());
        }

        // JSONL transcript
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ts", ts);
            entry.put("index", index);
            entry.put("role", msg.get("role"));
            entry.put("tag", msg.get("tag"));
            entry.put("content", msg.get("content"));
            jsonlWriter.write(MAPPER.writeValueAsString(entry) + "\n");
            jsonlWriter.flush();
        } catch (IOException e) {
            LOG.debug("Error writing JSONL transcript: {}", e.getMessage());
        }
    }

    public void finalizeFullJson(List<Map<String, Object>> messages) {
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(messages);
            Files.writeString(jsonPath, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.debug("Error writing full JSON: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        try { textWriter.close(); } catch (IOException ignored) {}
        try { jsonlWriter.close(); } catch (IOException ignored) {}
    }
}
