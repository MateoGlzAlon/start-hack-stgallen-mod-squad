package com.leash.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.leash.engine.CheckService;
import com.leash.engine.Decision;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class CheckController {
    private final CheckService checks;

    public CheckController(CheckService checks) {
        this.checks = checks;
    }

    /**
     * Body: a Viseca authorization event (or the whole poll envelope with run_id + data).
     * Optional ?policy_id= enforces one of our local policies instead of the event's own mandate snapshot;
     * optional ?run_id= scopes spend windows and duplicate detection.
     */
    @PostMapping("/check")
    Decision check(@RequestBody JsonNode body,
                   @RequestParam(name = "policy_id", required = false) String policyId,
                   @RequestParam(name = "run_id", required = false) String runId) {
        JsonNode event = body;
        if (!body.has("authorization") && body.has("data")) {           // poll envelope
            event = body.get("data");
            if (runId == null && body.hasNonNull("run_id")) runId = body.get("run_id").asText();
        }
        return checks.check(event, policyId, runId, "api");
    }

    /** The customer's answer to a pending_human decision: {"decision": "approve"|"decline", "customer_message": "..."} */
    @PostMapping("/check/{authorizationId}/resolve")
    Decision resolve(@PathVariable String authorizationId, @RequestBody JsonNode body) {
        return checks.resolve(authorizationId, body.path("decision").asText(null), body.path("customer_message").asText(null));
    }

    @GetMapping("/decisions")
    List<Decision> decisions(@RequestParam(name = "run_id", required = false) String runId,
                             @RequestParam(name = "state", required = false) String state) {
        return checks.list(runId, state);
    }

    @GetMapping("/decisions/{authorizationId}")
    Decision decision(@PathVariable String authorizationId) {
        return checks.find(authorizationId).orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "No decision for " + authorizationId));
    }
}
