'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, type Policy } from '@/lib/api';
import PolicyCard from '@/components/PolicyCard';

const EXAMPLES = [
  'Buy me black running shoes for up to CHF 200. Ask me when uncertain.',
  'Buy one ordinary grocery item for CHF 20 or less from a shop I use regularly. Ask me when uncertain.',
  'Order our household groceries for delivery. Keep each order at or below CHF 120 including delivery, and keep the total across any seven days at or below CHF 300. Ask me when uncertain.',
  'Only buy groceries, never gift cards, cosmetics or memberships, at most CHF 50 per order. If you are unsure, decline.',
];

export default function PoliciesPage() {
  const [policies, setPolicies] = useState<Policy[] | null>(null);
  const [text, setText] = useState('');
  const [creating, setCreating] = useState(false);
  const [err, setErr] = useState('');
  const [fresh, setFresh] = useState<string | null>(null);

  const load = useCallback(() => api.policies().then(setPolicies).catch((e) => setErr(e.message)), []);
  useEffect(() => {
    load();
    window.addEventListener('leash:policies-changed', load);
    return () => window.removeEventListener('leash:policies-changed', load);
  }, [load]);

  async function create() {
    if (!text.trim() || creating) return;
    setCreating(true);
    setErr('');
    try {
      const p = await api.createPolicy(text.trim());
      setText('');
      setFresh(p.id);
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not create the policy');
    }
    setCreating(false);
  }

  const active = (policies ?? []).filter((p) => p.status === 'active');
  const revoked = (policies ?? []).filter((p) => p.status === 'revoked');
  // the newest first, and the one just created on top
  const sorted = [...active].sort((a, b) => (a.id === fresh ? -1 : b.id === fresh ? 1 : (b.created_at ?? '').localeCompare(a.created_at ?? '')));

  return (
    <div className="space-y-8">
      <section>
        <h1 className="text-2xl font-semibold tracking-tight">What may your agent buy?</h1>
        <p className="mt-1 text-sm text-muted">Say it in your own words. I turn it into rules, show you what I understood, and check every purchase against it.</p>

        <div className="card mt-4 p-4">
          <label htmlFor="instruction" className="sr-only">Instruction</label>
          <textarea
            id="instruction"
            className="field min-h-28 resize-y"
            placeholder="e.g. Buy me black running shoes for up to CHF 200. Ask me when uncertain."
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => { if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') create(); }}
          />
          <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-wrap gap-1.5">
              {EXAMPLES.map((ex, i) => (
                <button key={i} type="button" className="chip transition hover:border-accent hover:text-fg" onClick={() => setText(ex)}>
                  Example {i + 1}
                </button>
              ))}
            </div>
            <button className="btn-primary w-full sm:w-auto" onClick={create} disabled={creating || !text.trim()}>
              {creating ? 'Understanding…' : 'Create policy'}
            </button>
          </div>
          {err && <p className="mt-3 text-sm text-no">{err}</p>}
        </div>
      </section>

      <section className="space-y-3">
        <div className="flex items-baseline justify-between">
          <h2 className="text-lg font-semibold">Active policies</h2>
          <span className="text-sm text-muted">{policies ? active.length : ''}</span>
        </div>
        {policies === null && !err && <p className="text-sm text-muted">Loading…</p>}
        {policies !== null && active.length === 0 && (
          <div className="card p-6 text-center text-sm text-muted">No policy yet, so your agent may not buy anything. Create one above.</div>
        )}
        {sorted.map((p) => (
          <PolicyCard
            key={p.id}
            p={p}
            highlight={p.id === fresh}
            onRevoke={async () => { await api.revokePolicy(p.id); await load(); }}
            onTighten={async () => { await api.declineWhenUnsure(p); await load(); }}
          />
        ))}
        {revoked.length > 0 && (
          <details className="pt-2">
            <summary className="cursor-pointer text-sm text-muted hover:text-fg">Revoked ({revoked.length})</summary>
            <div className="mt-3 space-y-3">{revoked.map((p) => <PolicyCard key={p.id} p={p} />)}</div>
          </details>
        )}
      </section>
    </div>
  );
}
