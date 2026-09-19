package com.leash.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.Json;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Is this seller shady?", answered from Viseca's own data and independent of the customer's policy (always on).
 * Two signals, both without any LLM:
 *   - lookalike: the seller's name is almost the same as an established shop in merchants.csv, and it has far less history
 *     (PixelHarbour vs PixelHarbor). An approval then becomes a question to the customer ("alert").
 *   - platform history: how many approved purchases / distinct cards the whole history file has at this seller ("caution" when
 *     nobody or almost nobody has bought there). Only context and evidence: unfamiliar is not wrong, and it must not block ordinary shopping.
 * Never approves anything and never declines anything by itself.
 */
@Component
public class SellerCheck {
    private static final Logger log = LoggerFactory.getLogger(SellerCheck.class);

    /** Names at least this similar (0..1, ignoring case, spaces and punctuation) read as "almost the same shop". */
    static final double LOOKALIKE_MIN = 0.85;
    /** The shop being imitated must be well known: at least this many approved purchases on the platform. */
    static final int ESTABLISHED_MIN = 5;
    /** Fewer approved purchases than this on the whole platform = a thin history. */
    static final int THIN_BELOW = 5;

    private record Shop(String id, String name, String norm) {}

    /** level: alert | caution | ok. message is for the customer (only set on alert). */
    public record Result(String level, String message, List<Evidence> evidence, JsonNode context) {}

    private final HistoryIndex history;
    private final List<Shop> catalogue = new ArrayList<>();

    public SellerCheck(HistoryIndex history, Settings settings) {
        this.history = history;
        Path file = settings.dataDir.resolve("merchants.csv");
        if (!Files.exists(file)) {
            log.warn("No merchant catalogue at {} - the lookalike check is off", file.toAbsolutePath());
            return;
        }
        try (Reader r = Files.newBufferedReader(file);
             CSVParser p = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(r)) {
            for (CSVRecord rec : p) catalogue.add(new Shop(rec.get("merchant_id"), rec.get("merchant_name"), norm(rec.get("merchant_name"))));
            log.info("Seller check: {} shops in the catalogue", catalogue.size());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read {} - the lookalike check is off", file, e);
            catalogue.clear();
        }
    }

    /** Null when there is no platform history at all to judge from (then nothing is said, never a made-up verdict). */
    public Result assess(PurchaseFacts f) {
        if (history.rows() == 0) return null;
        String name = f.merchantName().isBlank() ? catalogueName(f.merchantId()) : f.merchantName();
        int purchases = history.shopPurchases(f.merchantId());
        int cards = history.shopCards(f.merchantId());
        int byCard = history.merchantCount(f.cardId(), f.merchantId());
        boolean cardKnowsSeller = history.knowsCard(f.cardId()) && byCard >= PurchaseFacts.FAMILIAR_MIN_PURCHASES;

        List<Evidence> ev = new ArrayList<>();
        ObjectNode ctx = Json.MAPPER.createObjectNode();
        ctx.put("seller_purchases_on_platform", purchases).put("seller_cards_on_platform", cards).put("purchases_by_this_card", byCard);

        // Signal 1: lookalike of an established shop that this seller is much smaller than.
        Shop twin = null;
        double best = 0;
        String n = norm(name);
        for (Shop s : catalogue) {
            if (s.id().equals(f.merchantId()) || n.isEmpty()) continue;
            double sim = similarity(n, s.norm());
            if (sim >= LOOKALIKE_MIN && sim > best) { best = sim; twin = s; }
        }
        boolean alert = false;
        if (twin != null) {
            int twinPurchases = history.shopPurchases(twin.id());
            int twinCards = history.shopCards(twin.id());
            int byCardTwin = history.merchantCount(f.cardId(), twin.id());
            if (twinPurchases >= ESTABLISHED_MIN && twinPurchases > purchases && !cardKnowsSeller) {
                alert = true;
                ev.add(new Evidence("seller_check", "Name almost identical to an established seller",
                        "\"" + name + "\" vs \"" + twin.name() + "\" (" + Math.round(best * 100) + "% the same)",
                        twin.name() + " (" + twin.id() + "): " + twinPurchases + " purchases on " + twinCards + " cards"
                                + (byCardTwin > 0 ? ", " + byCardTwin + " by this card" : "")));
                ObjectNode lk = ctx.putObject("lookalike_of");
                lk.put("merchant_name", twin.name()).put("merchant_id", twin.id()).put("name_match_percent", Math.round(best * 100))
                        .put("purchases_on_platform", twinPurchases).put("purchases_by_this_card", byCardTwin);
            }
        }

        // Signal 2: history of the seller across the whole platform.
        String depth = purchases == 0 ? "none" : purchases < THIN_BELOW ? "thin" : "established";
        ctx.put("platform_history", depth);
        ev.add(new Evidence("seller_check", "Seller history on the platform",
                purchases == 0 ? "no approved purchase on any card" : purchases + " approved purchases on " + cards + " cards",
                byCard > 0 ? "this card bought here " + byCard + "x" : "this card has not bought here before"));

        String level = alert ? "alert" : !"established".equals(depth) ? "caution" : "ok";
        ctx.put("level", level);

        String msg = null;
        if (alert) {
            ObjectNode lk = (ObjectNode) ctx.get("lookalike_of");
            int twinByCard = lk.get("purchases_by_this_card").asInt();
            msg = "Please check the seller before I buy: \"" + name + "\" is almost the same name as \"" + twin.name() + "\", "
                    + (twinByCard > 0 ? "a shop you have used " + twinByCard + " times" : "a well-known shop with " + lk.get("purchases_on_platform").asInt() + " purchases")
                    + ", but " + (purchases == 0 ? "\"" + name + "\" has no purchase history at all" : "\"" + name + "\" has only " + purchases + " purchases")
                    + ". This could be an imitation.";
        }
        return new Result(level, msg, ev, ctx);
    }

    /**
     * The engine's answer with the seller check applied: its findings become evidence on every decision, and an approval at a
     * lookalike seller turns into a question for the customer. A denial or an existing question stays as it is.
     */
    public Decision apply(PurchaseFacts f, Decision d) {
        Result r = assess(f);
        if (r == null) return d;
        List<Evidence> ev = new ArrayList<>(d.evidence());
        ev.addAll(r.evidence());
        if (!"alert".equals(r.level())) return d.with(d.state(), d.reasonCodes(), d.customerMessage(), ev);
        if (Decision.APPROVED.equals(d.state())) {
            return d.with(Decision.PENDING, List.of("lookalike_seller", "customer_confirmation"), r.message(), ev);
        }
        List<String> codes = new ArrayList<>(d.reasonCodes());
        if (!codes.contains("lookalike_seller")) codes.add("lookalike_seller");
        String msg = d.customerMessage();
        if (Decision.DENIED.equals(d.state())) {
            msg += " The seller's name is also almost the same as \"" + r.context().path("lookalike_of").path("merchant_name").asText() + "\", an established shop.";
        }
        return d.with(d.state(), codes, msg, ev);
    }

    private String catalogueName(String merchantId) {
        return catalogue.stream().filter(s -> s.id().equals(merchantId)).map(Shop::name).findFirst().orElse("");
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** 1 - edit distance / longer length. */
    static double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return 1.0 - (double) prev[b.length()] / max;
    }
}
