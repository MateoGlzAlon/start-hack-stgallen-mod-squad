import type { Check, Evidence, Policy } from './api';
import { money } from './format';

/** "return_period_unknown" -> "Return period unknown" */
export const humanize = (code: string) => {
  const s = code.replace(/[_.]+/g, ' ').trim();
  return s.charAt(0).toUpperCase() + s.slice(1);
};

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
const nice = (v: string) => v.replace(/_/g, ' ');
const unique = (s: string) => [...new Set(s.split(',').map((x) => x.trim()).filter(Boolean))].join(', ');

/** The first sentence of the customer's instruction, short enough for a line of evidence. */
export function policyName(p?: Policy): string | null {
  if (!p) return null;
  const first = p.instruction.split(/(?<=[.!?])\s/)[0].trim();
  return first.length > 90 ? `${first.slice(0, 90).replace(/\s+\S*$/, '')}…` : first;
}

// what decided the outcome first, the noise about unrelated policies last
const ORDER: Record<string, number> = { Policy: -0.5, Fits: 0, 'Unverified': 1, 'Shop text': 2, Price: 3, Seller: 4, 'Doesn’t fit': 5, You: 6 };

export type Line = { tag: string; tone: 'ok' | 'no' | 'ask' | 'muted'; text: string; sub?: string };

/** One piece of evidence as a plain sentence, with no ids or field names. */
export function describeEvidence(e: Evidence, nameOf: (id: string) => string | null): Line {
  const value = e.value ?? '';
  const note = e.note ?? '';

  if (e.source === 'model') {
    const m = e.fact.match(/^Policy (POL-[A-Za-z0-9]+) (satisfied|violated|unverified): ([\s\S]*)$/);
    if (m) {
      const name = nameOf(m[1]);
      const who = name ? `“${name}”` : 'One of your policies';
      const tag = m[2] === 'satisfied' ? 'Fits' : m[2] === 'violated' ? 'Doesn’t fit' : 'Unverified';
      return { tag, tone: m[2] === 'satisfied' ? 'ok' : m[2] === 'violated' ? 'no' : 'ask', text: who, sub: m[3] };
    }
    return { tag: 'AI', tone: 'muted', text: e.fact };
  }

  if (e.source === 'policy') {
    const name = nameOf(value);
    return { tag: 'Policy', tone: 'muted', text: name ? `The closest of your policies: \u201c${name}\u201d` : 'The closest of your policies', sub: note ? note.charAt(0).toUpperCase() + note.slice(1) + '.' : undefined };
  }

  if (e.source === 'customer') {
    return { tag: 'You', tone: 'muted', text: value === 'approve' ? 'You approved this purchase.' : value === 'decline' ? 'You declined this purchase.' : 'You answered.' };
  }

  if (e.source === 'seller_check') {
    if (e.fact.startsWith('Name almost identical')) {
      const n = value.match(/"([^"]+)" vs "([^"]+)" \((\d+)% the same\)/);
      const t = note.match(/^(.*?) \(ME\d+\): (\d+) purchases? on (\d+) cards?(?:, (\d+) by this card)?/);
      const text = n
        ? `The seller’s name, “${n[1]}”, is ${n[3] === '100' ? 'exactly the same as' : 'almost the same as'} “${n[2]}”${n[3] === '100' ? '' : ` (${n[3]}% alike)`}${t ? `, a shop with ${plural(Number(t[2]), 'purchase', 'purchases')} on ${plural(Number(t[3]), 'card', 'cards')}${t[4] ? `, ${t[4]} of them yours` : ''}` : ''}. It could be an imitation.`
        : `${e.fact}: ${value}`;
      return { tag: 'Seller', tone: 'no', text };
    }
    const h = value.match(/(\d+) approved purchases? on (\d+) cards?/);
    const bought = note.match(/bought here (\d+)x/);
    const yours = bought ? `You have bought here ${plural(Number(bought[1]), 'time', 'times')}.` : 'You haven’t bought here before.';
    const text = h
      ? `${plural(Number(h[1]), 'purchase has', 'purchases have')} been approved at this seller, across ${plural(Number(h[2]), 'card', 'cards')}. ${yours}`
      : `Nobody on the platform has bought from this seller yet. ${yours}`;
    return { tag: 'Seller', tone: 'muted', text };
  }

  if (e.source === 'currency') {
    if (e.fact.startsWith('CHF amount does not match')) {
      const v = value.charAt(0).toUpperCase() + value.slice(1);
      return { tag: 'Price', tone: 'ask', text: `The amount doesn’t add up. ${v}. I used the higher amount for every check.` };
    }
    return { tag: 'Price', tone: 'muted', text: `The price was converted to Swiss francs: ${value}.`, sub: note ? note.replace(/^rates from /, 'Exchange rates: ') : undefined };
  }

  if (e.source === 'text_scan') {
    return { tag: 'Shop text', tone: 'ask', text: `The shop’s text tries to give instructions. I ignored it: “${value}”` };
  }

  return { tag: e.source, tone: 'muted', text: value ? `${e.fact}: ${value}` : e.fact, sub: note || undefined };
}

