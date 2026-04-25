package com.executionagent.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * LLM model implementation using OpenAI-compatible API (same as Python LiteLLM).
 * Uses OkHttp for HTTP calls with threading-based timeout backup.
 */
public class LitellmModel implements LlmModel {

    private static final Logger LOG = LoggerFactory.getLogger(LitellmModel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final String modelName;
    private final String apiKey;
    private final String baseUrl;
    private final int timeoutSeconds;
    private final OkHttpClient httpClient;

    public LitellmModel(String modelName, String apiKey) {
        this(modelName, apiKey, "https://api.openai.com/v1", 300);
    }

    public LitellmModel(String modelName, String apiKey, String baseUrl, int timeoutSeconds) {
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds + 30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public Map<String, Object> query(List<Map<String, String>> messages, double temperature) {
        int maxRetries = 5;
        int retryDelay = 5;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                LOG.info("[LLM] Calling model={} messages={} attempt={}/{}", modelName, messages.size(), attempt, maxRetries);
                String content = callApi(messages, temperature);
                LOG.info("[LLM] Response received, length={} chars", content.length());

                Map<String, Object> result = new HashMap<>();
                result.put("content", content);
                return result;

            } catch (LLMTimeoutException e) {
                LOG.error("[LLM] Timeout on attempt {}/{}: {}", attempt, maxRetries, e.getMessage());
                if (attempt >= maxRetries) throw e;
                sleep(retryDelay);
                retryDelay = Math.min(retryDelay * 2, 60);

            } catch (IOException e) {
                LOG.error("[LLM] IO error on attempt {}/{}: {}", attempt, maxRetries, e.getMessage());
                if (attempt >= maxRetries) throw new RuntimeException("LLM call failed after " + maxRetries + " attempts", e);
                sleep(retryDelay);
                retryDelay = Math.min(retryDelay * 2, 60);
            }
        }

        throw new RuntimeException("LLM call failed after " + maxRetries + " attempts");
    }

    private String callApi(List<Map<String, String>> messages, double temperature) throws IOException {
        ObjectNode requestBody = MAPPER.createObjectNode();
        requestBody.put("model", modelName);
        requestBody.put("temperature", temperature);

        ArrayNode msgsArray = requestBody.putArray("messages");
        for (Map<String, String> msg : messages) {
            ObjectNode msgNode = msgsArray.addObject();
            msgNode.put("role", msg.getOrDefault("role", "user"));
            msgNode.put("content", msg.getOrDefault("content", ""));
        }

        String bodyStr = MAPPER.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(bodyStr, JSON_TYPE);

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "llm-call-thread");
            t.setDaemon(true);
            return t;
        });

        Future<String> future = executor.submit(() -> {
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "(empty)";
                    throw new IOException("HTTP " + response.code() + ": " + errorBody);
                }
                assert response.body() != null;
                String responseStr = response.body().string();
                JsonNode json = MAPPER.readTree(responseStr);

                JsonNode choices = json.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) {
                    throw new IOException("No choices in LLM response: " + responseStr);
                }

                JsonNode message = choices.get(0).get("message");
                if (message == null) {
                    throw new IOException("No message in first choice: " + responseStr);
                }

                JsonNode contentNode = message.get("content");
                return contentNode != null ? contentNode.asText("") : "";
            }
        });

        try {
            return future.get(timeoutSeconds + 30L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LLMTimeoutException("LLM call timed out after " + timeoutSeconds + "s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioEx) throw ioEx;
            throw new IOException("LLM call failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM call interrupted");
        } finally {
            executor.shutdownNow();
        }
    }

    private void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String getModelName() {
        return modelName;
    }

    public static class LLMTimeoutException extends RuntimeException {
        public LLMTimeoutException(String message) {
            super(message);
        }
    }
}
