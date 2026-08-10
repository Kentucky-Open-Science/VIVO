/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.askvivo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A minimal client for an OpenAI-compatible chat-completions API.
 *
 * Configuration comes from runtime.properties (llm.baseUrl, llm.apiKey, llm.model). The API key may
 * legitimately be empty for keyless local gateways, in which case no Authorization header is sent.
 */
public class LlmClient {

    private static final int TIMEOUT_MILLIS = 60000;
    private static final int EXCERPT_LENGTH = 500;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    /** One chat message: a role ("system", "user" or "assistant") and its text content. */
    public static class Message {
        private final String role;
        private final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }

    public LlmClient(String baseUrl, String apiKey, String model) {
        String trimmed = (baseUrl == null) ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed;
        this.apiKey = (apiKey == null) ? "" : apiKey.trim();
        this.model = (model == null) ? "" : model.trim();
    }

    /**
     * POST the messages to {baseUrl}/chat/completions and return choices[0].message.content.
     *
     * @throws IOException on connection failure, non-2xx status (with a response-body excerpt in the
     *         message), or an unparseable response.
     */
    public String chat(List<Message> messages, int maxTokens) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode messageArray = body.putArray("messages");
        for (Message message : messages) {
            ObjectNode node = messageArray.addObject();
            node.put("role", message.getRole());
            node.put("content", message.getContent());
        }
        body.put("temperature", 0.1);
        body.put("max_tokens", maxTokens);
        byte[] payload = mapper.writeValueAsBytes(body);

        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (!apiKey.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
            } finally {
                out.close();
            }

            int status = connection.getResponseCode();
            InputStream in = (status >= 200 && status < 300)
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String responseBody = readAll(in);
            if (status < 200 || status >= 300) {
                throw new IOException("LLM request failed with HTTP " + status + ": " + excerpt(responseBody));
            }

            JsonNode content = mapper.readTree(responseBody)
                    .path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new IOException("Unexpected LLM response format: " + excerpt(responseBody));
            }
            return content.asText();
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        if (flattened.length() > EXCERPT_LENGTH) {
            return flattened.substring(0, EXCERPT_LENGTH) + "...";
        }
        return flattened;
    }
}
