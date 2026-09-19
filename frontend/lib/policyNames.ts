import { api, type Policy } from './api';

// One shared fetch of the policies, so every decision card can show a policy in the customer's own words instead of its id.
let cache: Promise<Policy[]> | null = null;

export function getPolicies(): Promise<Policy[]> {
  return (cache ??= api.policies().catch(() => []));
}

if (typeof window !== 'undefined') window.addEventListener('leash:policies-changed', () => { cache = null; });
