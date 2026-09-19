'use client';

import { useState } from 'react';
import type { Decision } from '@/lib/api';
import { VERDICT, money, timeAgo } from '@/lib/format';
import DecisionCard from './DecisionCard';

const DOT = { ok: 'bg-ok', ask: 'bg-ask', no: 'bg-no' };

/** One decision as a compact row; tap to open the full card with the evidence. */
export default function DecisionRow({ d, onResolve, defaultOpen = false }: { d: Decision; onResolve?: (c: 'approve' | 'decline') => Promise<void>; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <li>
      <button onClick={() => setOpen(!open)} aria-expanded={open} className="card flex w-full items-center gap-3 p-3 text-left transition hover:border-accent sm:p-4">
        <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${DOT[VERDICT[d.state].tone]}`} />
        <span className="min-w-0 flex-1">
          <span className="flex items-baseline justify-between gap-3">
            <span className="truncate text-sm font-medium">{d.merchant_name || 'Purchase'}</span>
            <span className="shrink-0 text-sm">{money(d.billing_amount_chf)}</span>
          </span>
          <span className="mt-0.5 flex items-baseline justify-between gap-3 text-xs text-muted">
            <span className="truncate">{d.customer_message.toLowerCase().startsWith(VERDICT[d.state].short.toLowerCase()) ? d.customer_message : `${VERDICT[d.state].short} · ${d.customer_message}`}</span>
            <span className="shrink-0">{timeAgo(d.decided_at)}</span>
          </span>
        </span>
      </button>
      {open && <DecisionCard d={d} defaultOpen onResolve={onResolve} className="mt-2" />}
    </li>
  );
}
