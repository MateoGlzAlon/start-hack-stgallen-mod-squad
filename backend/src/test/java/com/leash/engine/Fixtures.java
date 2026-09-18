package com.leash.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.Json;
import com.leash.Settings;
import com.leash.policy.Rule;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Test helpers: settings, the official example event, and events rebuilt from the data pack CSVs (offline replay). */
final class Fixtures {
    static final Path DATA = Path.of("../resources/viseca-2026-main/data");

    private Fixtures() {}

    static Settings settings(Path storeDir) {
        return new Settings("http://localhost:1", "", false, "http://localhost:1/v1", "", "test-model", 1000, 1000,
                storeDir.toString(), DATA.toString());
    }

    static ObjectNode example() {
        try {
            return (ObjectNode) Json.MAPPER.readTree(DATA.resolve("scenario_fixtures/example_authorization_request.json").toFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The example event with a new id, simulated time and amount, and these rules in its mandate snapshot. */
    static ObjectNode event(String authId, String timestamp, double chf, Rule... rules) {
        ObjectNode e = example();
        ObjectNode a = (ObjectNode) e.get("authorization");
        a.put("authorization_id", authId).put("source_authorization_id", authId).put("timestamp", timestamp).put("billing_amount_chf", chf);
        ((ObjectNode) a.get("items").get(0)).put("item_id", "IT-" + authId);      // distinct basket per purchase unless a test says otherwise
        ((ObjectNode) e.get("mandate")).set("hard_rules", Json.MAPPER.valueToTree(List.of(rules)));
        return e;
    }

    static ObjectNode sameBasket(ObjectNode e, String itemId) {
        ((ObjectNode) e.get("authorization").get("items").get(0)).put("item_id", itemId);
        return e;
    }

    static Rule rule(String field, String op, Object value) {
        return new Rule(field, op, value, null, null, null);
    }

    static Rule money(String op, double chf) {
        return new Rule("authorization.billing_amount_chf", op, chf, "CHF", "purchase", null);
    }

    static Rule period(String op, double chf, int days) {
        return new Rule("authorization.billing_amount_chf", op, chf, "CHF", "period", days);
    }

    // ---- offline replay: rebuild events from the CSVs, following technical_details.md section 3 ----

    static List<ObjectNode> scenarioEvents(String scenarioId) throws IOException {
        Map<String, CSVRecord> merchants = new HashMap<>();
        for (CSVRecord r : csv("merchants.csv")) merchants.put(r.get("merchant_id"), r);
        Map<String, List<CSVRecord>> items = new HashMap<>();
        for (CSVRecord r : csv("purchase_attempt_items.csv")) items.computeIfAbsent(r.get("authorization_id"), k -> new ArrayList<>()).add(r);

        List<CSVRecord> attempts = new ArrayList<>();
        for (CSVRecord r : csv("purchase_attempts.csv")) if (scenarioId.equals(r.get("scenario_id"))) attempts.add(r);
        attempts.sort(Comparator.comparingInt(r -> Integer.parseInt(r.get("replay_order"))));

        List<ObjectNode> events = new ArrayList<>();
        for (CSVRecord r : attempts) {
            ObjectNode e = example();
            ObjectNode a = (ObjectNode) e.get("authorization");
            a.put("authorization_id", r.get("authorization_id")).put("source_authorization_id", r.get("authorization_id"));
            a.put("scenario_id", r.get("scenario_id")).put("replay_order", Integer.parseInt(r.get("replay_order")));
            a.put("card_id", r.get("card_id")).put("timestamp", r.get("timestamp"));
            a.put("amount", new BigDecimal(r.get("amount"))).put("currency", r.get("currency"));
            a.put("billing_amount_chf", new BigDecimal(r.get("billing_amount_chf")));
            a.put("items_subtotal", new BigDecimal(r.get("items_subtotal"))).put("delivery_fee", new BigDecimal(r.get("delivery_fee")));
            a.put("channel", r.get("channel")).put("customer_device_id", r.get("customer_device_id"));
            a.put("authority_status", r.get("authority_status")).put("card_status_at_attempt", r.get("card_status_at_attempt"));
            a.putNull("spend_in_period_before_chf").put("recent_attempt_count_10m", Integer.parseInt(r.get("recent_attempt_count_10m")));
            a.put("fulfillment_method", r.get("fulfillment_method"));
            nullable(a, "delivery_by", r.get("delivery_by"));
            a.put("order_returnable", r.get("order_returnable")).put("order_cancellable", r.get("order_cancellable"));
            nullable(a, "related_authorization_id", r.get("related_authorization_id"));
            nullable(a, "related_authorization_status", r.get("related_authorization_status"));
            a.put("purchase_description", r.get("purchase_description"));

            CSVRecord m = merchants.get(r.get("merchant_id"));
            ObjectNode mo = (ObjectNode) a.get("merchant");
            for (String k : List.of("merchant_id", "merchant_name", "merchant_category", "merchant_mcc", "merchant_country", "merchant_city", "availability", "recurring_capable")) {
                mo.put(k, m.get(k));
            }
            ArrayNode arr = a.putArray("items");
            for (CSVRecord i : items.get(r.get("authorization_id"))) {
                arr.addObject().put("line_no", Integer.parseInt(i.get("line_no"))).put("item_id", i.get("item_id")).put("item_name", i.get("item_name"))
                        .put("item_category", i.get("item_category")).put("quantity", Integer.parseInt(i.get("quantity")))
                        .put("unit_price", new BigDecimal(i.get("unit_price"))).put("currency", i.get("currency")).put("item_details", i.get("item_details"));
            }
            events.add(e);
        }
        return events;
    }

    private static void nullable(ObjectNode n, String key, String v) {
        if (v == null || v.isBlank()) n.putNull(key); else n.put(key, v);
    }

    private static Iterable<CSVRecord> csv(String file) throws IOException {
        try (Reader r = Files.newBufferedReader(DATA.resolve(file))) {
            return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(r).getRecords();
        }
    }

    static JsonNode asJson(Object o) {
        return Json.MAPPER.valueToTree(o);
    }
}
