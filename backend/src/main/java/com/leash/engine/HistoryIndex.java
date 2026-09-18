package com.leash.engine;

import com.leash.Settings;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-card baselines from the past-activity file: how often this card bought at a shop / used a device / bought in a country.
 * Only approved purchases count. Missing file or unknown card => "not available", which rules treat as unknown, never as permission.
 */
@Component
public class HistoryIndex {
    private static final Logger log = LoggerFactory.getLogger(HistoryIndex.class);

    private final Set<String> cards = new HashSet<>();
    private final Map<String, Integer> merchants = new HashMap<>();
    private final Map<String, Integer> devices = new HashMap<>();
    private final Map<String, Integer> countries = new HashMap<>();
    private int rows;

    public HistoryIndex(Settings settings) {
        Path file = settings.dataDir.resolve("authorization_history.csv");
        if (!Files.exists(file)) {
            log.warn("No history file at {} - familiarity facts will be 'unknown'", file.toAbsolutePath());
            return;
        }
        try (Reader r = Files.newBufferedReader(file);
             CSVParser p = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(r)) {
            for (CSVRecord rec : p) {
                if (!"approved".equals(rec.get("status")) || !"purchase".equals(rec.get("transaction_type"))) continue;
                String card = rec.get("card_id");
                cards.add(card);
                merchants.merge(card + "|" + rec.get("merchant_id"), 1, Integer::sum);
                countries.merge(card + "|" + rec.get("merchant_country"), 1, Integer::sum);
                String device = rec.get("customer_device_id");
                if (!device.isBlank()) devices.merge(card + "|" + device, 1, Integer::sum);
                rows++;
            }
            log.info("History index: {} approved purchases over {} cards", rows, cards.size());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read history file {} - familiarity facts will be 'unknown'", file, e);
            cards.clear();
        }
    }

    public int rows() { return rows; }

    public boolean knowsCard(String cardId) { return cards.contains(cardId); }

    public int merchantCount(String cardId, String merchantId) { return merchants.getOrDefault(cardId + "|" + merchantId, 0); }

    public int deviceCount(String cardId, String deviceId) { return devices.getOrDefault(cardId + "|" + deviceId, 0); }

    public int countryCount(String cardId, String country) { return countries.getOrDefault(cardId + "|" + country, 0); }
}
