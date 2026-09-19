import type { Rule, State } from './api';

const OPS: Record<string, string> = { '<=': 'at most', '<': 'under', '>=': 'at least', '>': 'over', '=': 'is', '!=': 'is not', in: 'is one of', not_in: 'is none of' };

const nice = (v: unknown) => String(v).replace(/_/g, ' ');
const list = (v: Rule['value']) => (Array.isArray(v) ? v.map(nice).join(', ') : nice(v));
export const money = (n: number, cur = 'CHF') => `${cur} ${n.toLocaleString('en-CH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

/** A hard rule as a sentence a customer would say. */
export function describeRule(r: Rule): string {
  const f = r.field.replace(/^authorization\./, '');
  const op = OPS[r.operator] ?? r.operator;
  const v = r.value;
  const yes = String(v).toLowerCase() === 'true';
  switch (f) {
    case 'billing_amount_chf': {
      const amount = `${r.currency ?? 'CHF'} ${v}`;
      return r.scope === 'period' ? `Total over ${r.period_days ?? '?'} days: ${op} ${amount}` : `Each order: ${op} ${amount}`;
    }
    case 'merchant.familiar':
      return yes ? 'Only shops I have used before' : 'Only shops I have not used before';
    case 'merchant.merchant_category':
      return `Shop type ${op} ${list(v)}`;
    case 'merchant.merchant_country':
      return `Shop country ${op} ${list(v)}`;
    case 'items.item_category':
      return r.operator === 'not_in' ? `No item may be: ${list(v)}` : `Every item must be: ${list(v)}`;
    case 'items.quantity':
      return `Quantity per item ${op} ${v}`;
    case 'fulfillment_method':
      return `Delivery method ${op} ${list(v)}`;
    case 'order_returnable':
      return yes ? 'The order can be returned' : 'The order is not returnable';
    case 'order_cancellable':
      return yes ? 'The order can be cancelled' : 'The order is not cancellable';
    case 'channel':
      return `Channel ${op} ${list(v)}`;
    case 'recent_attempt_count_10m':
      return `Attempts in the last 10 minutes ${op} ${v}`;
    default:
      return `${f} ${op} ${list(v)}`;
  }
}

export const VERDICT: Record<State, { label: string; short: string; tone: 'ok' | 'ask' | 'no' }> = {
  approved: { label: 'Approved', short: 'Approved', tone: 'ok' },
  denied: { label: 'Blocked', short: 'Blocked', tone: 'no' },
  pending_human: { label: 'Needs your review', short: 'Needs review', tone: 'ask' },
};

export function timeAgo(iso: string): string {
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 10) return 'just now';
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
}

export const UNSURE: Record<string, string> = { ask: 'Ask me', decline: 'Decline', approve: 'Approve' };

/** The brand voice: verdict first, then the reason ("Blocked — Order total is 459.00, needs at most CHF 400.00."). */
export const stripVerdict = (msg: string) => {
  const m = voice(msg).replace(/^(Approved|Blocked|Needs your review)\s*[:.,\u2014-]\s*/i, '');
  return m.charAt(0).toUpperCase() + m.slice(1);
};
export const voice = (msg: string) =>
  msg
    .replace(/\s*\(POL-[A-Za-z0-9]+\)/g, '') // a policy id must never reach the customer
    .replace(/POL-[A-Za-z0-9]+/g, 'one of your policies')
    .replace(/^Declined[:,]?\s+/i, 'Blocked \u2014 ');
