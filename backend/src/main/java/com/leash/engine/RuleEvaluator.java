package com.leash.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.leash.policy.Rule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Deterministic hard-rule check. Each rule ends up pass / fail / unknown.
 * Unknown (missing, null, "unknown", unsupported field) is never permission: the caller decides what to do with it.
 * Fields that fan out over the cart (items.*) must hold for EVERY line.
 */
public final class RuleEvaluator {
    public static final String PASS = "pass", FAIL = "fail", UNKNOWN = "unknown";

    public record Result(Rule rule, String verdict, String actual, String detail) {}

    private record Cmp(String verdict, String shown) {}

    /**
     * @param view        the event plus derived facts (see PurchaseFacts.view)
     * @param thisAmount  this order's billing_amount_chf, added to the approved spend for period rules
     * @param periodSpend approved spend in the last N days (simulated time), supplied by the caller's ledger
     */
    public Result evaluate(Rule rule, JsonNode view, BigDecimal thisAmount, IntFunction<BigDecimal> periodSpend) {
        String canon = Fields.canonical(rule.field());
        if (canon == null) return new Result(rule, UNKNOWN, null, "'" + rule.field() + "' cannot be checked automatically");
        String label = label(canon);
        boolean money = canon.endsWith("_chf");
        String op = rule.operator();

        if ("period".equals(rule.scope())) {
            if (!canon.equals("authorization.billing_amount_chf")) return new Result(rule, UNKNOWN, null, label + ": period totals are only supported for the order total");
            if (!(rule.value() instanceof Number n) || rule.periodDays() == null || rule.periodDays() < 1) {
                return new Result(rule, UNKNOWN, null, label + ": period rule is incomplete");
            }
            BigDecimal target = target(n, rule, true);
            if (target == null) return new Result(rule, UNKNOWN, null, label + ": no exchange rate for " + rule.currency());
            BigDecimal spent = periodSpend.apply(rule.periodDays());
            BigDecimal total = spent.add(thisAmount);
            Boolean ok = numeric(total, op, target);
            String actual = "CHF " + fmt(total, true) + " (CHF " + fmt(spent, true) + " already approved + CHF " + fmt(thisAmount, true) + " this order)";
            if (ok == null) return new Result(rule, UNKNOWN, actual, label + ": unsupported operator " + op);
            return new Result(rule, ok ? PASS : FAIL, actual,
                    rule.periodDays() + "-day total is " + actual + ", needs " + words(op) + " CHF " + fmt(target, true));
        }

        List<JsonNode> values = resolve(view, Fields.viewPath(canon));
        if (values.isEmpty()) return new Result(rule, UNKNOWN, null, label + " is not available");

        String worst = PASS;
        LinkedHashSet<String> shown = new LinkedHashSet<>();
        LinkedHashSet<String> failed = new LinkedHashSet<>();
        for (JsonNode v : values) {
            Cmp c = compare(v, rule, canon, money);
            shown.add(c.shown());
            if (FAIL.equals(c.verdict())) { worst = FAIL; failed.add(c.shown()); }
            else if (UNKNOWN.equals(c.verdict()) && !FAIL.equals(worst)) worst = UNKNOWN;
        }
        String targetText = targetText(rule, money);
        String need = words(op) + " " + targetText;
        return switch (worst) {
            case FAIL -> new Result(rule, FAIL, String.join(", ", failed), label + " is " + String.join(", ", failed) + ", needs " + need);
            case UNKNOWN -> new Result(rule, UNKNOWN, String.join(", ", shown), label + " could not be verified (" + String.join(", ", shown) + "), needs " + need);
            default -> new Result(rule, PASS, String.join(", ", shown), label + " " + String.join(", ", shown) + " satisfies " + need);
        };
    }

