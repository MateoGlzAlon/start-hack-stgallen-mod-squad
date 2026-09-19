package com.leash.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.Json;
import com.leash.engine.CheckService;
import com.leash.policy.Policy;
import com.leash.policy.PolicyService;
import com.leash.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Starts a Viseca scenario run for one of our active policies. The worker picks the purchases up by itself. */
@Service
public class RunService {
    private final PolicyService policies;
    private final VisecaClient viseca;
    private final CheckService checks;

    public RunService(PolicyService policies, VisecaClient viseca, CheckService checks) {
        this.policies = policies;
        this.viseca = viseca;
        this.checks = checks;
    }

    public ObjectNode start(String policyId, String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "scenario_id is required");
        if (!viseca.configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TEAM_API_KEY is not set");
        Policy p = policies.require(policyId);
        if (!"active".equals(p.status)) throw new ApiException(HttpStatus.CONFLICT, "Policy is " + p.status + "; only an active policy can start a run");
        try {
            String mandateId = policies.ensureMandate(p);
            JsonNode run = viseca.startRun(scenarioId, mandateId);
            ObjectNode out = Json.MAPPER.createObjectNode();
            out.put("policy_id", p.id).put("mandate_id", mandateId);
            out.set("run", run);
            return out;
        } catch (VisecaClient.VisecaException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    public ObjectNode get(String runId) {
        try {
            ObjectNode out = Json.MAPPER.createObjectNode();
            out.set("run", viseca.getRun(runId));
            out.set("decisions", Json.MAPPER.valueToTree(checks.list(runId, null)));
            return out;
        } catch (VisecaClient.VisecaException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }
}
