package com.leash.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.policy.Policy;
import com.leash.policy.PolicyClarifier;
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

    /** {"instruction": "..."} -> compiled by OpenAI and active immediately. */
    @Operation(summary = "Create a policy from a sentence (active immediately)",
            description = "Sends the instruction to OpenAI and stores the result as an ACTIVE policy: hard_rules (enforced exactly), guidance (judged by the model), "
                    + "uncertainty_policy and open_questions. There is no draft or confirm step. Show the customer what was understood and offer tighten or revoke. "
                    + "503 if the model is unavailable.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {"instruction": "Buy me black running shoes for up to CHF 200. Ask me when uncertain."}"""))))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Policy create(@RequestBody JsonNode body) {
        return service.create(body.path("instruction").asText(null));
    }

    @Operation(summary = "Is the sentence specific enough? If not, what to ask the customer first",
            description = "Call this before POST /policies when the customer typed the sentence. Returns ready=true for a sentence that says what may be bought and how much "
                    + "may be spent, otherwise up to 3 plain-language questions (each with a short 'why' and optional quick-answer 'choices'). Nothing is stored. "
                    + "Add the customer's answers to the sentence and send the result to POST /policies. 503 if the model is unavailable.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {"instruction": "Buy me some shoes."}"""))))
    @PostMapping("/clarify")
    PolicyClarifier.Result clarify(@RequestBody JsonNode body) {
        return service.clarify(body.path("instruction").asText(null));
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
