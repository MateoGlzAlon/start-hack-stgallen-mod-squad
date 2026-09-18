package com.leash.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The few typed facts the engine needs, read from a Viseca authorization event without any LLM.
 * Everything else stays reachable through the raw event / the augmented {@link #view(HistoryIndex)}.
 */
public record PurchaseFacts(
        JsonNode event,
        String authorizationId,
        String cardId,
        String mandateId,
        Instant timestamp,
        BigDecimal billingChf,
        String merchantId,
        String merchantName,
        List<String> itemIds,
        Instant deadline) {

    /** At least this many earlier approved purchases at a shop on the same card make it "familiar". */
    public static final int FAMILIAR_MIN_PURCHASES = 2;

    public static PurchaseFacts parse(JsonNode event) {
        if (event == null || !event.isObject()) throw new IllegalArgumentException("Body must be a purchase event (a JSON object)");
        JsonNode a = event.path("authorization");
        List<String> bad = new ArrayList<>();

        String authId = text(a, "authorization_id", "authorization.authorization_id", bad);
        String cardId = text(a, "card_id", "authorization.card_id", bad);
        String merchantId = text(a.path("merchant"), "merchant_id", "authorization.merchant.merchant_id", bad);
        Instant ts = null;
        try {
            ts = Instant.parse(a.path("timestamp").asText(""));
        } catch (DateTimeParseException e) {
            bad.add("authorization.timestamp");
        }
        BigDecimal chf = null;
        if (a.path("billing_amount_chf").isNumber()) chf = a.get("billing_amount_chf").decimalValue();
        else bad.add("authorization.billing_amount_chf");
        List<String> itemIds = new ArrayList<>();
        if (!a.path("items").isArray() || a.get("items").isEmpty()) bad.add("authorization.items");
        else a.get("items").forEach(i -> itemIds.add(i.path("item_id").asText("")));

        if (!bad.isEmpty()) throw new IllegalArgumentException("Not a valid authorization event, missing or invalid: " + String.join(", ", bad));

        String mandateId = a.path("mandate_id").asText(event.path("mandate").path("mandate_id").asText(null));
        Instant deadline = null;
        try {
            if (event.hasNonNull("deadline_at")) deadline = Instant.parse(event.get("deadline_at").asText());
        } catch (DateTimeParseException ignored) { /* no deadline known: treat as generous */ }

        return new PurchaseFacts(event, authId, cardId, mandateId, ts, chf, merchantId,
                a.path("merchant").path("merchant_name").asText(""), itemIds, deadline);
    }

    /** The event plus facts derived from history, under authorization.merchant.* and session.*. Unknown card => nothing derived. */
    public JsonNode view(HistoryIndex history) {
        ObjectNode root = event.deepCopy();
        ObjectNode a = (ObjectNode) root.get("authorization");
        ObjectNode m = a.get("merchant") instanceof ObjectNode o ? o : a.putObject("merchant");
        ObjectNode s = root.putObject("session");
        if (a.has("recent_attempt_count_10m")) s.set("recent_attempt_count_10m", a.get("recent_attempt_count_10m"));
        if (history.knowsCard(cardId)) {
            int mc = history.merchantCount(cardId, merchantId);
            m.put("familiar", mc >= FAMILIAR_MIN_PURCHASES ? "true" : "false");
            m.put("prior_approved_purchases", mc);
            String device = a.path("customer_device_id").asText("");
            if (!device.isBlank()) {
                int dc = history.deviceCount(cardId, device);
                s.put("device_familiar", dc >= 1 ? "true" : "false");
                s.put("device_prior_approved_purchases", dc);
            }
            String country = m.path("merchant_country").asText("");
            if (!country.isBlank()) {
                int cc = history.countryCount(cardId, country);
                s.put("country_familiar", cc >= 1 ? "true" : "false");
                s.put("country_prior_approved_purchases", cc);
            }
        }
        return root;
    }

    /** Everything the shop wrote. Untrusted: facts may be extracted, instructions must be ignored. */
    public List<String> untrustedTexts() {
        JsonNode a = event.path("authorization");
        List<String> texts = new ArrayList<>();
        texts.add(a.path("purchase_description").asText(null));
        texts.add(a.path("merchant").path("merchant_name").asText(null));
        for (JsonNode i : a.path("items")) {
            texts.add(i.path("item_name").asText(null));
            texts.add(i.path("item_details").asText(null));
        }
        return texts;
    }

    private static String text(JsonNode parent, String key, String path, List<String> bad) {
        String v = parent.path(key).asText("");
        if (v.isBlank()) bad.add(path);
        return v;
    }
}