/** A rule check as a plain sentence (instead of "Merchant category sporting_goods satisfies exactly sporting_goods"). */
export function explainCheck(c: Check): string {
  const f = c.rule.field.replace(/^authorization\./, '');
  const actual = (c.actual ?? '').toString();
  const up = c.rule.operator === '<=' || c.rule.operator === '<';

  if (c.verdict === 'unknown') {
    if (f === 'order_returnable') return 'The return terms aren’t stated, so I can’t confirm this.';
    if (f === 'merchant.familiar') return 'I can’t tell whether you have bought here before.';
    if (f === 'billing_amount_chf') return 'I couldn’t work out the amount.';
    return 'I couldn’t verify this.';
  }
  const ok = c.verdict === 'pass';
  switch (f) {
    case 'billing_amount_chf':
      if (c.rule.scope === 'period') return actual;
      if (!ok && up && typeof c.rule.value === 'number') return `${money(Number(actual))} is over the limit by ${money(Number(actual) - c.rule.value)}.`;
      return `${money(Number(actual))} ${ok ? (up ? 'is within the limit' : 'meets the minimum') : up ? 'is over the limit' : 'is below the minimum'}.`;
    case 'merchant.familiar':
      return actual === 'true' ? 'You have bought here before.' : 'You haven’t bought here before.';
    case 'order_returnable':
      return actual === 'true' ? 'Confirmed: returns are allowed.' : 'Returns are not allowed.';
    // when the rule passed, the title already says it: only add what was actually found
    case 'merchant.merchant_category':
      return ok ? `Matches: ${nice(unique(actual))}.` : `This shop: ${nice(unique(actual))}.`;
    case 'merchant.merchant_country':
      return ok ? `Matches: ${unique(actual)}.` : `This shop is in ${unique(actual)}.`;
    case 'items.item_category':
      return ok ? `Matches: ${nice(unique(actual))}.` : `The items are: ${nice(unique(actual))}.`;
    case 'items.quantity':
      return ok ? `Matches: ${unique(actual)}.` : `Quantity: ${unique(actual)}.`;
    case 'fulfillment_method':
      return ok ? `Matches: ${nice(unique(actual))}.` : `Delivery: ${nice(unique(actual))}.`;
    case 'channel':
      return ok ? `Matches: ${nice(unique(actual))}.` : `Channel: ${nice(unique(actual))}.`;
    case 'recent_attempt_count_10m':
      return `${plural(Number(actual), 'attempt', 'attempts')} in the last 10 minutes.`;
    default:
      return c.detail ?? '';
  }
}

// warnings (shop text with orders, a lookalike seller) come first: they are why a purchase was stopped
const rank = (l: Line) => (l.tag === 'Shop text' || (l.tag === 'Seller' && l.tone === 'no') ? -1 : ORDER[l.tag] ?? 7);
export const byRelevance = (a: Line, b: Line) => rank(a) - rank(b);
