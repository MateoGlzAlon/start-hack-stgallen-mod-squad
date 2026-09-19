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
            description = "Body: a Viseca authorization event (or the whole poll envelope with run_id + data). "
                    + "No policy_id and no mandate in the event (the normal call): ALL active policies (policies.json; drafts and revoked ones excluded) and the purchase are "
                    + "sent to OpenAI in one call, which answers approved / denied / pending_human and names the policy that allows it (policy_id). The hard rules only veto: "
                    + "the model cannot approve under a policy whose rule the purchase breaks. If the model is unavailable, a clean rule pass still approves, otherwise the "
                    + "uncertainty_policy applies. With policy_id, or with a mandate inside the event (the Viseca worker path), that one policy is enforced: rules first, "
                    + "the model only for what they cannot settle. "
                    + "The amount is billing_amount_chf, or amount x the fixed exchange rate (loaded at startup, see GET /status) when only amount + currency are sent, so a price of EUR 205 "
                    + "fits a CHF 200 limit (CHF 194.75). If both are sent and disagree, the higher one is used and an approval turns into a question. "
                    + "The decision is remembered by authorization_id: sending the same id again returns the saved decision, so change the id to re-run an example.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    examples = {@ExampleObject(name = "any active policy (no mandate in the event)", value = """
{
  "authorization": {
    "authorization_id": "AU_DEMO_2",
    "card_id": "CA0001",
    "timestamp": "2026-08-12T09:00:00Z",
    "billing_amount_chf": 165.0,
    "merchant": {"merchant_id": "ME0999", "merchant_name": "Run Specialists", "merchant_category": "sporting_goods", "merchant_country": "CH"},
    "items": [{"line_no": 1, "item_id": "IT0999", "item_name": "Road running shoes, black, size 43", "item_category": "sporting_goods",
               "quantity": 1, "unit_price": 165.0, "currency": "CHF", "item_details": "Black road-running shoe, size 43, 30-day returns"}]
  }
}"""),
                    @ExampleObject(name = "grocery under the limit (mandate inside the event)", value = """
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
}""")})))
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
