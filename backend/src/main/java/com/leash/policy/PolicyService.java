package com.leash.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.Json;
import com.leash.llm.LlmException;
import com.leash.web.ApiException;
import com.leash.worker.VisecaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Policy lifecycle: create (active at once) -> tighten -> revoke. Local state is authoritative; Viseca sync is best effort. */
@Service
public class PolicyService {
    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    private final PolicyStore store;
    private final PolicyCompiler compiler;
    private final PolicyClarifier clarifier;
    private final VisecaClient viseca;

    public PolicyService(PolicyStore store, PolicyCompiler compiler, PolicyClarifier clarifier, VisecaClient viseca) {
        this.store = store;
        this.compiler = compiler;
        this.clarifier = clarifier;
        this.viseca = viseca;
    }

    /** Is the sentence specific enough to enforce? If not, what to ask the customer (nothing is stored). */
    public PolicyClarifier.Result clarify(String instruction) {
        if (instruction == null || instruction.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "instruction is required");
        if (instruction.length() > 2000) throw new ApiException(HttpStatus.BAD_REQUEST, "instruction is too long (max 2000 chars)");
        try {
            return clarifier.clarify(instruction);
        } catch (LlmException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Policy compiler unavailable: " + e.getMessage());
        }
    }

    public Policy create(String instruction) {
        if (instruction == null || instruction.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "instruction is required");
        if (instruction.length() > 2000) throw new ApiException(HttpStatus.BAD_REQUEST, "instruction is too long (max 2000 chars)");
        PolicyCompiler.Compiled c;
        try {
            c = compiler.compile(instruction);
        } catch (LlmException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Policy compiler unavailable: " + e.getMessage());
        }
        Policy p = new Policy();
        p.id = "POL-" + UUID.randomUUID().toString().substring(0, 8);
        p.status = "active";
        p.confirmedAt = Instant.now().toString();
        p.instruction = instruction;
        p.hardRules = c.hardRules();
        p.uncertaintyPolicy = c.uncertaintyPolicy();
        p.guidance = c.guidance();
        p.openQuestions = c.openQuestions();
        p.createdAt = Instant.now().toString();
        return store.save(p);
    }

    /** Tighten only: add rules, move uncertainty_policy toward decline, replace guidance/questions. */
    public ObjectNode patch(String id, JsonNode body) {
        Policy p = require(id);
        if (!"active".equals(p.status)) throw new ApiException(HttpStatus.CONFLICT, "Only an active policy can be tightened (this one is " + p.status + ")");

        List<Rule> newRules = null;
        if (body.has("hard_rules")) {
            List<Rule> supplied = new ArrayList<>();
            for (JsonNode n : body.get("hard_rules")) {
                Rule r = Json.MAPPER.convertValue(n, Rule.class);
                String problem = PolicyCompiler.problem(r);
                if (problem != null) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid rule " + n + ": " + problem);
                supplied.add(r);
            }
            List<Rule> remaining = new ArrayList<>(supplied);
            for (Rule existing : p.hardRules) {
                Rule match = remaining.stream().filter(r -> sameRule(existing, r)).findFirst().orElseThrow(() ->
                        new ApiException(HttpStatus.BAD_REQUEST, "Existing rules cannot be removed or changed; missing: " + existing));
                remaining.remove(match);
            }
            newRules = new ArrayList<>(p.hardRules);
            newRules.addAll(remaining);
        }
        String uncertainty = p.uncertaintyPolicy;
        if (body.hasNonNull("uncertainty_policy")) {
            String u = body.get("uncertainty_policy").asText();
            if (!u.equals(p.uncertaintyPolicy) && !"decline".equals(u)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "uncertainty_policy can only move toward decline (now " + p.uncertaintyPolicy + ")");
            }
            uncertainty = u;
        }

        if (newRules != null) p.hardRules = newRules;
        p.uncertaintyPolicy = uncertainty;
        if (body.has("guidance")) p.guidance = strings(body.get("guidance"));
        if (body.has("open_questions")) p.openQuestions = strings(body.get("open_questions"));
        store.save(p);

        String sync = "not_synced";
        if (p.visecaMandateId != null && viseca.configured()) {
            try {
                viseca.patchMandate(p.visecaMandateId, p);
                sync = "ok (applies to later runs)";
            } catch (RuntimeException e) {
                log.warn("Viseca PATCH failed", e);
                sync = "failed: " + e.getMessage();
            }
        }
        return withSync(p, sync);
    }

    /** Revocation is local and immediate; the platform is told afterwards. */
    public ObjectNode revoke(String id) {
        Policy p = require(id);
        if ("revoked".equals(p.status)) return withSync(p, "already_revoked");
        p.status = "revoked";
        p.revokedAt = Instant.now().toString();
        store.save(p);

        String sync = "not_synced";
        if (p.visecaMandateId != null && viseca.configured()) {
            try {
                viseca.revokeMandate(p.visecaMandateId);
                sync = "ok";
            } catch (RuntimeException e) {
                log.warn("Viseca DELETE failed", e);
                sync = "failed: " + e.getMessage();
            }
        }
        return withSync(p, sync);
    }

    /** Makes sure the policy exists as a confirmed Viseca mandate (needed to start a run). Returns the mandate id. */
    public String ensureMandate(Policy p) {
        if (p.visecaMandateId != null) return p.visecaMandateId;
        JsonNode draft = viseca.createMandateDraft(p);
        p.visecaDraftId = draft.path("draft_id").asText(null);
        if (p.visecaDraftId == null) throw new IllegalStateException("Viseca returned no draft_id: " + draft);
        JsonNode confirmed = viseca.confirmMandate(p.visecaDraftId);
        p.visecaMandateId = confirmed.path("mandate_id").asText(null);
        if (p.visecaMandateId == null) throw new IllegalStateException("Viseca returned no mandate_id: " + confirmed);
        store.save(p);
        return p.visecaMandateId;
    }

    public Policy require(String id) {
        return store.get(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No policy " + id));
    }

    private static ObjectNode withSync(Policy p, String sync) {
        ObjectNode n = Json.MAPPER.valueToTree(p);
        n.put("viseca_sync", sync);
        return n;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> l = new ArrayList<>();
        arr.forEach(x -> l.add(x.asText()));
        return l;
    }

    static boolean sameRule(Rule a, Rule b) {
        return Objects.equals(a.field(), b.field()) && Objects.equals(a.operator(), b.operator())
                && Objects.equals(a.currency(), b.currency()) && Objects.equals(a.scope(), b.scope())
                && Objects.equals(a.periodDays(), b.periodDays()) && sameValue(a.value(), b.value());
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        return Objects.equals(a, b);
    }
}
