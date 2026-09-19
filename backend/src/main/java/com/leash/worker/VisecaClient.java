package com.leash.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.Json;
import com.leash.Settings;
import com.leash.engine.Decision;
import com.leash.engine.Evidence;
import com.leash.policy.Policy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Thin client for the hosted Viseca sandbox API. The team key never leaves the backend. */
@Component
public class VisecaClient {
    public static class VisecaException extends RuntimeException {
        public final int status;

        public VisecaException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Settings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public VisecaClient(Settings settings) {
        this.settings = settings;
    }

    public boolean configured() {
        return !settings.visecaApiKey.isBlank();
    }

    /** Small catalogues incl. the fixed currency rates (read once at startup). */
    public JsonNode referenceData() {
        return call("GET", "/v1/reference-data", null, Duration.ofSeconds(4)).orElseThrow();
    }

    // ---- mandates ----

    public JsonNode createMandateDraft(Policy p) {
        ObjectNode body = mandateBody(p);
        return call("POST", "/v1/mandates", body, Duration.ofSeconds(15)).orElseThrow();
    }

    public JsonNode confirmMandate(String draftId) {
        ObjectNode body = Json.MAPPER.createObjectNode().put("confirmed", true);
        return call("POST", "/v1/mandates/" + draftId + "/confirm", body, Duration.ofSeconds(15)).orElseThrow();
    }

    public JsonNode patchMandate(String mandateId, Policy p) {
        ObjectNode body = mandateBody(p);
        body.remove("instruction");
        return call("PATCH", "/v1/mandates/" + mandateId, body, Duration.ofSeconds(15)).orElseThrow();
    }

    public void revokeMandate(String mandateId) {
        call("DELETE", "/v1/mandates/" + mandateId, null, Duration.ofSeconds(15));
    }

    private static ObjectNode mandateBody(Policy p) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("instruction", p.instruction);
        body.set("hard_rules", Json.MAPPER.valueToTree(p.hardRules));
        body.put("uncertainty_policy", p.uncertaintyPolicy);
        body.set("guidance", Json.MAPPER.valueToTree(p.guidance));
        body.set("open_questions", Json.MAPPER.valueToTree(p.openQuestions));
        return body;
    }

    // ---- runs ----

    public JsonNode startRun(String scenarioId, String mandateId) {
        ObjectNode body = Json.MAPPER.createObjectNode().put("scenario_id", scenarioId).put("mandate_id", mandateId);
        return call("POST", "/v1/scenario-runs", body, Duration.ofSeconds(15)).orElseThrow();
    }

    public JsonNode getRun(String runId) {
        return call("GET", "/v1/scenario-runs/" + runId, null, Duration.ofSeconds(10)).orElseThrow();
    }

    /** Long-poll for the next purchase. Empty on 204: that is "nothing yet", not "run finished". */
    public Optional<JsonNode> next(int waitSeconds) {
        return call("GET", "/v1/decision-requests/next?wait=" + waitSeconds, null, Duration.ofSeconds(waitSeconds + 10L));
    }

    // ---- decisions ----

    /**
     * Posts our decision. The evidence format is not specified, so we try structured objects first and fall back to
     * plain strings, then to no evidence, but only when the API rejects the body itself (400/422).
     * A 409 means the platform already has a decision for this purchase, which is fine.
     */
    public void postDecision(Decision d) {
        for (int attempt = 0; attempt < 3; attempt++) {
            ObjectNode body = Json.MAPPER.createObjectNode();
            body.put("authorization_id", d.authorizationId());
            body.put("decision", d.visecaDecision());
            body.set("reason_codes", Json.MAPPER.valueToTree(d.reasonCodes()));
            body.put("customer_message", d.customerMessage());
            body.set("evidence", evidence(d.evidence(), attempt));
            body.put("engine_version", d.engineVersion());
            if (attempt == 2) body.remove("evidence");
            try {
                call("POST", "/v1/authorizations/" + d.authorizationId() + "/decision", body, Duration.ofSeconds(5));
                return;
            } catch (VisecaException e) {
                if (e.status == 409) return;
                if ((e.status == 400 || e.status == 422) && attempt < 2) continue;
                throw e;
            }
        }
    }

    public void resolve(String authorizationId, String decision, String customerMessage, List<Evidence> evidence) {
        for (int attempt = 0; attempt < 3; attempt++) {
            ObjectNode body = Json.MAPPER.createObjectNode();
            body.put("decision", decision);
            body.put("customer_message", customerMessage);
            if (attempt < 2) body.set("evidence", evidence(evidence, attempt));
            try {
                call("POST", "/v1/authorizations/" + authorizationId + "/resolve", body, Duration.ofSeconds(8));
                return;
            } catch (VisecaException e) {
                if ((e.status == 400 || e.status == 422) && attempt < 2) continue;
                throw e;
            }
        }
    }

    private static ArrayNode evidence(List<Evidence> list, int attempt) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        for (Evidence e : list) {
            if (attempt == 0) arr.add(Json.MAPPER.valueToTree(e));
            else arr.add(e.toString());
        }
        return arr;
    }

    // ---- plumbing ----

    private Optional<JsonNode> call(String method, String path, JsonNode body, Duration timeout) {
        if (!configured()) throw new VisecaException(503, "TEAM_API_KEY is not set");
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(settings.visecaBaseUrl + path))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + settings.visecaApiKey)
                    .header("Content-Type", "application/json");
            HttpRequest.BodyPublisher pub = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body));
            HttpResponse<String> res = http.send(b.method(method, pub).build(), HttpResponse.BodyHandlers.ofString());
            int s = res.statusCode();
            if (s == 204) return Optional.empty();
            if (s < 200 || s >= 300) throw new VisecaException(s, "Viseca " + method + " " + path + " -> " + s + ": " + res.body());
            String text = res.body();
            return Optional.of(text == null || text.isBlank() ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(text));
        } catch (IOException e) {
            throw new VisecaException(502, "Viseca unreachable: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VisecaException(502, "interrupted");
        }
    }
}
