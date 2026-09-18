package com.leash.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.policy.Policy;
import com.leash.policy.PolicyService;
import com.leash.policy.PolicyStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Policies")
@RestController
@RequestMapping("/policies")
public class PolicyController {
    private final PolicyService service;
    private final PolicyStore store;

    public PolicyController(PolicyService service, PolicyStore store) {
        this.service = service;
        this.store = store;
    }

    /** {"instruction": "..."} -> a draft the customer must review and confirm. */
    @Operation(summary = "Create a draft policy from a sentence",
            description = "Sends the instruction to OpenAI and stores the result as a draft: hard_rules (enforced), guidance (judged), "
                    + "uncertainty_policy and open_questions. Nothing is enforced until the customer confirms. 503 if the model is unavailable.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {"instruction": "Buy me black running shoes for up to CHF 200. Ask me when uncertain."}"""))))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Policy create(@RequestBody JsonNode body) {
        return service.createDraft(body.path("instruction").asText(null));
    }

    @Operation(summary = "List all policies")
    @GetMapping
    List<Policy> list() {
        return store.all();
    }

    @Operation(summary = "Get one policy")
    @GetMapping("/{id}")
    Policy get(@PathVariable String id) {
        return service.require(id);
    }

    /** Optional body {"confirmed": true}; an explicit false is rejected. */
    @Operation(summary = "Confirm a draft (draft -> active)",
            description = "The customer agrees to exactly what the draft shows. Only a draft can be confirmed. The body is optional; an explicit false is rejected.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = "{\"confirmed\": true}"))))
    @PostMapping("/{id}/confirm")
    Policy confirm(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        if (body != null && body.has("confirmed") && !body.get("confirmed").asBoolean()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "confirmed must be true to activate the policy");
        }
        return service.confirm(id);
    }

    @Operation(summary = "Tighten an active policy",
            description = "Tighten only: hard_rules must contain every existing rule unchanged plus any new ones; uncertainty_policy may only move "
                    + "to decline; guidance and open_questions are replaced. Applies to later runs (a running run keeps its snapshot). "
                    + "The response includes viseca_sync.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {"hard_rules": [
                               {"field": "authorization.billing_amount_chf", "operator": "<=", "value": 200, "currency": "CHF", "scope": "purchase"},
                               {"field": "merchant.merchant_category", "operator": "in", "value": ["sporting_goods"]}],
                             "uncertainty_policy": "decline"}"""))))
    @PatchMapping("/{id}")
    ObjectNode patch(@PathVariable String id, @RequestBody JsonNode body) {
        return service.patch(id, body);
    }

    @Operation(summary = "Revoke a policy",
            description = "Immediate and final. Purchases checked afterwards against this policy are declined. Viseca is told afterwards (viseca_sync in the response).")
    @DeleteMapping("/{id}")
    ObjectNode revoke(@PathVariable String id) {
        return service.revoke(id);
    }
}
