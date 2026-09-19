'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, type Decision, type Policy, type Scenario } from '@/lib/api';
import DecisionRow from './DecisionRow';
import LoadScenarios from './LoadScenarios';

/** Sends a scenario's example purchase attempts (from the data pack) to the engine, one by one, like a shopping agent would. */
export default function TestPurchases() {
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [sel, setSel] = useState('');
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [results, setResults] = useState<Decision[]>([]);
  const [err, setErr] = useState('');

  const loadPolicies = useCallback(() => api.policies().then(setPolicies).catch(() => {}), []);
  useEffect(() => {
    api.scenarios().then((r) => { setScenarios(r.scenarios); setSel((s) => s || r.scenarios[0]?.scenario_id || ''); }).catch((e) => setErr(e.message));
    loadPolicies();
    window.addEventListener('leash:policies-changed', loadPolicies);
    return () => window.removeEventListener('leash:policies-changed', loadPolicies);
  }, [loadPolicies]);

  const scenario = scenarios.find((s) => s.scenario_id === sel);
  // the policy made from this scenario's own instruction, like Viseca's run would use; otherwise every active policy is tried
  const policy = policies.find((p) => p.status === 'active' && p.instruction.trim() === scenario?.cardholder_instruction.trim());

  async function run() {
    if (!sel || running) return;
    setRunning(true);
    setErr('');
    setResults([]);
    setProgress(null);
    try {
      const { attempts } = await api.attempts(sel);
      const stamp = Date.now().toString(36); // new ids and a new run id, so the test can be repeated
      setProgress({ done: 0, total: attempts.length });
      for (let i = 0; i < attempts.length; i++) {
        const ev = JSON.parse(JSON.stringify(attempts[i]));
        const a = ev.authorization;
        a.authorization_id = `${a.authorization_id}-${stamp}`;
        if (a.related_authorization_id) a.related_authorization_id = `${a.related_authorization_id}-${stamp}`;
        const d = await api.check(ev, { policyId: policy?.id, runId: `test-${stamp}` });
        setResults((r) => [...r, d]);
        setProgress({ done: i + 1, total: attempts.length });
        window.dispatchEvent(new Event('leash:changed'));
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'The test failed');
    }
    setRunning(false);
  }

  const count = (s: Decision['state']) => results.filter((d) => d.state === s).length;

  return (
    <section className="card space-y-4 p-4 sm:p-5">
      <div>
        <h2 className="h-section">Test your policies with example purchases</h2>
        <p className="mt-1 text-sm text-muted">
          Pick a scenario. Its example purchase attempts (the ones Viseca&rsquo;s sandbox replays) are sent to the engine one by one, like a shopping agent would.
        </p>
      </div>

      <LoadScenarios />

      <div className="grid gap-3 sm:grid-cols-[1fr_auto] sm:items-end">
        <div>
          <label className="label" htmlFor="scenario">Scenario</label>
          <select id="scenario" className="field" value={sel} onChange={(e) => { setSel(e.target.value); setResults([]); setProgress(null); }} disabled={running}>
            {scenarios.map((s) => <option key={s.scenario_id} value={s.scenario_id}>{s.scenario_name} ({s.event_count} {s.event_count === 1 ? 'purchase' : 'purchases'})</option>)}
          </select>
        </div>
        <button className="btn-primary w-full sm:w-auto" onClick={run} disabled={running || !sel}>
          {running ? `Testing ${progress?.done ?? 0}/${progress?.total ?? '…'}` : `Test with ${scenario?.event_count ?? ''} example ${scenario?.event_count === 1 ? 'purchase' : 'purchases'}`}
        </button>
      </div>

      {scenario && (
        <p className="text-sm text-muted">
          {policy
            ? <>Checked against the policy made from &ldquo;{scenario.cardholder_instruction}&rdquo;</>
            : <>No policy was made from this scenario yet (press <b className="font-medium text-fg">Load scenarios</b> above), so every active policy is tried instead.</>}
        </p>
      )}
      {err && <p className="note-no">{err}</p>}

      {(progress || results.length > 0) && (
        <div className="space-y-3">
          <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted">
            <span className="inline-flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-ok" /><b className="font-mono font-medium text-fg">{count('approved')}</b> approved</span>
            <span className="inline-flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-no" /><b className="font-mono font-medium text-fg">{count('denied')}</b> blocked</span>
            <span className="inline-flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-ask" /><b className="font-mono font-medium text-fg">{count('pending_human')}</b> need review</span>
            {progress && progress.done < progress.total && <span>{progress.total - progress.done} to go</span>}
          </div>
          <ul className="space-y-2">
            {results.map((d) => (
              <DecisionRow
                key={d.authorization_id}
                d={d}
                onResolve={async (choice) => {
                  const nd = await api.resolve(d.authorization_id, choice);
                  setResults((r) => r.map((x) => (x.authorization_id === nd.authorization_id ? nd : x)));
                  window.dispatchEvent(new Event('leash:changed'));
                }}
              />
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