    private Cmp compare(JsonNode actual, Rule rule, String canon, boolean money) {
        if (actual == null || actual.isNull() || actual.isMissingNode()) return new Cmp(UNKNOWN, "unknown");
        String text = actual.asText();
        if (text.isBlank() || "unknown".equalsIgnoreCase(text)) return new Cmp(UNKNOWN, "unknown");
        String op = rule.operator();
        Object target = rule.value();

        if (target instanceof Number n) {
            BigDecimal a = number(actual);
            if (a == null) return new Cmp(UNKNOWN, text);
            BigDecimal limit = target(n, rule, money);
            if (limit == null) return new Cmp(UNKNOWN, fmt(a, money));
            Boolean ok = numeric(a, op, limit);
            return new Cmp(ok == null ? UNKNOWN : ok ? PASS : FAIL, fmt(a, money));
        }
        List<String> targets = target instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(String.valueOf(target));
        boolean hit = targets.stream().anyMatch(t -> t.equalsIgnoreCase(text));
        return switch (op) {
            case "in", "=" -> new Cmp(hit ? PASS : FAIL, text);
            case "not_in", "!=" -> new Cmp(hit ? FAIL : PASS, text);
            default -> new Cmp(UNKNOWN, text);
        };
    }

    /** Null when the operator makes no sense for numbers. in/not_in with a number mean =/!=. */
    private static Boolean numeric(BigDecimal a, String op, BigDecimal t) {
        int c = a.compareTo(t);
        return switch (op) {
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            case "=", "in" -> c == 0;
            case "!=", "not_in" -> c != 0;
            default -> null;
        };
    }

    /** Rule value as a BigDecimal, converted to CHF when the rule states another currency on a *_chf field. Null when there is no rate for that currency. */
    private static BigDecimal target(Number n, Rule rule, boolean money) {
        BigDecimal t = new BigDecimal(n.toString());
        if (money && rule.currency() != null) t = Fx.toChf(t, rule.currency());
        return t;
    }

    private static BigDecimal number(JsonNode n) {
        if (n.isNumber()) return n.decimalValue();
        if (n.isTextual()) {
            try { return new BigDecimal(n.asText().trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** Values found at a dotted path; arrays fan out, and a cart line missing the key counts as null (= unknown). */
    static List<JsonNode> resolve(JsonNode root, String path) {
        List<JsonNode> cur = List.of(root);
        for (String seg : path.split("\\.")) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode n : cur) {
                if (n.isArray()) {
                    for (JsonNode e : n) next.add(e.has(seg) ? e.get(seg) : NullNode.instance);
                } else if (n.has(seg)) {
                    next.add(n.get(seg));
                }
            }
            if (next.isEmpty()) return next;
            cur = next;
        }
        List<JsonNode> flat = new ArrayList<>();
        for (JsonNode n : cur) {
            if (n.isArray()) n.forEach(flat::add); else flat.add(n);
        }
        return flat;
    }

    private static String targetText(Rule rule, boolean money) {
        if (rule.value() instanceof List<?> l) return String.join(", ", l.stream().map(String::valueOf).toList());
        if (rule.value() instanceof Number n) {
            BigDecimal t = target(n, rule, money);
            return t == null ? rule.currency() + " " + n : (money ? "CHF " : "") + fmt(t, money);
        }
        return String.valueOf(rule.value());
    }

    private static String fmt(BigDecimal b, boolean money) {
        return money ? b.setScale(2, RoundingMode.HALF_UP).toPlainString() : b.stripTrailingZeros().toPlainString();
    }

    private static String words(String op) {
        return switch (op) {
            case "<" -> "less than";
            case "<=" -> "at most";
            case "=" -> "exactly";
            case "!=" -> "anything except";
            case ">" -> "more than";
            case ">=" -> "at least";
            case "in" -> "one of";
            case "not_in" -> "none of";
            default -> op;
        };
    }

    private static String label(String canon) {
        return switch (canon) {
            case "authorization.billing_amount_chf" -> "Order total";
            case "merchant.familiar" -> "Shop familiarity";
            default -> {
                String last = canon.substring(canon.lastIndexOf('.') + 1).replace('_', ' ');
                yield Character.toUpperCase(last.charAt(0)) + last.substring(1);
            }
        };
    }

    /** "authorization.billing_amount_chf <= 120 CHF (period 7d)" */
    public static String describe(Rule r) {
        StringBuilder sb = new StringBuilder(r.field()).append(' ').append(r.operator()).append(' ').append(r.value());
        if (r.currency() != null) sb.append(' ').append(r.currency());
        if (r.scope() != null) sb.append(" (").append(r.scope()).append(r.periodDays() != null ? " " + r.periodDays() + "d" : "").append(')');
        return sb.toString();
    }
}
