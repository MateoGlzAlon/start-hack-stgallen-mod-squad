'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, type Decision, type State } from '@/lib/api';
import DecisionCard from '@/components/DecisionCard';
import { VERDICT, money, stripVerdict, timeAgo } from '@/lib/format';

const FILTERS: { key: 'all' | State; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'approved', label: 'Approved' },
  { key: 'denied', label: 'Blocked' },
  { key: 'pending_human', label: 'Needs review' },
];

const DOT = { ok: 'bg-ok', ask: 'bg-ask', no: 'bg-no' };

export default function ActivityPage() {
  const [items, setItems] = useState<Decision[] | null>(null);
  const [filter, setFilter] = useState<'all' | State>('all');
  const [open, setOpen] = useState<string | null>(null);
  const [err, setErr] = useState('');

  const load = useCallback(() => api.decisions().then((d) => { setItems(d); setErr(''); }).catch((e) => setErr(e.message)), []);
  useEffect(() => {
    load();
    const t = setInterval(load, 6000);
    return () => clearInterval(t);
  }, [load]);

  const shown = (items ?? []).filter((d) => filter === 'all' || d.state === filter);

  return (
    <div className="space-y-5">
      <header>
        <h1 className="h-page">Activity</h1>
        <p className="mt-1 text-sm text-muted">Every purchase your agent proposed, with the reasons.</p>
      </header>

      <div className="flex gap-2 overflow-x-auto pb-1">
        {FILTERS.map((f) => (
          <button key={f.key} onClick={() => setFilter(f.key)}
            className={`chip shrink-0 px-3 py-1.5 text-sm transition ${filter === f.key ? 'border-accent bg-accent/10 text-fg' : 'hover:text-fg'}`}>
            {f.label}
          </button>
        ))}
      </div>

      {err && <p className="note-no">{err}</p>}
      {items === null && !err && <p className="text-sm text-muted">Loading…</p>}
      {items !== null && shown.length === 0 && <div className="card p-8 text-center text-sm text-muted">Nothing here yet. Try a purchase first.</div>}

      <ul className="space-y-2">
        {shown.map((d) => {
          const isOpen = open === d.authorization_id;
          return (
            <li key={d.authorization_id}>
              <button
                onClick={() => setOpen(isOpen ? null : d.authorization_id)}
                className="card flex w-full items-center gap-3 p-3 text-left transition hover:border-accent sm:p-4"
                aria-expanded={isOpen}
              >
                <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${DOT[VERDICT[d.state].tone]}`} />
                <span className="min-w-0 flex-1">
                  <span className="flex items-baseline justify-between gap-3">
                    <span className="truncate text-sm font-medium">{d.merchant_name || 'Purchase'}</span>
                    <span className="shrink-0 font-mono text-sm">{money(d.billing_amount_chf)}</span>
                  </span>
                  <span className="mt-0.5 flex items-baseline justify-between gap-3 text-xs text-muted">
                    <span className="truncate">{VERDICT[d.state].short} · {stripVerdict(d.customer_message)}</span>
                    <span className="shrink-0">{timeAgo(d.decided_at)}</span>
                  </span>
                </span>
              </button>
              {isOpen && <DecisionCard d={d} defaultOpen className="mt-2" />}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
