'use client';

import { useEffect, useState } from 'react';
import { api } from '@/lib/api';

type Note = { tone: 'ok' | 'no'; text: string };

/** GET /scenarios, then one policy per scenario instruction (the ones that already exist are skipped). */
export default function LoadScenarios() {
  const [busy, setBusy] = useState<string | null>(null);
  const [note, setNote] = useState<Note | null>(null);

  useEffect(() => {
    if (!note) return;
    const t = setTimeout(() => setNote(null), 12000);
    return () => clearTimeout(t);
  }, [note]);

  async function load() {
    setBusy('Loading…');
    setNote(null);
    try {
      const { scenarios, source } = await api.scenarios();
      const have = new Set((await api.policies()).filter((p) => p.status === 'active').map((p) => p.instruction.trim()));
      let created = 0, skipped = 0;
      const failed: string[] = [];
      for (let i = 0; i < scenarios.length; i++) {
        const s = scenarios[i];
        if (have.has(s.cardholder_instruction.trim())) { skipped++; continue; }
        setBusy(`Creating policy ${i + 1} of ${scenarios.length}…`);
        try { await api.createPolicy(s.cardholder_instruction); created++; } catch (e) { failed.push(`${s.scenario_name}: ${e instanceof Error ? e.message : 'failed'}`); }
        window.dispatchEvent(new Event('leash:policies-changed')); // the test section below and the Policies tab pick the new policy up
      }
      const text = `${scenarios.length} scenarios from ${source}: ${created} policies created${skipped ? `, ${skipped} already there` : ''}${failed.length ? `. Failed: ${failed.join('; ')}` : ''}`;
      setNote({ tone: failed.length ? 'no' : 'ok', text });
    } catch (e) {
      setNote({ tone: 'no', text: e instanceof Error ? e.message : 'Could not load the scenarios' });
    }
    setBusy(null);
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-col gap-3 rounded-xl border border-line bg-bg p-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-sm text-muted">
          <b className="font-medium text-fg">First time?</b> Create a policy from each of Viseca&rsquo;s scenarios, so there is something to test.
        </p>
        <button className="btn-primary shrink-0" onClick={load} disabled={busy !== null}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden><path d="M12 4v11m0 0l-4-4m4 4l4-4M5 20h14" /></svg>
          {busy ?? 'Load scenarios'}
        </button>
      </div>
      {note && (
        <p role="status" className={`rounded-xl border px-3 py-2 text-sm ${note.tone === 'ok' ? 'border-ok/30 bg-ok/10 text-ok' : 'border-no/30 bg-no/10 text-no'}`}>{note.text}</p>
      )}
    </div>
  );
}
