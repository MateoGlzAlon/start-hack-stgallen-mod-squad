package com.leash.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The vocabulary of facts a hard rule may talk about. The policy compiler is told this list and the rule
 * evaluator only understands this list. Free merchant text (item_details, names, descriptions) is deliberately
 * absent: shop-supplied text can never be the subject of a rule.
 */
public final class Fields {
    public static final Map<String, String> ALLOWED = new LinkedHashMap<>();

    static {
        ALLOWED.put("authorization.billing_amount_chf",
                "number. Total charged for the whole order in CHF, delivery included. scope=purchase compares this one order; "
                        + "scope=period with period_days=N compares (approved spend in the last N days + this order).");
        ALLOWED.put("authorization.recent_attempt_count_10m", "number. Earlier purchase attempts by the agent in the last 10 minutes.");
        ALLOWED.put("authorization.channel", "string: ecommerce | in_store | mobile_wallet | recurring | atm");
        ALLOWED.put("authorization.fulfillment_method", "string, e.g. delivery | pickup");
        ALLOWED.put("authorization.order_returnable", "string: \"true\" | \"false\" | \"unknown\" | \"not_applicable\"");
        ALLOWED.put("authorization.order_cancellable", "string: \"true\" | \"false\" | \"unknown\" | \"not_applicable\"");
        ALLOWED.put("merchant.merchant_category",
                "string: books | clothing | dining | electronics | entertainment | food_delivery | fuel | groceries | health | "
                        + "home_improvement | hotel | household | kids_family | pet_care | photography | software | sporting_goods | "
                        + "subscriptions | sustainable_goods | transport | travel");
        ALLOWED.put("merchant.merchant_country", "string, ISO country code such as CH, IT, US");
        ALLOWED.put("merchant.familiar",
                "string \"true\" | \"false\". \"true\" when the card has at least 2 earlier approved purchases at this shop (\"a shop I use regularly / have used before\").");
        ALLOWED.put("items.item_category",
                "string, checked for EVERY cart line: books | clothing | cosmetics | dining | electronics | food_delivery | fuel | "
                        + "gift_card | groceries | home_improvement | household | hotel | membership | sporting_goods | subscriptions | transport");
        ALLOWED.put("items.quantity", "number, checked for EVERY cart line");
    }

    private Fields() {}

    /** Canonical allowed field name, or null when the rule talks about something we cannot check deterministically. */
    public static String canonical(String field) {
        if (field == null) return null;
        String f = field.trim();
        if (f.startsWith("authorization.merchant.") || f.startsWith("authorization.items.")) {
            f = f.substring("authorization.".length());
        }
        if (ALLOWED.containsKey(f)) return f;
        if (ALLOWED.containsKey("authorization." + f)) return "authorization." + f;
        return null;
    }

    /** Where the fact lives inside the augmented event (see PurchaseFacts.view). */
    public static String viewPath(String canonical) {
        return canonical.startsWith("authorization.") ? canonical : "authorization." + canonical;
    }
}
