'use client';

import { useEffect, useState } from 'react';
import type { Decision, Policy } from '@/lib/api';
import { VERDICT, describeRule, money, timeAgo } from '@/lib/format';
import { byRelevance, describeEvidence, explainCheck, humanize, policyName, type Line } from '@/lib/evidence';
import { getPolicies } from '@/lib/policyNames';
import { Check, Cross, Question } from './icons';

const TONE = {
  ok: { text: 'text-ok', bg: 'bg-ok/12', border: 'border-ok/30' },
  ask: { text: 'text-ask', bg: 'bg-ask/12', border: 'border-ask/30' },
  no: { text: 'text-no', bg: 'bg-no/12', border: 'border-no/30' },
};

const LINE_TONE: Record<Line['tone'], string> = {
  ok: 'bg-ok/12 text-ok',
  no: 'bg-no/12 text-no',
  ask: 'bg-ask/12 text-ask',
  muted: 'bg-fg/6 text-muted',
};

export function VerdictBadge({ state }: { state: Decision['state'] }) {
  const v = VERDICT[state];
  const t = TONE[v.tone];
  const Icon = v.tone === 'ok' ? Check : v.tone === 'no' ? Cross : Question;
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-sm font-medium ${t.bg} ${t.text}`}>
      <Icon width={16} height={16} />
      {v.label}
    </span>
  );
}

function Mark({ verdict }: { verdict: 'pass' | 'fail' | 'unknown' }) {
  if (verdict === 'pass') return <span className="mt-0.5 text-ok"><Check width={16} height={16} /></span>;
  if (verdict === 'fail') return <span className="mt-0.5 text-no"><Cross width={16} height={16} /></span>;
  return <span className="mt-0.5 text-ask"><Question width={16} height={16} /></span>;
}

function EvidenceRow({ line }: { line: Line }) {
  return (
    <li className="flex gap-3 py-2 text-sm">
      <span className={`mt-0.5 h-fit w-[4.5rem] shrink-0 rounded-md px-1.5 py-0.5 text-center text-[11px] font-medium ${LINE_TONE[line.tone]}`}>{line.tag}</span>
      <span className="min-w-0 break-words">
        {line.text}
        {line.sub ? <span className="block text-xs text-muted">{line.sub}</span> : null}
      </span>
    </li>
  );
}

export default function DecisionCard({
  d, onResolve, defaultOpen = false, className = '',
}: {
  d: Decision;
  onResolve?: (choice: 'approve' | 'decline') => Promise<void>;
  defaultOpen?: boolean;
  className?: string;
}) {
  const [busy, setBusy] = useState<'approve' | 'decline' | null>(null);
  const [err, setErr] = useState('');
  const t = TONE[VERDICT[d.state].tone];
  const [policies, setPolicies] = useState<Policy[]>([]);
  useEffect(() => {
    let live = true;
    getPolicies().then((p) => live && setPolicies(p));
    return () => { live = false; };
  }, []);
  const nameOf = (id: string) => policyName(policies.find((x) => x.id === id));
  // the rule results are shown above; the model also echoes raw facts ("item_name: ...", "authorization.order_returnable: unknown"): noise here
  const looked = d.evidence
    .filter((e) => e.source !== 'rule' && !(e.source === 'model' && /^[a-z_]+(\.[a-z_]+)*: /.test(e.fact)))
    .map((e) => describeEvidence(e, nameOf))
    .sort(byRelevance);

  async function resolve(choice: 'approve' | 'decline') {
    if (!onResolve) return;
    setBusy(choice);
    setErr('');
    try {
      await onResolve(choice);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Something went wrong');
      setBusy(null);
    }
  }

  return (
    <article className={`card overflow-hidden border-l-4 ${t.border} ${className}`}>
      <div className="space-y-3 p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <VerdictBadge state={d.state} />
          <span className="text-sm text-muted">
            {d.merchant_name ? `${d.merchant_name} · ` : ''}
            <span className="font-medium text-fg">{money(d.billing_amount_chf)}</span>
          </span>
        </div>

        <p className="text-[15px] leading-relaxed">{d.customer_message}</p>

        {d.reason_codes.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {d.reason_codes.map((c) => (
              <span key={c} className="chip">{humanize(c)}</span>
            ))}
          </div>
        )}

        {d.state === 'pending_human' && onResolve && (
          <div className="grid grid-cols-2 gap-2 pt-1">
            <button className="btn-ok" disabled={busy !== null} onClick={() => resolve('approve')}>
              <Check width={18} height={18} /> {busy === 'approve' ? 'Approving…' : 'Approve'}
            </button>
            <button className="btn-no" disabled={busy !== null} onClick={() => resolve('decline')}>
              <Cross width={18} height={18} /> {busy === 'decline' ? 'Declining…' : 'Decline'}
            </button>
          </div>
        )}
        {err && <p className="text-sm text-no">{err}</p>}
      </div>

      <details className="group border-t border-line" open={defaultOpen}>
        <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between px-4 text-sm text-muted hover:text-fg">
          <span>Why · evidence and checks</span>
          <span className="transition group-open:rotate-90"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 6l6 6-6 6" /></svg></span>
        </summary>
        <div className="space-y-4 px-4 pb-4">
          {d.checks.length > 0 && (
            <section>
              <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Your rules</h4>
              <ul className="divide-y divide-line">
                {d.checks.map((c, i) => (
                  <li key={i} className="flex gap-3 py-2 text-sm">
                    <Mark verdict={c.verdict} />
                    <span className="min-w-0">
                      {describeRule(c.rule)}
                      <span className="block break-words text-xs text-muted">{explainCheck(c)}</span>
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          )}
          {looked.length > 0 && (
            <section>
              <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">What I looked at</h4>
              <ul className="divide-y divide-line">
                {looked.map((line, i) => <EvidenceRow key={i} line={line} />)}
              </ul>
            </section>
          )}
          <p className="text-xs text-muted">
            {d.decided_by === 'customer' ? 'You made this decision' : d.used_llm ? 'Decided automatically, with the AI\u2019s help' : 'Decided automatically by your rules'}
            {d.policy_id && nameOf(d.policy_id) ? ` · under \u201c${nameOf(d.policy_id)}\u201d` : ''} · {timeAgo(d.decided_at)}
          </p>
        </div>
      </details>
    </article>
  );
}
