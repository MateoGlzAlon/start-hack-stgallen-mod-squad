'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, type Policy } from '@/lib/api';
import PolicyCard from '@/components/PolicyCard';


type Q = { question: string; why: string; choices: string[] };
// base = the sentence plus every answer given so far; each round asks what is still missing
type Ask = { base: string; round: number; questions: Q[]; answers: string[] };
const MAX_ROUNDS = 4;

function compose(base: string, qs: Q[], answers: string[]) {
  const pairs = qs.map((q, i) => (answers[i].trim() ? `${q.question} ${answers[i].trim().replace(/[.!?]+$/, '')}.` : '')).filter(Boolean);
  if (!pairs.length) return base;
  return base.includes(' Details: ') ? `${base} ${pairs.join(' ')}` : `${base} Details: ${pairs.join(' ')}`;
}

export default function PoliciesPage() {
  const [policies, setPolicies] = useState<Policy[] | null>(null);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState<'' | 'check' | 'create'>('');
  const [ask, setAsk] = useState<Ask | null>(null);
  const [err, setErr] = useState('');
  const [fresh, setFresh] = useState<string | null>(null);

  const load = useCallback(() => api.policies().then(setPolicies).catch((e) => setErr(e.message)), []);
  useEffect(() => {
    load();
    window.addEventListener('leash:policies-changed', load);
    return () => window.removeEventListener('leash:policies-changed', load);
  }, [load]);

  async function create(instruction: string) {
    setBusy('create');
    setErr('');
    try {
      const p = await api.createPolicy(instruction);
      setText('');
      setAsk(null);
      setFresh(p.id);
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not create the policy');
    }
    setBusy('');
  }

  // a precise sentence is created at once; a vague one first gets a few questions
  async function start() {
    if (!text.trim() || busy) return;
    setBusy('check');
    setErr('');
    setAsk(null);
    try {
      const c = await api.clarify(text.trim());
      if (c.ready) {
        await create(text.trim());
        return;
      }
      setAsk({ base: text.trim(), round: 1, questions: c.questions, answers: c.questions.map(() => '') });
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not check the instruction');
    }
    setBusy('');
  }

  // Continue: add the answers to the text, check it again, and ask again until nothing necessary is missing
  async function next() {
    if (!ask || busy) return;
    const full = compose(ask.base, ask.questions, ask.answers);
    if (full === ask.base) { await create(ask.base); return; } // nothing answered: the customer chose to skip
    setBusy('check');
    setErr('');
    try {
      const c = await api.clarify(full);
      if (c.ready || ask.round >= MAX_ROUNDS) { await create(full); return; }
      setAsk({ base: full, round: ask.round + 1, questions: c.questions, answers: c.questions.map(() => '') });
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not check the instruction');
    }
    setBusy('');
  }

  const active = (policies ?? []).filter((p) => p.status === 'active');
  const revoked = (policies ?? []).filter((p) => p.status === 'revoked');
  // the newest first, and the one just created on top
  const sorted = [...active].sort((a, b) => (a.id === fresh ? -1 : b.id === fresh ? 1 : (b.created_at ?? '').localeCompare(a.created_at ?? '')));

  return (
    <div className="space-y-8">
      <section>
        <h1 className="h-page">What may your agent buy?</h1>
        <p className="mt-1 text-sm text-muted">Say it in your own words. WatchCat turns it into rules and checks every purchase against them.</p>

        <div className="card mt-4 p-4">
          <label htmlFor="instruction" className="sr-only">Instruction</label>
          <textarea
            id="instruction"
            className="field min-h-28 resize-y"
            placeholder="e.g. Buy me black running shoes for up to CHF 200. Ask me when uncertain."
            value={text}
            onChange={(e) => { setText(e.target.value); setAsk(null); }}
            onKeyDown={(e) => { if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') start(); }}
          />
          <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
            <button className="btn-primary w-full sm:w-auto" onClick={start} disabled={busy !== '' || !text.trim()}>
              {busy === 'check' ? 'Checking…' : busy === 'create' ? 'Understanding…' : 'Create policy'}
            </button>
          </div>
          {err && <p className="note-no mt-3">{err}</p>}
        </div>

        {ask && (
          <div className="card mt-4 space-y-5 border-accent p-4 sm:p-5">
            <div>
              <h2 className="h-section">{ask.round === 1 ? 'A few details first' : 'A few more details'}</h2>
              <p className="mt-1 text-sm text-muted">
                {ask.round === 1
                  ? 'Too open to enforce yet. Answer what you can.'
                  : 'Thanks. Still missing:'}
              </p>
              {ask.round > 1 && <p className="mt-2 rounded-lg bg-fg/5 px-3 py-2 text-xs text-muted">So far: {ask.base}</p>}
            </div>
            {ask.questions.map((q, i) => (
              <div key={i}>
                <label className="text-sm font-medium" htmlFor={`q${i}`}>{q.question}</label>
                {q.why && <p className="mt-0.5 text-xs text-muted">{q.why}</p>}
                {q.choices.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {q.choices.map((c) => (
                      <button key={c} type="button" onClick={() => setAsk({ ...ask, answers: ask.answers.map((a, j) => (j === i ? c : a)) })}
                        className={`chip px-3 py-1.5 text-sm transition ${ask.answers[i] === c ? 'border-accent bg-accent/10 text-fg' : 'hover:border-accent hover:text-fg'}`}>{c}</button>
                    ))}
                  </div>
                )}
                <input id={`q${i}`} className="field mt-2" placeholder="Or type your own answer" value={ask.answers[i]}
                  onChange={(e) => setAsk({ ...ask, answers: ask.answers.map((a, j) => (j === i ? e.target.value : a)) })} />
              </div>
            ))}
            <div className="flex flex-col gap-2 sm:flex-row">
              <button className="btn-primary" onClick={next} disabled={busy !== ''}>{busy === 'check' ? 'Checking…' : busy === 'create' ? 'Understanding…' : 'Continue'}</button>
              <button className="btn-ghost" onClick={() => create(compose(ask.base, ask.questions, ask.answers))} disabled={busy !== ''}>Skip the rest and create</button>
            </div>
          </div>
        )}
      </section>

      <section className="space-y-3">
        <div className="flex items-baseline justify-between">
          <h2 className="h-section">Active policies</h2>
          <span className="text-sm text-muted">{policies ? active.length : ''}</span>
        </div>
        {policies === null && !err && <p className="text-sm text-muted">Loading…</p>}
        {policies !== null && active.length === 0 && (
          <div className="card p-6 text-center text-sm text-muted">No policy yet. Your agent may not buy anything. Create one above.</div>
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
