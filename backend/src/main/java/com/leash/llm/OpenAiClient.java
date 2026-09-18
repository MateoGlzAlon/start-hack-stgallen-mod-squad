package com.leash.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.Json;
import com.leash.Settings;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal OpenAI chat-completions client with strict JSON-schema output. No SDK, just HttpClient. */
@Component
public class OpenAiClient {
    private final Settings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public OpenAiClient(Settings settings) {
        this.settings = settings;
    }

    public boolean configured() {
        return !settings.openaiApiKey.isBlank();
    }

    public String model() {
        return settings.openaiModel;
    }

    /** Sends system+user messages and returns the model's answer parsed as JSON matching {@code schema}. */
    public JsonNode chatJson(String system, String user, String schemaName, JsonNode schema, Duration timeout) throws LlmException {
        if (!configured()) throw new LlmException("OPENAI_API_KEY is not set");
        try {
            ObjectNode body = Json.MAPPER.createObjectNode();
            body.put("model", settings.openaiModel);
            var messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", system);
            messages.addObject().put("role", "user").put("content", user);
            ObjectNode format = body.putObject("response_format");
            format.put("type", "json_schema");
            ObjectNode js = format.putObject("json_schema");
            js.put("name", schemaName);
            js.put("strict", true);
            js.set("schema", schema);

            HttpRequest req = HttpRequest.newBuilder(URI.create(settings.openaiBaseUrl + "/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + settings.openaiApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                String b = res.body();
                throw new LlmException("OpenAI HTTP " + res.statusCode() + ": " + b.substring(0, Math.min(300, b.length())));
            }
            JsonNode message = Json.MAPPER.readTree(res.body()).path("choices").path(0).path("message");
            if (message.hasNonNull("refusal")) throw new LlmException("model refused: " + message.get("refusal").asText());
            String content = message.path("content").asText("");
            if (content.isBlank()) throw new LlmException("empty model answer");
            return Json.MAPPER.readTree(content);
        } catch (IOException e) {
            throw new LlmException("OpenAI call failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e);
        }
    }
}
