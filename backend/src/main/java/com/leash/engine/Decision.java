package com.leash.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The outcome for one proposed purchase. state: approved | denied | pending_human (a pending one is NOT spend).
 * Also the memory the engine keeps about earlier purchases (spend windows, duplicates), keyed by authorization_id.
 */
public record Decision(
        String authorizationId,
        String runKey,
        String source,
        String state,
        String visecaDecision,
        List<String> reasonCodes,
        String customerMessage,
        List<Evidence> evidence,
        List<RuleEvaluator.Result> checks,
        boolean usedLlm,
        String decidedBy,
        String engineVersion,
        String decidedAt,
        String purchaseTimestamp,
        String merchantId,
        String merchantName,
        List<String> itemIds,
        BigDecimal billingAmountChf,
        boolean postedToViseca,
        String visecaError) {

    public static final String APPROVED = "approved";
    public static final String DENIED = "denied";
    public static final String PENDING = "pending_human";

    public static String visecaFor(String state) {
        return switch (state) {
            case APPROVED -> "approve";
            case DENIED -> "decline";
            default -> "step_up";
        };
    }

    public Decision withPosted(boolean ok, String error) {
        return new Decision(authorizationId, runKey, source, state, visecaDecision, reasonCodes, customerMessage, evidence, checks,
                usedLlm, decidedBy, engineVersion, decidedAt, purchaseTimestamp, merchantId, merchantName, itemIds, billingAmountChf, ok, error);
    }

    /** The customer's answer to a pending_human decision. */
    public Decision resolved(String choice, String message, List<Evidence> extraEvidence) {
        String newState = "approve".equals(choice) ? APPROVED : DENIED;
        List<String> codes = new ArrayList<>(reasonCodes);
        codes.add("customer_" + choice + "d");
        List<Evidence> ev = new ArrayList<>(evidence);
        ev.addAll(extraEvidence);
        return new Decision(authorizationId, runKey, source, newState, visecaFor(newState), codes, message, ev, checks,
                usedLlm, "customer", engineVersion, Instant.now().toString(), purchaseTimestamp, merchantId, merchantName, itemIds,
                billingAmountChf, postedToViseca, visecaError);
    }
}
