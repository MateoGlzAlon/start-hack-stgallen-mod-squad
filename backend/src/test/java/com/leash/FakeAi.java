package com.leash;

import com.fasterxml.jackson.databind.JsonNode;
import com.leash.llm.LlmException;
import com.leash.llm.OpenAiClient;

import java.time.Duration;

/** A scripted model: returns {@code answer}, or throws {@code failure}. Counts calls. */
public class FakeAi extends OpenAiClient {
    public JsonNode answer;
    public LlmException failure;
    public int calls;
    public String lastUserMessage;

    public FakeAi(Settings settings) {
        super(settings);
    }

    public FakeAi says(String json) {
        try {
            this.answer = Json.MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public JsonNode chatJson(String system, String user, String schemaName, JsonNode schema, Duration timeout) throws LlmException {
        calls++;
        lastUserMessage = user;
        if (failure != null) throw failure;
        return answer;
    }
}
