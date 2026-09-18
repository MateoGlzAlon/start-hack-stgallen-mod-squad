package com.leash.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.policy.Policy;
import com.leash.policy.PolicyService;
import com.leash.policy.PolicyStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Policy create(@RequestBody JsonNode body) {
        return service.createDraft(body.path("instruction").asText(null));
    }

    @GetMapping
    List<Policy> list() {
        return store.all();
    }

    @GetMapping("/{id}")
    Policy get(@PathVariable String id) {
        return service.require(id);
    }

    /** Optional body {"confirmed": true}; an explicit false is rejected. */
    @PostMapping("/{id}/confirm")
    Policy confirm(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        if (body != null && body.has("confirmed") && !body.get("confirmed").asBoolean()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "confirmed must be true to activate the policy");
        }
        return service.confirm(id);
    }

    @PatchMapping("/{id}")
    ObjectNode patch(@PathVariable String id, @RequestBody JsonNode body) {
        return service.patch(id, body);
    }

    @DeleteMapping("/{id}")
    ObjectNode revoke(@PathVariable String id) {
        return service.revoke(id);
    }
}
