package com.leash.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap tripwire for merchant text that talks to the agent/system instead of describing a product.
 * A hit never approves or declines by itself: it forces the purchase to the judge and forbids approval-by-text.
 */
public final class TextScan {
    private static final List<Pattern> PATTERNS = List.of(
            "ignore (all |any |the )?(previous|prior|above|earlier)",
            "disregard",
            "\\bsystem\\s*:",
            "pre-?authori[sz]ed",
            "without (any )?(further|additional) (checks?|verification|approval|confirmation)",
            "(automated|ai|purchasing|shopping)\\s+(purchasing\\s+)?(agents?|assistants?|bots?)",
            "approve (this |it |the (order|purchase) )?(immediately|automatically|without)",
            "(cardholder|customer|user|owner) (is )?(unavailable|not available|has (already )?(approved|authori[sz]ed))",
            "(spending|purchase|budget)\\s+(limits?|instructions?|rules?|policy)",
            "\\boverride\\b"
    ).stream().map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE)).toList();

    private TextScan() {}

    /** Snippets of the texts that look like instructions aimed at whoever processes them. */
    public static List<String> suspicious(List<String> texts) {
        List<String> hits = new ArrayList<>();
        for (String t : texts) {
            if (t == null) continue;
            for (Pattern p : PATTERNS) {
                Matcher m = p.matcher(t);
                if (m.find()) {
                    if (t.length() <= 200) { hits.add(t.trim()); break; }
                    int from = Math.max(0, m.start() - 20), to = Math.min(t.length(), m.end() + 40);
                    // widen to whole words so the quote never starts or ends mid-word
                    while (from > 0 && !Character.isWhitespace(t.charAt(from - 1))) from--;
                    while (to < t.length() && !Character.isWhitespace(t.charAt(to))) to++;
                    hits.add(t.substring(from, to).trim());
                    break;
                }
            }
        }
        return hits;
    }
}
