// Everything goes through /api/* (proxied to the backend by next.config.mjs).

export type Rule = {
  field: string;
  operator: string;
  value: string | number | string[];
  currency?: string | null;
  scope?: string | null;
  period_days?: number | null;
};

export type Policy = {
  id: string;
  status: string; // active | revoked
  instruction: string;
  hard_rules: Rule[];
  uncertainty_policy: 'ask' | 'decline' | 'approve';
  guidance: string[];
  open_questions: string[];
  created_at?: string;
};

export type Evidence = { source: string; fact: string; value?: string | null; note?: string | null };
export type Check = { rule: Rule; verdict: 'pass' | 'fail' | 'unknown'; actual?: string | null; detail?: string | null };

export type State = 'approved' | 'denied' | 'pending_human';

export type Decision = {
  authorization_id: string;
  state: State;
  reason_codes: string[];
  customer_message: string;
  evidence: Evidence[];
  checks: Check[];
  used_llm: boolean;
  decided_by: string;
  policy_id?: string | null;
  decided_at: string;
  merchant_name?: string | null;
  billing_amount_chf: number;
};

export type Status = {
  openai: { configured: boolean; model: string };
  viseca: { configured: boolean; worker_running: boolean };
  fx?: { source: string; to_chf: Record<string, number> };
};

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api${path}`, { ...init, headers: { 'Content-Type': 'application/json' }, cache: 'no-store' });
  } catch {
    throw new Error('Cannot reach the backend');
  }
  const text = await res.text();
  let body: any = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    /* not JSON */
  }
  if (!res.ok) throw new Error(body?.error?.message ?? `Request failed (${res.status})`);
  return body as T;
}

export const api = {
  status: () => req<Status>('/status'),
  policies: () => req<Policy[]>('/policies'),
  createPolicy: (instruction: string) => req<Policy>('/policies', { method: 'POST', body: JSON.stringify({ instruction }) }),
  revokePolicy: (id: string) => req<Policy>(`/policies/${id}`, { method: 'DELETE' }),
  // tighten only: every existing rule is sent back unchanged, and "when unsure" moves to decline
  declineWhenUnsure: (p: Policy) =>
    req<Policy>(`/policies/${p.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ hard_rules: p.hard_rules, uncertainty_policy: 'decline', guidance: p.guidance, open_questions: p.open_questions }),
    }),
  check: (event: unknown) => req<Decision>('/check', { method: 'POST', body: JSON.stringify(event) }),
  decisions: (state?: State) => req<Decision[]>(`/decisions${state ? `?state=${state}` : ''}`),
  resolve: (id: string, decision: 'approve' | 'decline') =>
    req<Decision>(`/check/${encodeURIComponent(id)}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ decision, customer_message: decision === 'approve' ? 'Approved in the app' : 'Declined in the app' }),
    }),
};
