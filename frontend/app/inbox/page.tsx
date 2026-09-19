'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, type Decision } from '@/lib/api';
import DecisionCard from '@/components/DecisionCard';

export default function InboxPage() {
  const [items, setItems] = useState<Decision[] | null>(null);
  const [err, setErr] = useState('');

  const load = useCallback(() => api.decisions('pending_human').then((d) => { setItems(d); setErr(''); }).catch((e) => setErr(e.message)), []);
  useEffect(() => {
    load();
    const t = setInterval(load, 4000);
    return () => clearInterval(t);
  }, [load]);

  return (
    <div className="space-y-5">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">Waiting for you</h1>
        <p className="mt-1 text-sm text-muted">Purchases I paused because I could not be sure. Nothing is bought until you say yes.</p>
      </header>

      {err && <p className="text-sm text-no">{err}</p>}
      {items === null && !err && <p className="text-sm text-muted">Loading…</p>}
      {items !== null && items.length === 0 && (
        <div className="card p-8 text-center">
          <p className="font-medium">All clear</p>
          <p className="mt-1 text-sm text-muted">Nothing is waiting for your decision.</p>
        </div>
      )}
      <div className="space-y-3">
        {items?.map((d) => (
          <DecisionCard
            key={d.authorization_id}
            d={d}
            defaultOpen
            onResolve={async (choice) => {
              await api.resolve(d.authorization_id, choice);
              window.dispatchEvent(new Event('leash:changed'));
              await load();
            }}
          />
        ))}
      </div>
    </div>
  );
}
