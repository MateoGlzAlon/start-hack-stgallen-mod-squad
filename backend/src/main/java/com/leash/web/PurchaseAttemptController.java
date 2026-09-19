package com.leash.web;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.Json;
import com.leash.Settings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Example purchase attempts as ready-to-send authorization events. Viseca only hands attempts out inside a scenario run, so these come from the
 * data pack (purchase_attempts.csv + items + merchants), the same hand-authored attempts the sandbox replays. Send each one to POST /check.
 */
@Tag(name = "Check")
@RestController
public class PurchaseAttemptController {
    private static final List<String> NUMBERS = List.of("amount", "billing_amount_chf", "items_subtotal", "delivery_fee", "spend_in_period_before_chf");
    private static final List<String> MERCHANT = List.of("merchant_id", "merchant_name", "merchant_category", "merchant_mcc", "merchant_country", "merchant_city", "availability", "recurring_capable");

    private final Settings settings;

    public PurchaseAttemptController(Settings settings) {
        this.settings = settings;
    }

    @Operation(summary = "Example purchase attempts to test the policies with",
            description = "Events in Viseca's authorization format, in replay order, from the data pack (Viseca serves attempts only during a run). "
                    + "Send each one to POST /check (add run_id so spend windows and duplicates are counted per test, and change authorization_id to run it again). "
                    + "Filter with scenario_id, e.g. SCEN0004.")
    @GetMapping("/purchase-attempts")
    ObjectNode attempts(@Parameter(description = "e.g. SCEN0004; all scenarios if omitted") @RequestParam(name = "scenario_id", required = false) String scenarioId) {
        try {
            Map<String, CSVRecord> merchants = new HashMap<>();
            for (CSVRecord r : read("merchants.csv")) merchants.put(r.get("merchant_id"), r);
            Map<String, List<CSVRecord>> items = new HashMap<>();
            for (CSVRecord r : read("purchase_attempt_items.csv")) items.computeIfAbsent(r.get("authorization_id"), k -> new ArrayList<>()).add(r);

            List<CSVRecord> rows = new ArrayList<>();
            for (CSVRecord r : read("purchase_attempts.csv")) if (scenarioId == null || scenarioId.isBlank() || scenarioId.equals(r.get("scenario_id"))) rows.add(r);
            rows.sort(Comparator.comparing((CSVRecord r) -> r.get("scenario_id")).thenComparingInt(r -> Integer.parseInt(r.get("replay_order"))));
            if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "No purchase attempts for scenario " + scenarioId);

            ObjectNode out = Json.MAPPER.createObjectNode();
            out.put("source", "data pack purchase_attempts.csv");
            ArrayNode list = out.putArray("attempts");
            for (CSVRecord r : rows) {
                ObjectNode a = list.addObject().putObject("authorization");
                for (String col : r.getParser().getHeaderNames()) {
                    if (col.equals("merchant_id")) continue;
                    String v = r.get(col);
                    if (v.isBlank()) a.putNull(col);
                    else if (NUMBERS.contains(col)) a.put(col, new BigDecimal(v));
                    else if (col.equals("recent_attempt_count_10m") || col.equals("replay_order")) a.put(col, Integer.parseInt(v));
                    else a.put(col, v);
                }
                a.put("source_authorization_id", r.get("authorization_id"));
                CSVRecord m = merchants.get(r.get("merchant_id"));
                ObjectNode mo = a.putObject("merchant");
                if (m != null) MERCHANT.forEach(k -> mo.put(k, m.get(k)));
                else mo.put("merchant_id", r.get("merchant_id"));
                ArrayNode lines = a.putArray("items");
                for (CSVRecord i : items.getOrDefault(r.get("authorization_id"), List.of())) {
                    lines.addObject().put("line_no", Integer.parseInt(i.get("line_no"))).put("item_id", i.get("item_id")).put("item_name", i.get("item_name"))
                            .put("item_category", i.get("item_category")).put("quantity", Integer.parseInt(i.get("quantity")))
                            .put("unit_price", new BigDecimal(i.get("unit_price"))).put("currency", i.get("currency")).put("item_details", i.get("item_details"));
                }
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Could not read the purchase attempts from " + settings.dataDir + ": " + e.getMessage());
        }
    }

    private List<CSVRecord> read(String file) throws Exception {
        Path p = settings.dataDir.resolve(file);
        try (Reader r = Files.newBufferedReader(p); CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(r)) {
            return parser.getRecords();
        }
    }
}
