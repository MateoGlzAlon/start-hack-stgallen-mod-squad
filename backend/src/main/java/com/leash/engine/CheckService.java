package com.leash.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.Json;
import com.leash.Settings;
import com.leash.llm.LlmException;
import com.leash.policy.Policy;
import com.leash.policy.PolicyStore;
import com.leash.policy.Rule;
import com.leash.web.ApiException;
import com.leash.worker.VisecaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static com.leash.engine.Decision.APPROVED;
import static com.leash.engine.Decision.DENIED;
import static com.leash.engine.Decision.PENDING;

/**
 * Purchase in, decision out.
 *   1. parse facts (no LLM)  2. deterministic hard rules  3. only if those cannot settle it, an LLM judge
 *   4. any failure of the judge falls back to the policy's uncertainty_policy.
 * Remembers every decision by live authorization_id: retries return the saved result and never double-count spend.
 */
@Service
public class CheckService {
    private static final Logger log = LoggerFactory.getLogger(CheckService.class);
    public static final String ENGINE_VERSION = "leash-engine/0.1";

    /** What the engine enforces for one purchase, from a local policy or from the mandate snapshot inside the event. */
    private record PolicyView(String id, String instruction, List<Rule> rules, String uncertainty, List<String> guidance, String inactiveCode, String inactiveMessage) {}

    private final Map<String, Decision> decisions = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final RuleEvaluator evaluator = new RuleEvaluator();

    private final PolicyStore policies;
    private final HistoryIndex history;
    private final LlmJudge judge;
    private final VisecaClient viseca;
    private final Settings settings;

