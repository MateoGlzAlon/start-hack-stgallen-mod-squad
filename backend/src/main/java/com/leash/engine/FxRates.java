package com.leash.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.leash.Json;
import com.leash.Settings;
import com.leash.worker.VisecaClient;
import jakarta.annotation.PostConstruct;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Loads the exchange rates once at startup into {@link Fx}, first source that works wins:
 *   1. Viseca's GET /v1/reference-data when a team key is set (the platform's own rates, the ones behind billing_amount_chf)
 *   2. the exchange rates file, a mock of an exchange-rate API response (FX_RATES_FILE, default mock/exchange-rates.json in the jar)
 *   3. fx_rates.csv in the data pack.
 * Formats are read leniently (the Viseca response format is not specified).
 */
@Component
public class FxRates {
    private static final Logger log = LoggerFactory.getLogger(FxRates.class);
    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    private final Settings settings;
    private final VisecaClient viseca;

    public FxRates(Settings settings, VisecaClient viseca) {
        this.settings = settings;
        this.viseca = viseca;
    }

    @PostConstruct
    void load() {
        Map<String, BigDecimal> rates = new HashMap<>();
        String from = null;
        // 1. Viseca's own rates, when a team key is set (they are the ones behind billing_amount_chf)
        if (viseca.configured()) {
            try {
                collect(viseca.referenceData(), rates);
                if (rates.keySet().stream().anyMatch(c -> !"CHF".equals(c))) from = "Viseca /v1/reference-data";
                else log.warn("Viseca reference data has no exchange rates I can read - using the data pack file");
            } catch (RuntimeException e) {
                log.warn("Could not load exchange rates from Viseca ({}) - using the data pack file", e.getMessage());
            }
        }
        // 2. the exchange rates file: a mock of an exchange-rate API response (FX_RATES_FILE, default: the one inside the jar)
        if (from == null) {
            rates.clear();
            from = fromRatesFile(rates);
        }
        // 3. the data pack's fx_rates.csv
        if (from == null) {
            rates.clear();
            from = fromFile(rates);
        }
        if (from == null) {
            log.error("No exchange rates loaded: only CHF amounts can be checked, other currencies are treated as unknown");
            return;
        }
        Fx.load(rates, from);
        log.info("Exchange rates to CHF from {}: {}", from, Fx.describe());
    }

    private String fromRatesFile(Map<String, BigDecimal> rates) {
        String name = settings.fxRatesFile.isBlank() ? "mock/exchange-rates.json (in the jar)" : settings.fxRatesFile;
        try (InputStream in = settings.fxRatesFile.isBlank()
                ? getClass().getResourceAsStream("/mock/exchange-rates.json") : Files.newInputStream(Path.of(settings.fxRatesFile))) {
            if (in == null) return null;
            JsonNode doc = Json.MAPPER.readTree(in);
            collect(doc, rates);
            if (rates.keySet().stream().noneMatch(c -> !"CHF".equals(c))) return null;
            String provider = doc.path("provider").asText("");
            return provider.isBlank() ? "exchange rates file " + name : provider;
        } catch (Exception e) {
            log.warn("Could not read the exchange rates file {}: {}", name, e.getMessage());
            return null;
        }
    }

    private String fromFile(Map<String, BigDecimal> rates) {
        Path file = settings.dataDir.resolve("fx_rates.csv");
        if (!Files.exists(file)) {
            log.warn("No exchange rate file at {}", file.toAbsolutePath());
            return null;
        }
        try (Reader r = Files.newBufferedReader(file);
             CSVParser p = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(r)) {
            for (CSVRecord rec : p) {
                if (!"CHF".equalsIgnoreCase(rec.get("to_currency"))) continue;
                BigDecimal rate = positive(rec.get("rate"));
                if (rate != null) rates.put(rec.get("from_currency").toUpperCase(), rate);
            }
            return rates.isEmpty() ? null : "data pack fx_rates.csv";
        } catch (Exception e) {
            log.warn("Could not read {}: {}", file, e.getMessage());
            return null;
        }
    }

    /** Finds currency -> CHF rates anywhere in a JSON document: rows like {from_currency, to_currency, rate}, or maps like {"EUR": 0.95}. */
    static void collect(JsonNode n, Map<String, BigDecimal> out) {
        if (n == null) return;
        if (n.isObject()) {
            String code = first(n, "from_currency", "currency", "code");
            BigDecimal rate = positive(first(n, "rate", "to_chf", "chf"));
            String to = first(n, "to_currency");
            if (code != null && rate != null && CODE.matcher(code.toUpperCase()).matches() && (to == null || "CHF".equalsIgnoreCase(to))) {
                out.put(code.toUpperCase(), rate);
            }
            n.fields().forEachRemaining(e -> {
                BigDecimal v = e.getValue().isValueNode() ? positive(e.getValue().asText()) : null;
                if (v != null && CODE.matcher(e.getKey()).matches() && n.size() <= 12) out.put(e.getKey(), v);
                else collect(e.getValue(), out);
            });
        } else if (n.isArray()) {
            n.forEach(c -> collect(c, out));
        }
    }

    private static String first(JsonNode n, String... keys) {
        for (String k : keys) if (n.hasNonNull(k) && n.get(k).isValueNode()) return n.get(k).asText();
        return null;
    }

    private static BigDecimal positive(String s) {
        if (s == null) return null;
        try {
            BigDecimal d = new BigDecimal(s.trim());
            return d.signum() > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
