package com.leash.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.leash.Json;
import com.leash.engine.Fields;
import com.leash.engine.Fx;
import com.leash.llm.LlmException;
import com.leash.llm.OpenAiClient;
import com.leash.Settings;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Natural-language instruction -> Viseca-shaped rules, via OpenAI structured output. Policy time, not latency-critical. */
@Component
public class PolicyCompiler {
    public record Compiled(List<Rule> hardRules, String uncertaintyPolicy, List<String> guidance, List<String> openQuestions) {}

    private static final Set<String> OPERATORS = Set.of("<", "<=", "=", "!=", ">", ">=", "in", "not_in");
    private static final Set<String> NUMERIC_OPERATORS = Set.of("<", "<=", ">", ">=");
    private static final Set<String> CURRENCIES = Set.of("CHF", "EUR", "GBP", "USD");

    private final OpenAiClient openai;
    private final Settings settings;
    private final JsonNode schema;

    public PolicyCompiler(OpenAiClient openai, Settings settings) {
        this.openai = openai;
        this.settings = settings;
        try {
            var fields = Json.MAPPER.valueToTree(Fields.ALLOWED.keySet());
            var root = Json.MAPPER.readTree("""
                    {
                      "type": "object", "additionalProperties": false,
                      "required": ["hard_rules", "uncertainty_policy", "guidance", "open_questions"],
                      "properties": {
                        "hard_rules": {"type": "array", "items": {
                          "type": "object", "additionalProperties": false,
                          "required": ["field", "operator", "value", "currency", "scope", "period_days"],
                          "properties": {
                            "field": {"type": "string"},
                            "operator": {"type": "string", "enum": ["<", "<=", "=", "!=", ">", ">=", "in", "not_in"]},
                            "value": {"anyOf": [{"type": "number"}, {"type": "string"}, {"type": "array", "items": {"type": "string"}}]},
                            "currency": {"type": ["string", "null"], "enum": ["CHF", "EUR", "GBP", "USD", null]},
                            "scope": {"type": ["string", "null"], "enum": ["purchase", "period", null]},
                            "period_days": {"type": ["integer", "null"]}
                          }}},
                        "uncertainty_policy": {"type": "string", "enum": ["ask", "decline", "approve"]},
                        "guidance": {"type": "array", "items": {"type": "string"}},
                        "open_questions": {"type": "array", "items": {"type": "string"}}
                      }
                    }""");
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/properties/hard_rules/items/properties/field")).set("enum", fields);
            this.schema = root;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Compiled compile(String instruction) throws LlmException {
        JsonNode out = openai.chatJson(systemPrompt(), instruction, "wallet_policy", schema, Duration.ofMillis(settings.compileTimeoutMs));
        List<Rule> rules = new ArrayList<>();
        List<String> guidance = new ArrayList<>();
        for (JsonNode n : out.path("guidance")) guidance.add(n.asText());
        for (JsonNode n : out.path("hard_rules")) {
            Rule r = clean(n);
            String problem = r == null ? "unreadable rule" : problem(r);
            if (problem == null) rules.add(r);
            else guidance.add("Not enforced automatically (" + problem + "): " + n);
        }
        List<String> questions = new ArrayList<>();
        for (JsonNode n : out.path("open_questions")) questions.add(n.asText());
        String uncertainty = out.path("uncertainty_policy").asText("ask");
        if (!Set.of("ask", "decline", "approve").contains(uncertainty)) uncertainty = "ask";
        if (rules.isEmpty() && guidance.isEmpty()) {
            questions.add("I could not find anything enforceable in that instruction. Could you state a limit, item or shop requirement?");
        }
        return new Compiled(rules, uncertainty, guidance, questions);
    }

    /** Drops the nulls the strict schema forced the model to emit (the Viseca API wants unused optional keys omitted). */
    private static Rule clean(JsonNode n) {
        JsonNode v = n.get("value");
        Object value;
        if (v == null || v.isNull()) return null;
        else if (v.isNumber()) value = v.decimalValue().stripTrailingZeros().scale() <= 0 ? (Object) v.longValue() : (Object) v.doubleValue();
        else if (v.isTextual()) value = v.asText();
        else if (v.isArray()) {
            List<String> l = new ArrayList<>();
            v.forEach(x -> l.add(x.asText()));
            value = l;
        } else return null;
        String currency = n.hasNonNull("currency") ? n.get("currency").asText() : null;
        String scope = n.hasNonNull("scope") ? n.get("scope").asText() : null;
        Integer days = n.hasNonNull("period_days") ? n.get("period_days").asInt() : null;
        if (!"period".equals(scope)) days = null;
        return new Rule(n.path("field").asText(null), n.path("operator").asText(null), value, currency, scope, days);
    }

    /** null when the rule is acceptable; otherwise why not. Used for compiled rules and for PATCH additions. */
    public static String problem(Rule r) {
        if (Fields.canonical(r.field()) == null) return "unsupported field '" + r.field() + "'";
        if (r.operator() == null || !OPERATORS.contains(r.operator())) return "unknown operator";
        if (r.value() == null) return "missing value";
        boolean list = r.value() instanceof List<?>;
        boolean number = r.value() instanceof Number;
        boolean string = r.value() instanceof String;
        if (!list && !number && !string) return "value must be a number, a string or a list of strings";
        if (list && ((List<?>) r.value()).stream().anyMatch(x -> !(x instanceof String))) return "list values must be strings";
        if (NUMERIC_OPERATORS.contains(r.operator()) && !number) return "operator " + r.operator() + " needs a number";
        if (("in".equals(r.operator()) || "not_in".equals(r.operator())) && number) return "operator " + r.operator() + " needs a string or list";
        if (r.currency() != null && !CURRENCIES.contains(r.currency())) return "unknown currency";
        if (r.scope() != null && !Set.of("purchase", "period").contains(r.scope())) return "unknown scope";
        if ("period".equals(r.scope()) && (r.periodDays() == null || r.periodDays() < 1)) return "period scope needs period_days >= 1";
        if (!"period".equals(r.scope()) && r.periodDays() != null) return "period_days only applies to scope=period";
        return null;
    }

    private static String systemPrompt() {
        StringBuilder fields = new StringBuilder();
        Fields.ALLOWED.forEach((k, v) -> fields.append("- ").append(k).append(": ").append(v).append('\n'));
        return """
                You turn a customer's plain-language shopping instruction into a wallet policy that a rule engine enforces \
                on every purchase an AI shopping agent proposes with the customer's card.

                Output:
                - hard_rules: machine-checkable limits. Only use these fields (exact names):
                %s
                  Operators: < <= = != > >= in not_in. "in"/"not_in" take a string or a list of strings; the others compare numbers.
                  Keep every amount in the currency the customer used and put that currency in "currency" (CHF, EUR, GBP or USD). NEVER convert \
                an amount yourself: the system converts it exactly (fixed rates to CHF: %s). The field name must keep _chf even for EUR, GBP or USD amounts. scope: "purchase" for a per-order limit; "period" with period_days for a \
                rolling total across several days (e.g. per 7 days). Set unused optional keys (currency, scope, period_days) to null.
                  When the customer names the kind of goods ("groceries", "clothing", "electronics"), write an items.item_category rule so \
                every line of the basket is checked (groceries -> items.item_category in ["groceries"]); when they name the kind of shop \
                ("a specialist sports retailer" = merchant category sporting_goods), write a merchant.merchant_category rule. Things they \
                say they never want ("never gift cards") become items.item_category not_in. Do NOT guess a category for a specific product \
                such as shoes, a monitor or a hat (categories are ambiguous): describe the product in guidance instead. Never invent ids, \
                names or other values that the customer did not say.
                - Every requirement in the sentence must end up either as a hard rule or as a guidance line. Never drop one: product type, colour, \
                size, brand, return terms, "no extras" and session-safety wishes all go into guidance if they are not a rule.
                - guidance: everything the customer asked for that cannot be written as a rule above (specific product, size, colour, \
                return terms of at least N days, shop type, no add-ons or extras, "pause if the session looks hijacked", ...). Each line is a \
                REQUIREMENT stated in the customer's meaning, e.g. "Only black running shoes". NEVER write a question, a request for more \
                information or a suggestion in guidance, and never add requirements about things the customer did not mention (no size given \
                means no size requirement). A judge later checks these against each purchase, so do not drop any.
                - open_questions: real ambiguities the customer should confirm (e.g. "Are trail-running shoes acceptable?"). Usually empty.
                - uncertainty_policy: what to do when a purchase cannot be verified. "ask" (default; also for "ask me when uncertain"), \
                "decline" (if they want unclear purchases blocked), "approve" (only if they explicitly say to go ahead when unsure).

                Examples (fields shown as field operator value):
                "Get me a blue umbrella for max CHF 30, ask me if unsure." ->
                  hard_rules: [authorization.billing_amount_chf <= 30 CHF purchase]; uncertainty_policy ask; guidance ["Only a blue umbrella"]; open_questions [].
                "Order pasta and milk from a shop I know, at most 50 francs per week, decline anything odd." ->
                  hard_rules: [authorization.billing_amount_chf <= 50 CHF period 7 days, merchant.familiar = "true", items.item_category in ["groceries"]]; \
                uncertainty_policy decline; guidance ["Only pasta and milk"]; open_questions [].

                Never invent limits or requirements the customer did not state. Never loosen anything. Enforce exactly what was said.""".formatted(fields, Fx.describe());
    }
}
