package com.leash.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leash.policy.Rule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.leash.engine.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class RuleEvaluatorTest {
    private final RuleEvaluator ev = new RuleEvaluator();

    private RuleEvaluator.Result run(Rule rule, JsonNode event) {
        PurchaseFacts f = PurchaseFacts.parse(event);
        return ev.evaluate(rule, event, f.billingChf(), days -> BigDecimal.ZERO);
    }

    private static ObjectNode withItem(ObjectNode e, String id, String category) {
        var items = (com.fasterxml.jackson.databind.node.ArrayNode) e.get("authorization").get("items");
        items.addObject().put("line_no", 2).put("item_id", id).put("item_name", "x").put("item_category", category)
                .put("quantity", 1).put("unit_price", 5.0).put("currency", "CHF").put("item_details", "");
        return e;
    }

    @Test
    void limitBoundaryIsInclusive() {
        var e = event("A1", "2026-08-12T09:00:00Z", 120.00);
        assertThat(run(money("<=", 120), e).verdict()).isEqualTo(RuleEvaluator.PASS);
        assertThat(run(money("<", 120), e).verdict()).isEqualTo(RuleEvaluator.FAIL);
        assertThat(run(money("<=", 119.99), e).verdict()).isEqualTo(RuleEvaluator.FAIL);
    }

    @Test
    void foreignCurrencyLimitIsConvertedToChf() {
        var e = event("A1", "2026-08-12T09:00:00Z", 190.00);            // EUR 200 = CHF 190.00
        Rule eur = new Rule("authorization.billing_amount_chf", "<=", 200, "EUR", "purchase", null);
        assertThat(run(eur, e).verdict()).isEqualTo(RuleEvaluator.PASS);
        assertThat(run(eur, event("A2", "2026-08-12T09:00:00Z", 190.01)).verdict()).isEqualTo(RuleEvaluator.FAIL);
    }

    @Test
    void everyCartLineMustSatisfyAListRule() {
        var e = withItem(event("A1", "2026-08-12T09:00:00Z", 20), "IT9", "cosmetics");
        assertThat(run(rule("items.item_category", "in", List.of("groceries")), e).verdict()).isEqualTo(RuleEvaluator.FAIL);
        assertThat(run(rule("items.item_category", "not_in", List.of("cosmetics", "gift_card")), e).verdict()).isEqualTo(RuleEvaluator.FAIL);
        assertThat(run(rule("items.item_category", "in", List.of("groceries", "cosmetics")), e).verdict()).isEqualTo(RuleEvaluator.PASS);
    }

    @Test
    void missingNullAndUnknownFactsAreNeverPermission() {
        var e = event("A1", "2026-08-12T09:00:00Z", 20);
        assertThat(run(rule("authorization.order_returnable", "=", "true"), e).verdict()).isEqualTo(RuleEvaluator.UNKNOWN);   // "unknown"
        assertThat(run(rule("merchant.familiar", "=", "true"), e).verdict()).isEqualTo(RuleEvaluator.UNKNOWN);                   // no history for this card
        assertThat(run(rule("authorization.customer_device_id", "=", "x"), e).verdict()).isEqualTo(RuleEvaluator.UNKNOWN);      // not an allowed field
        ((ObjectNode) e.get("authorization")).putNull("channel");
        assertThat(run(rule("authorization.channel", "=", "ecommerce"), e).verdict()).isEqualTo(RuleEvaluator.UNKNOWN);         // null
    }

    @Test
    void freeMerchantTextCanNeverBeARuleSubject() {
        var e = event("A1", "2026-08-12T09:00:00Z", 20);
        assertThat(run(rule("items.item_details", "in", List.of("Synthetic parser example")), e).verdict()).isEqualTo(RuleEvaluator.UNKNOWN);
        assertThat(run(rule("authorization.purchase_description", "=", "Example grocery order"), e).verdict()).isEqualTo(RuleEvaluator.UNKNOWN);
    }

    @Test
    void periodRuleAddsApprovedSpendToThisOrder() {
        var e = event("A1", "2026-08-12T09:00:00Z", 66.00);
        PurchaseFacts f = PurchaseFacts.parse(e);
        var over = ev.evaluate(period("<=", 300, 7), e, f.billingChf(), days -> new BigDecimal("234.50"));
        assertThat(over.verdict()).isEqualTo(RuleEvaluator.FAIL);
        var exact = ev.evaluate(period("<=", 300, 7), e, f.billingChf(), days -> new BigDecimal("234.00"));
        assertThat(exact.verdict()).isEqualTo(RuleEvaluator.PASS);
    }
}
