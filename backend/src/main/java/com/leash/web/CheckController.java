package com.leash.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.leash.engine.CheckService;
import com.leash.engine.Decision;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Check")
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
    @Operation(summary = "Decide a purchase",
            description = "Body: a Viseca authorization event (or the whole poll envelope with run_id + data). Runs the hard rules, and only if they cannot "
                    + "settle it asks the LLM judge; falls back to the policy's uncertainty_policy. Without policy_id the event's own mandate snapshot is enforced. "
                    + "The decision is remembered by authorization_id: sending the same id again returns the saved decision, so change AU_DEMO_1 to re-run the example.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(name = "grocery under the limit", value = """
{
  "type": "authorization.request",
  "request_id": "req_demo_1",
  "deadline_at": "2030-01-01T00:00:08Z",
  "authorization": {
    "authorization_id": "AU_DEMO_1",
    "mandate_id": "TM_DEMO",
    "card_id": "CA0001",
    "timestamp": "2026-08-12T09:00:00Z",
    "billing_amount_chf": 18.0,
    "merchant": {"merchant_id": "ME0001", "merchant_name": "Alpine Basket", "merchant_category": "groceries", "merchant_country": "CH"},
    "items": [{"line_no": 1, "item_id": "IT0001", "item_name": "Fresh produce selection", "item_category": "groceries",
               "quantity": 1, "unit_price": 18.0, "currency": "CHF", "item_details": "Seasonal fruit and vegetables"}]
  },
  "mandate": {
    "mandate_id": "TM_DEMO", "status": "active",
    "instruction": "Buy one ordinary grocery item for CHF 20 or less from a shop I use regularly. Ask me when uncertain.",
    "uncertainty_policy": "ask",
    "hard_rules": [
      {"field": "authorization.billing_amount_chf", "operator": "<=", "value": 20, "currency": "CHF", "scope": "purchase"},
      {"field": "items.item_category", "operator": "in", "value": ["groceries"]},
      {"field": "merchant.familiar", "operator": "=", "value": "true"}]
  }
}"""))))
    @PostMapping("/check")
    Decision check(@RequestBody JsonNode body,
                   @Parameter(description = "Enforce this local policy instead of the event's mandate snapshot") @RequestParam(name = "policy_id", required = false) String policyId,
                   @Parameter(description = "Scope for spend windows and duplicate detection (default: the mandate id)") @RequestParam(name = "run_id", required = false) String runId) {
        JsonNode event = body;
        if (!body.has("authorization") && body.has("data")) {           // poll envelope
            event = body.get("data");
            if (runId == null && body.hasNonNull("run_id")) runId = body.get("run_id").asText();
        }
        return checks.check(event, policyId, runId, "api");
    }

    /** The customer's answer to a pending_human decision: {"decision": "approve"|"decline", "customer_message": "..."} */
    @Operation(summary = "Answer a pending purchase (the human path)",
            description = "Only a pending_human decision can be resolved. For purchases that came from Viseca it is sent to Viseca's /resolve first, then recorded here.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {"decision": "approve", "customer_message": "Yes, I want this."}"""))))
    @PostMapping("/check/{authorizationId}/resolve")
    Decision resolve(@PathVariable String authorizationId, @RequestBody JsonNode body) {
        return checks.resolve(authorizationId, body.path("decision").asText(null), body.path("customer_message").asText(null));
    }

    @Operation(summary = "List decisions", description = "Newest first. Use state=pending_human for what is waiting on the customer.")
    @GetMapping("/decisions")
    List<Decision> decisions(@RequestParam(name = "run_id", required = false) String runId,
                             @Parameter(description = "approved | denied | pending_human") @RequestParam(name = "state", required = false) String state) {
        return checks.list(runId, state);
    }

    @Operation(summary = "Get one decision with its evidence and per-rule checks")
    @GetMapping("/decisions/{authorizationId}")
    Decision decision(@PathVariable String authorizationId) {
        return checks.find(authorizationId).orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "No decision for " + authorizationId));
    }
}
