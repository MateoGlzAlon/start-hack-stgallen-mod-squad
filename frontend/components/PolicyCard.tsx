'use client';

import { useState } from 'react';
import type { Policy } from '@/lib/api';
import { UNSURE, describeRule } from '@/lib/format';

export default function PolicyCard({
  p, highlight = false, onRevoke, onTighten,
}: {
  p: Policy;
  highlight?: boolean;
  onRevoke?: () => Promise<void>;
  onTighten?: () => Promise<void>;
}) {
  const [busy, setBusy] = useState('');
  const [confirm, setConfirm] = useState(false);
  const [err, setErr] = useState('');
  const revoked = p.status === 'revoked';

  async function run(name: string, fn?: () => Promise<void>) {
    if (!fn) return;
    setBusy(name);
    setErr('');
    try {
      await fn();
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Something went wrong');
    }
    setBusy('');
    setConfirm(false);
  }

  return (
    <article className={`card p-4 sm:p-5 ${highlight ? 'border-accent' : ''} ${revoked ? 'opacity-60' : ''}`}>
      {highlight && <p className="label-caps mb-2 text-xs text-accent-text">What WatchCat understood</p>}
      <p className="text-[15px] font-medium leading-snug">&ldquo;{p.instruction}&rdquo;</p>

      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        <section>
          <h4 className="label-caps mb-1.5 text-xs text-muted">Checked exactly</h4>
          {p.hard_rules.length === 0 ? (
            <p className="text-sm text-muted">No hard limits</p>
          ) : (
            <ul className="space-y-1.5 text-sm">
              {p.hard_rules.map((r, i) => (
                <li key={i} className="flex gap-2"><span className="text-accent-text">•</span><span>{describeRule(r)}</span></li>
              ))}
            </ul>
          )}
        </section>
        <section>
          <h4 className="label-caps mb-1.5 text-xs text-muted">Judged by AI</h4>
          {p.guidance.length === 0 ? (
            <p className="text-sm text-muted">Nothing extra</p>
          ) : (
            <ul className="space-y-1.5 text-sm">
              {p.guidance.map((g, i) => (
                <li key={i} className="flex gap-2"><span className="text-faint">•</span><span>{g}</span></li>
              ))}
            </ul>
          )}
        </section>
      </div>

      {p.open_questions.length > 0 && (
        <p className="note-ask mt-3">Still unclear: {p.open_questions.join(' \u00b7 ')}</p>
      )}

      <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
        <span className="chip">{revoked ? 'Revoked' : `When unsure: ${UNSURE[p.uncertainty_policy] ?? p.uncertainty_policy}`}</span>
        {!revoked && (
          <div className="flex flex-wrap gap-2">
            {p.uncertainty_policy !== 'decline' && onTighten && (
              <button className="btn-ghost" disabled={busy !== ''} onClick={() => run('tighten', onTighten)}>
                {busy === 'tighten' ? 'Saving…' : 'Decline when unsure'}
              </button>
            )}
            {onRevoke && (
              <button className={confirm ? 'btn-no' : 'btn-ghost'} disabled={busy !== ''} onClick={() => (confirm ? run('revoke', onRevoke) : setConfirm(true))} onBlur={() => setConfirm(false)}>
                {busy === 'revoke' ? 'Revoking…' : confirm ? 'Tap again to revoke' : 'Revoke'}
              </button>
            )}
          </div>
        )}
      </div>
      {err && <p className="note-no mt-2">{err}</p>}
    </article>
  );
}