    public CheckService(PolicyStore policies, HistoryIndex history, LlmJudge judge, VisecaClient viseca, Settings settings) {
        this.policies = policies;
        this.history = history;
        this.judge = judge;
        this.viseca = viseca;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ check

    /**
     * @param policyId optional local policy to enforce. Without it: the mandate snapshot inside the event if there is one
     *                 (the Viseca path), otherwise every active local policy is tried and the one that fits decides
     * @param runId    optional run id: spend windows and duplicate detection are scoped to it (default: the mandate id)
     * @param source   "api" or "viseca" (viseca-sourced pending decisions are resolved through the Viseca API)
     */
    public Decision check(JsonNode event, String policyId, String runId, String source) {
        PurchaseFacts f = PurchaseFacts.parse(event);
        String key = runId != null && !runId.isBlank() ? runId : f.mandateId() != null ? f.mandateId() : "local";
        synchronized (lock(key)) {
            Decision prior = decisions.get(f.authorizationId());
            if (prior != null) return prior;
            Decision d;
            try {
                d = decideFor(f, policyId, key, source);
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Engine error on {} - asking the customer", f.authorizationId(), e);
                d = make(f, key, source, PENDING, List.of("engine_error", "customer_confirmation"),
                        "I could not evaluate this purchase safely, so I am asking you to review it.", List.of(), List.of(), false);
            }
            // "No active policy" says nothing about the purchase itself: don't remember it, so the same id works once a policy is confirmed.
            if (!d.reasonCodes().contains("no_active_policy")) decisions.put(f.authorizationId(), d);
            return d;
        }
    }

    private Decision decideFor(PurchaseFacts f, String policyId, String key, String source) {
        boolean explicit = policyId != null && !policyId.isBlank();
        if (explicit || f.event().path("mandate").isObject()) {
            Prepared p = prepare(f, policyView(f.event(), policyId), key, source);
            return p.early() != null ? p.early() : judgeAmong(f, key, source, List.of(p), false);
        }
        return decideAuto(f, key, source);
    }

    /**
     * No policy named and no mandate in the event: the model decides. Every active policy (instruction, rules, guidance,
     * uncertainty_policy) and the purchase go to OpenAI in ONE call and its answer is approved / denied / pending_human.
     * The engine only ever vetoes: hard rules are still evaluated for each policy, and the model can't approve under a policy whose
     * rule the purchase breaks or whose fact is unknown. Model unavailable => deterministic fallback (a clean rule pass approves,
     * otherwise the uncertainty_policy).
     */
    private Decision decideAuto(PurchaseFacts f, String key, String source) {
        List<PolicyView> views = policies.all().stream().filter(p -> "active".equals(p.status)).map(CheckService::viewOf).toList();
        if (views.isEmpty()) {
            return make(f, key, source, DENIED, List.of("no_active_policy"),
                    "Declined: there is no active policy, so the agent has no permission to buy. Create one with POST /policies.", List.of(), List.of(), false);
        }
        Decision guard = purchaseGuard(f, key, source);
        if (guard != null) return guard;
        List<Prepared> all = views.stream().map(pv -> prepare(f, pv, key, source)).toList();
        return judgeAmong(f, key, source, all, true);
    }

    /** The model was not available: a clean rule pass still approves; otherwise the uncertainty_policy; nothing fits => denied. */
    private Decision autoFallback(PurchaseFacts f, String key, String source, List<Prepared> all, String why) {
        for (Prepared p : all) if (p.early() != null && APPROVED.equals(p.early().state())) return p.early();
        List<Prepared> couldFit = all.stream().filter(p -> p.early() == null).toList();
        if (!couldFit.isEmpty()) return fallback(f, key, source, couldFit, why);

        List<String> codes = new ArrayList<>(List.of("no_matching_policy"));
        List<Evidence> ev = new ArrayList<>();
        List<RuleEvaluator.Result> checks = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        for (Prepared p : all) {
            Decision d = p.early();
            d.reasonCodes().stream().filter(c -> !codes.contains(c)).forEach(codes::add);
            d.evidence().forEach(e -> ev.add(new Evidence(e.source(), "[" + d.policyId() + "] " + e.fact(), e.value(), e.note())));
            checks.addAll(d.checks());
            parts.add("Policy " + d.policyId() + ": " + d.customerMessage().replaceFirst("^Declined: ", ""));
        }
        String msg = "Declined: " + (all.size() == 1 ? "your active policy does not allow" : "none of your " + all.size() + " active policies allows")
                + " this purchase. " + String.join(" ", parts);
        return make(f, key, source, DENIED, codes, msg, ev, checks, false);
    }

    /** One policy's deterministic verdict. {@code early} is set when the rules alone settled it (guard, failed rule, clean pass). */
    private record Prepared(PolicyView pv, Decision early, List<RuleEvaluator.Result> checks, List<RuleEvaluator.Result> unknowns,
                            List<String> flags, List<Evidence> ev) {}

    private Prepared settled(PurchaseFacts f, PolicyView pv, String key, String source, String state, List<String> codes, String msg,
                             List<Evidence> ev, List<RuleEvaluator.Result> checks) {
        Decision d = make(f, key, source, state, codes, msg, ev, checks, false).withPolicy(pv.id());
        return new Prepared(pv, d, checks, List.of(), List.of(), ev);
    }

    /** Independent of any policy: nothing else matters if the authority behind the purchase or the card is gone. */
    private Decision purchaseGuard(PurchaseFacts f, String key, String source) {
        JsonNode auth = f.event().path("authorization");
        if (!"active".equals(auth.path("authority_status").asText("active"))) {
            return make(f, key, source, DENIED, List.of("authority_not_active"), "Declined: the authority behind this purchase is no longer active.", List.of(), List.of(), false);
        }
        if (!"active".equals(auth.path("card_status_at_attempt").asText("active"))) {
            return make(f, key, source, DENIED, List.of("card_not_active"), "Declined: the card is not active.", List.of(), List.of(), false);
        }
        return null;
    }

    private Prepared prepare(PurchaseFacts f, PolicyView pv, String key, String source) {
        JsonNode auth = f.event().path("authorization");
        List<Evidence> ev = new ArrayList<>();

        if (pv.inactiveCode() != null) return settled(f, pv, key, source, DENIED, List.of(pv.inactiveCode()), pv.inactiveMessage(), ev, List.of());
        Decision guard = purchaseGuard(f, key, source);
        if (guard != null) return new Prepared(pv, guard.withPolicy(pv.id()), List.of(), List.of(), List.of(), ev);

        // Deterministic hard rules.
        JsonNode view = f.view(history);
        List<RuleEvaluator.Result> checks = new ArrayList<>();
        for (Rule r : pv.rules()) {
            var res = evaluator.evaluate(r, view, f.billingChf(), days -> approvedSpend(key, pv.id(), f.timestamp(), days));
            checks.add(res);
            ev.add(new Evidence("rule", RuleEvaluator.describe(r), res.actual(), res.verdict() + ": " + res.detail()));
        }
        List<RuleEvaluator.Result> fails = checks.stream().filter(c -> RuleEvaluator.FAIL.equals(c.verdict())).toList();
        List<RuleEvaluator.Result> unknowns = checks.stream().filter(c -> RuleEvaluator.UNKNOWN.equals(c.verdict())).toList();
        if (!fails.isEmpty()) {
            List<String> codes = new ArrayList<>(List.of("hard_rule_violated"));
            fails.forEach(c -> codes.add(codeOf(c.rule())));
            String msg = "Declined: " + String.join("; ", fails.stream().map(RuleEvaluator.Result::detail).toList()) + ".";
            return settled(f, pv, key, source, DENIED, codes, msg, ev, checks);
        }

        // Soft signals that a plain rule pass cannot vouch for.
        List<String> flags = new ArrayList<>();
        if (!pv.guidance().isEmpty()) flags.add("guidance_check: the customer's policy has requirements that need judgement");
        List<String> hits = TextScan.suspicious(f.untrustedTexts());
        if (!hits.isEmpty()) {
            flags.add("untrusted_text_instruction: shop text addresses the agent/system");
            hits.forEach(h -> ev.add(new Evidence("text_scan", "shop text that looks like an instruction (ignored)", h, null)));
        }
        if (similarEarlier(key, f)) flags.add("possible_duplicate: an earlier purchase in this run has the same shop and items");
        if (auth.hasNonNull("related_authorization_id")) flags.add("linked_to_earlier_order: this order references " + auth.path("related_authorization_status").asText("another") + " order");
        if (auth.path("recent_attempt_count_10m").asInt(0) >= 1) flags.add("recent_activity: other attempts in the last 10 minutes");

        if (unknowns.isEmpty() && flags.isEmpty()) {
            String msg = "Approved: all " + checks.size() + " of your checks passed (CHF " + f.billingChf().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + ").";
            return settled(f, pv, key, source, APPROVED, List.of("all_rules_passed"), msg, ev, checks);
        }
        return new Prepared(pv, null, checks, unknowns, flags, ev);
    }

    /**
     * Ask the model. {@code auto} = every active policy is on the table (no policy was named): then even policies the rules already
     * settled are shown to the model, and the rules only veto. Otherwise only policies the rules could not settle get here.
     * Model off / failed / out of time => the fallback.
     */
    private Decision judgeAmong(PurchaseFacts f, String key, String source, List<Prepared> cands, boolean auto) {
        String why;
        Duration budget = Duration.ofMillis(settings.judgeTimeoutMs);
        if ("viseca".equals(source) && f.deadline() != null) {
            long left = Duration.between(Instant.now(), f.deadline()).toMillis() - 1500;
            budget = Duration.ofMillis(Math.min(settings.judgeTimeoutMs, left));
        }
        if (!judge.available()) why = "judge_unavailable";
        else if (budget.toMillis() < 800) why = "deadline_too_close";
        else {
            try {
                return fromJudge(f, key, source, cands, judge.judge(judgeInput(f, cands, key), budget));
            } catch (LlmException e) {
                log.warn("Judge failed for {}: {}", f.authorizationId(), e.getMessage());
                why = "judge_failed";
            }
        }
        return auto ? autoFallback(f, key, source, cands, why) : fallback(f, key, source, cands, why);
    }

    /** The model is off, failed, or out of time: apply the uncertainty_policy (with several policies, the strictest one). */
    private Decision fallback(PurchaseFacts f, String key, String source, List<Prepared> cands, String why) {
        String uncertainty = cands.stream().anyMatch(p -> "decline".equals(p.pv().uncertainty())) ? "decline"
                : cands.stream().anyMatch(p -> "ask".equals(p.pv().uncertainty())) ? "ask" : "approve";
        String state = stateForUncertainty(uncertainty);
        boolean many = cands.size() > 1;
        List<String> codes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<Evidence> ev = new ArrayList<>();
        List<RuleEvaluator.Result> checks = new ArrayList<>();
        for (Prepared p : cands) {
            p.flags().forEach(s -> codes.add(s.substring(0, s.indexOf(':'))));
            p.unknowns().forEach(c -> codes.add(codeOf(c.rule()).replace("rule_failed_", "unverified_")));
            String tag = many ? "Policy " + p.pv().id() + ": " : "";
            p.unknowns().forEach(c -> reasons.add(tag + c.detail()));
            p.flags().forEach(s -> reasons.add(tag + s.substring(s.indexOf(':') + 2)));
            addEvidence(ev, p, many);
            checks.addAll(p.checks());
        }
        List<String> distinct = new ArrayList<>(codes.stream().distinct().toList());
        distinct.add(why);
        distinct.add("uncertainty_policy_" + uncertainty);
        String tail = String.join("; ", reasons);
        String msg = switch (state) {
            case APPROVED -> "Approved because your policy allows purchases I cannot fully verify. Unverified: " + tail + ".";
            case DENIED -> "Declined because I could not verify this purchase and your policy says to decline when unsure. Unverified: " + tail + ".";
            default -> "Please review this purchase, I could not fully verify it: " + tail + ".";
        };
        Decision d = make(f, key, source, state, distinct, msg, ev, checks, false);
        return many ? d : d.withPolicy(cands.get(0).pv().id());
    }

    private Decision fromJudge(PurchaseFacts f, String key, String source, List<Prepared> cands, LlmJudge.Answer a) {
        Prepared chosen = cands.size() == 1 ? cands.get(0)
                : cands.stream().filter(p -> p.pv().id() != null && p.pv().id().equals(a.policyId())).findFirst().orElse(null);
        String state = stateOf(a.decision());
        List<String> codes = new ArrayList<>(a.reasonCodes());
        String msg = a.customerMessage();
        if (APPROVED.equals(state) && chosen != null && chosen.early() != null && !APPROVED.equals(chosen.early().state())) {
            // The model tried to approve under a policy whose hard rule (or guard) already said no: the rules veto.
            state = DENIED;
            codes = new ArrayList<>(chosen.early().reasonCodes());
            codes.add("model_overruled_by_rules");
            msg = chosen.early().customerMessage();
        } else if (APPROVED.equals(state) && a.manipulationSuspected()) {
            state = PENDING;
            codes.add("possible_manipulation");
            msg = "I stopped this purchase and am asking you first: the shop's text tries to give instructions to the agent, which I ignore.";
        } else if (APPROVED.equals(state) && chosen == null) {
            state = PENDING;
            codes.add("unclear_policy_match");
            msg = "I could not tell which of your policies allows this, so I am asking you: " + msg;
        } else if (APPROVED.equals(state) && !chosen.unknowns().isEmpty()) {
            // The model may not approve past a fact we could not verify.
            state = stateForUncertainty(chosen.pv().uncertainty());
            codes.add("unverified_fact");
            codes.add("uncertainty_policy_" + chosen.pv().uncertainty());
            msg = "I could not verify: " + String.join("; ", chosen.unknowns().stream().map(RuleEvaluator.Result::detail).toList()) + ".";
        }
        if (DENIED.equals(state) && a.assessments().stream().noneMatch(x -> "satisfied".equals(x.status()))) {
            // Declined although a policy is merely unverified: a missing fact is not a reason to decline. The uncertainty_policy decides.
            List<LlmJudge.Assessment> unverified = a.assessments().stream().filter(x -> "unverified".equals(x.status())).toList();
            List<Prepared> open = cands.stream().filter(p -> p.early() == null && unverified.stream().anyMatch(x -> x.policyId().equals(p.pv().id()))).toList();
            if (!open.isEmpty()) {
                String u = open.stream().anyMatch(p -> "decline".equals(p.pv().uncertainty())) ? "decline"
                        : open.stream().anyMatch(p -> "ask".equals(p.pv().uncertainty())) ? "ask" : "approve";
                state = stateForUncertainty(u);
                codes.add("unverified_fact");
                codes.add("uncertainty_policy_" + u);
                msg = "I could not verify this against your policy (" + unverified.stream().filter(x -> x.policyId().equals(open.get(0).pv().id())).findFirst().get().reason() + "), so "
                        + (PENDING.equals(state) ? "I am asking you." : DENIED.equals(state) ? "I am declining it." : "I am approving it as your policy allows.");
                chosen = open.get(0);
            }
        }
        if (PENDING.equals(state) && !a.manipulationSuspected() && !codes.contains("unclear_policy_match")) {
            // Asking the customer only makes sense while some policy could still apply: not one a hard rule already failed, nor one the model found violated.
            boolean anyOpen = cands.stream().anyMatch(p -> p.early() == null
                    && a.assessments().stream().noneMatch(x -> x.policyId().equals(p.pv().id()) && "violated".equals(x.status())));
            if (!anyOpen) {
                List<String> why = new ArrayList<>();
                for (Prepared p : cands) {
                    String r = a.assessments().stream().filter(x -> x.policyId().equals(p.pv().id()) && "violated".equals(x.status()))
                            .map(LlmJudge.Assessment::reason).findFirst()
                            .orElse(p.early() != null ? p.early().customerMessage().replaceFirst("^Declined: ", "") : null);
                    if (r != null && !why.contains(r)) why.add(r);
                }
                state = DENIED;
                codes.add("no_policy_could_apply");
                msg = "I can't approve this: none of your policies allows it. " + String.join(" ", why.subList(0, Math.min(2, why.size())));
                chosen = null;
            }
        }
        if (a.manipulationSuspected() && !codes.contains("possible_manipulation")) codes.add("possible_manipulation");

        List<Evidence> ev = new ArrayList<>();
        List<RuleEvaluator.Result> checks = new ArrayList<>();
        for (Prepared p : chosen != null ? List.of(chosen) : cands) {
            addEvidence(ev, p, chosen == null && cands.size() > 1);
            checks.addAll(p.checks());
        }
        a.evidence().forEach(s -> ev.add(new Evidence("model", s, null, null)));
        Decision d = make(f, key, source, state, codes, msg, ev, checks, true);
        return chosen != null ? d.withPolicy(chosen.pv().id()) : d;
    }

    private static void addEvidence(List<Evidence> into, Prepared p, boolean tag) {
        for (Evidence e : p.ev()) {
            into.add(tag ? new Evidence(e.source(), "[" + p.pv().id() + "] " + e.fact(), e.value(), e.note()) : e);
        }
    }

    // ------------------------------------------------------------------ resolve (human path)

    /** The customer's answer to a pending_human decision. For Viseca-sourced purchases it goes to the platform first. */
    public Decision resolve(String authorizationId, String choice, String message) {
        if (!Set.of("approve", "decline").contains(choice)) throw new ApiException(HttpStatus.BAD_REQUEST, "decision must be 'approve' or 'decline'");
        Decision seen = decisions.get(authorizationId);
        if (seen == null) throw new ApiException(HttpStatus.NOT_FOUND, "No decision for " + authorizationId);
        synchronized (lock(seen.runKey())) {
            Decision d = decisions.get(authorizationId);
            if (!PENDING.equals(d.state())) throw new ApiException(HttpStatus.CONFLICT, "Decision is already " + d.state() + "; only pending_human can be resolved");
            String msg = message != null && !message.isBlank() ? message
                    : "approve".equals(choice) ? "The customer confirmed this purchase." : "The customer rejected this purchase.";
            List<Evidence> extra = List.of(new Evidence("customer", "customer_choice", choice, null));
            if ("viseca".equals(d.source())) {
                try {
                    viseca.resolve(authorizationId, choice, msg, extra);
                } catch (VisecaClient.VisecaException e) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, e.getMessage());
                }
            }
            Decision r = d.resolved(choice, msg, extra);
            decisions.put(authorizationId, r);
            return r;
        }
    }

    // ------------------------------------------------------------------ queries

    public Optional<Decision> find(String authorizationId) {
        return Optional.ofNullable(decisions.get(authorizationId));
    }

    public List<Decision> list(String runKey, String state) {
        return decisions.values().stream()
                .filter(d -> runKey == null || runKey.equals(d.runKey()))
                .filter(d -> state == null || state.equals(d.state()))
                .sorted(Comparator.comparing(Decision::decidedAt).reversed())
                .toList();
    }

    public void markPosted(String authorizationId, boolean ok, String error) {
        decisions.computeIfPresent(authorizationId, (k, d) -> d.withPosted(ok, error));
    }

    /** Last resort for a purchase we could not even parse: always ask the customer, never approve. */
    public Decision failSafeStepUp(String authorizationId, String runKey, String source, String code, String message) {
        return decisions.computeIfAbsent(authorizationId, id -> new Decision(id, runKey == null ? "local" : runKey, source, null, PENDING,
                Decision.visecaFor(PENDING), List.of(code, "customer_confirmation"), message, List.of(), List.of(), false, "engine",
                ENGINE_VERSION, Instant.now().toString(), Instant.now().toString(), null, null, List.of(), BigDecimal.ZERO, false, null));
    }

    // ------------------------------------------------------------------ helpers

    private PolicyView policyView(JsonNode event, String policyId) {
        if (policyId != null && !policyId.isBlank()) {
            return viewOf(policies.get(policyId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No policy " + policyId)));
        }
        JsonNode m = event.path("mandate");
        if (!m.isObject()) throw new ApiException(HttpStatus.BAD_REQUEST, "The event has no mandate and no policy_id was given");
        List<Rule> rules = Json.MAPPER.convertValue(m.path("hard_rules"), new TypeReference<List<Rule>>() {});
        Optional<Policy> local = policies.findByMandateId(m.path("mandate_id").asText(null));
        String code = null, msg = null;
        if (local.isPresent() && "revoked".equals(local.get().status)) {
            code = "policy_revoked";
            msg = "Declined: you revoked this policy, so the agent has no permission to buy.";
        } else if (!"active".equals(m.path("status").asText("active"))) {
            code = "mandate_not_active";
            msg = "Declined: the mandate behind this purchase is " + m.path("status").asText() + ".";
        }
        String uncertainty = m.path("uncertainty_policy").asText("ask");
        if (!Set.of("ask", "decline", "approve").contains(uncertainty)) uncertainty = "ask";
        return new PolicyView(local.map(p -> p.id).orElse(null), m.path("instruction").asText(""), rules == null ? List.of() : rules, uncertainty,
                local.map(p -> p.guidance).orElse(List.of()), code, msg);
    }

    /** A local policy as the engine enforces it. Not active => every purchase is declined with the reason. */
    private static PolicyView viewOf(Policy p) {
        String code = null, msg = null;
        if (!"active".equals(p.status)) {
            code = "revoked".equals(p.status) ? "policy_revoked" : "policy_not_active";
            msg = "Declined: this policy is " + p.status + ", so the agent has no permission to buy.";
        }
        return new PolicyView(p.id, p.instruction, p.hardRules, p.uncertaintyPolicy, p.guidance, code, msg);
    }

    private Object lock(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    /** Final approvals only, under the same policy, over simulated time: (ts - days, ts]. */
    private BigDecimal approvedSpend(String key, String policyId, Instant ts, int days) {
        Instant from = ts.minus(Duration.ofDays(days));
        BigDecimal sum = BigDecimal.ZERO;
        for (Decision d : decisions.values()) {
            if (!key.equals(d.runKey()) || !Objects.equals(policyId, d.policyId()) || !APPROVED.equals(d.state()) || d.purchaseTimestamp() == null) continue;
            Instant t = Instant.parse(d.purchaseTimestamp());
            if (t.isAfter(from) && !t.isAfter(ts)) sum = sum.add(d.billingAmountChf());
        }
        return sum;
    }

    private boolean similarEarlier(String key, PurchaseFacts f) {
        Set<String> items = new TreeSet<>(f.itemIds());
        return decisions.values().stream().anyMatch(d -> key.equals(d.runKey()) && !d.authorizationId().equals(f.authorizationId())
                && f.merchantId().equals(d.merchantId()) && items.equals(new TreeSet<>(d.itemIds())));
    }

    private ObjectNode judgeInput(PurchaseFacts f, List<Prepared> cands, String key) {
        ObjectNode in = Json.MAPPER.createObjectNode();
        ArrayNode pols = in.putArray("customer_policies");
        for (Prepared c : cands) {
            PolicyView pv = c.pv();
            ObjectNode pol = pols.addObject();
            pol.put("policy_id", pv.id() != null ? pv.id() : "event_mandate");
            pol.put("instruction", pv.instruction());
            pol.set("hard_rules", Json.MAPPER.valueToTree(pv.rules()));
            pol.set("guidance", Json.MAPPER.valueToTree(pv.guidance()));
            pol.put("uncertainty_policy", pv.uncertainty());
            ArrayNode dc = pol.putArray("deterministic_checks");
            for (RuleEvaluator.Result r : c.checks()) {
                dc.addObject().put("rule", RuleEvaluator.describe(r.rule())).put("verdict", r.verdict()).put("actual", r.actual()).put("detail", r.detail());
            }
            ArrayNode fl = pol.putArray("why_you_are_asked");
            c.flags().forEach(fl::add);
        }

        JsonNode view = f.view(history);
        JsonNode a = view.path("authorization");
        ObjectNode p = in.putObject("purchase");
        copy(a, p, "timestamp", "amount", "currency", "billing_amount_chf", "items_subtotal", "delivery_fee", "channel", "fulfillment_method",
                "delivery_by", "order_returnable", "order_cancellable", "related_authorization_id", "related_authorization_status",
                "recent_attempt_count_10m", "customer_device_id");
        copy(a.path("merchant"), p.putObject("merchant"), "merchant_id", "merchant_category", "merchant_mcc", "merchant_country",
                "availability", "familiar", "prior_approved_purchases");
        ArrayNode items = p.putArray("items");
        for (JsonNode i : a.path("items")) copy(i, items.addObject(), "line_no", "item_id", "item_name", "item_category", "quantity", "unit_price", "currency");

        in.set("derived_signals", view.path("session"));
        in.set("platform_recent_authorizations", f.event().path("context").path("recent_authorizations"));

        ArrayNode earlier = in.putArray("earlier_purchases_in_this_run");
        List<Decision> before = decisions.values().stream()
                .filter(d -> key.equals(d.runKey()) && !d.authorizationId().equals(f.authorizationId()))
                .sorted(Comparator.comparing(Decision::purchaseTimestamp))
                .toList();
        for (Decision d : before.subList(Math.max(0, before.size() - 10), before.size())) {
            ObjectNode o = earlier.addObject();
            o.put("authorization_id", d.authorizationId()).put("timestamp", d.purchaseTimestamp()).put("merchant_id", d.merchantId())
                    .put("merchant_name", d.merchantName()).put("billing_amount_chf", d.billingAmountChf()).put("state", d.state());
            o.set("item_ids", Json.MAPPER.valueToTree(d.itemIds()));
        }

        ObjectNode t = in.putObject("untrusted_merchant_text");
        t.put("merchant_name", a.path("merchant").path("merchant_name").asText(""));
        t.put("purchase_description", a.path("purchase_description").asText(""));
        ArrayNode ti = t.putArray("items");
        for (JsonNode i : a.path("items")) copy(i, ti.addObject(), "line_no", "item_id", "item_name", "item_details");
        return in;
    }

    private static void copy(JsonNode from, ObjectNode to, String... keys) {
        for (String k : keys) if (from.has(k)) to.set(k, from.get(k));
    }

    private Decision make(PurchaseFacts f, String key, String source, String state, List<String> codes, String message,
                          List<Evidence> ev, List<RuleEvaluator.Result> checks, boolean usedLlm) {
        return new Decision(f.authorizationId(), key, source, null, state, Decision.visecaFor(state), codes, message, ev, checks, usedLlm,
                "engine", ENGINE_VERSION, Instant.now().toString(), f.timestamp().toString(), f.merchantId(), f.merchantName(),
                f.itemIds(), f.billingChf(), false, null);
    }

    private static String codeOf(Rule r) {
        String canon = Fields.canonical(r.field());
        String name = canon == null ? "unsupported_field" : canon.replaceFirst("^authorization\\.", "").replace('.', '_');
        return "rule_failed_" + name + ("period".equals(r.scope()) ? "_period" : "");
    }

    private static String stateOf(String visecaDecision) {
        return switch (visecaDecision) {
            case "approve" -> APPROVED;
            case "decline" -> DENIED;
            default -> PENDING;
        };
    }

    private static String stateForUncertainty(String uncertainty) {
        return switch (uncertainty) {
            case "approve" -> APPROVED;
            case "decline" -> DENIED;
            default -> PENDING;
        };
    }
}
