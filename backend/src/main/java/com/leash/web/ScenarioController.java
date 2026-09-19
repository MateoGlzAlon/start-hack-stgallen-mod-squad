package com.leash.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.Json;
import com.leash.Settings;
import com.leash.worker.VisecaClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/** The scenarios a run can be started for, so the UI never needs hardcoded data. */
@Tag(name = "Runs")
@RestController
public class ScenarioController {
    private static final Logger log = LoggerFactory.getLogger(ScenarioController.class);
    private static final long TTL_MS = 5 * 60_000;

    private final VisecaClient viseca;
    private final Settings settings;
    private volatile ObjectNode cached;
    private volatile long cachedAt;

    public ScenarioController(VisecaClient viseca, Settings settings) {
        this.viseca = viseca;
        this.settings = settings;
    }

    @Operation(summary = "Scenarios you can run",
            description = "Read from Viseca's reference data when TEAM_API_KEY is set (cached for 5 minutes), otherwise from the data pack's scenario_catalogue.csv. "
                    + "Each scenario carries the customer's own instruction (cardholder_instruction): create a policy from it with POST /policies, then start the run with POST /runs.")
    @GetMapping("/scenarios")
    synchronized ObjectNode scenarios() {
        if (cached != null && System.currentTimeMillis() - cachedAt < TTL_MS) return cached;
        ObjectNode out = Json.MAPPER.createObjectNode();
        ArrayNode list = out.putArray("scenarios");
        String source = null;
        if (viseca.configured()) {
            try {
                JsonNode ref = viseca.referenceData();
                ref.path("scenarios").forEach(list::add);
                if (!list.isEmpty()) source = "Viseca /v1/reference-data";
            } catch (RuntimeException e) {
                log.warn("Could not read the scenarios from Viseca ({}) - using the data pack", e.getMessage());
            }
        }
        if (list.isEmpty()) {
            fromCatalogue(list);
            source = "data pack scenario_catalogue.csv";
        }
        out.put("source", source);
        if (!list.isEmpty()) { cached = out; cachedAt = System.currentTimeMillis(); }
        return out;
    }

    private void fromCatalogue(ArrayNode into) {
        Path file = settings.dataDir.resolve("scenario_catalogue.csv");
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file);
             CSVParser p = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(r)) {
            for (CSVRecord rec : p) {
                ObjectNode s = into.addObject();
                s.put("scenario_id", rec.get("scenario_id")).put("scenario_name", rec.get("scenario_name"))
                        .put("cardholder_instruction", rec.get("cardholder_instruction")).put("control_question", rec.get("control_question"))
                        .put("control_theme", rec.get("control_theme")).put("event_count", Integer.parseInt(rec.get("event_count")))
                        .put("short_rationale", rec.get("short_rationale"));
            }
        } catch (Exception e) {
            log.warn("Could not read {}: {}", file, e.getMessage());
        }
    }
}
