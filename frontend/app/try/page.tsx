'use client';

import { useState } from 'react';
import { api, type Decision } from '@/lib/api';
import DecisionCard from '@/components/DecisionCard';
import TestPurchases from '@/components/TestPurchases';
import { EMPTY, ITEM_CATEGORIES, PRESETS, SHOP_TYPES, buildEvent, type PurchaseForm } from '@/lib/purchase';

const nice = (s: string) => (s ? s.replace(/_/g, ' ') : 'same as the shop type');

export default function TryPage() {
  const [f, setF] = useState<PurchaseForm>(EMPTY);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [result, setResult] = useState<Decision | null>(null);

  const set = <K extends keyof PurchaseForm>(k: K, v: PurchaseForm[K]) => setF((x) => ({ ...x, [k]: v }));

  async function run(form: PurchaseForm) {
    setBusy(true);
    setErr('');
    setResult(null);
    try {
      const d = await api.check(buildEvent(form));
      setResult(d);
      window.dispatchEvent(new Event('leash:changed'));
      setTimeout(() => document.getElementById('result')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }), 50);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'The check failed');
    }
    setBusy(false);
  }

  const preset = (p: (typeof PRESETS)[number]) => {
    const form = { ...EMPTY, ...p.form };
    setF(form);
    run(form);
  };

  return (
    <div className="space-y-8">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">Try purchases</h1>
        <p className="mt-1 text-sm text-muted">Play the shopping agent and see what your policies say: send Viseca&rsquo;s example purchases, or make up your own below.</p>
      </header>

      <TestPurchases />

      <section>
        <h2 className="text-lg font-semibold">Or make up one purchase</h2>
        <p className="mt-1 text-sm text-muted">The presets assume the example policies.</p>

        <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-3">
          {PRESETS.map((p) => (
            <button key={p.label} type="button" onClick={() => preset(p)} disabled={busy}
              className="card min-h-16 p-3 text-left transition hover:border-accent disabled:opacity-60">
              <span className="block text-sm font-medium leading-tight">{p.label}</span>
              <span className="mt-0.5 block text-xs text-muted">{p.hint}</span>
            </button>
          ))}
        </div>
      </section>

      <section className="card p-4 sm:p-5">
        <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); run(f); }}>
          <div>
            <label className="label" htmlFor="item">Item</label>
            <input id="item" className="field" value={f.item} onChange={(e) => set('item', e.target.value)} required />
          </div>
          <div>
            <label className="label" htmlFor="details">What the shop says about it (untrusted text)</label>
            <textarea id="details" className="field min-h-20" value={f.details} onChange={(e) => set('details', e.target.value)} />
          </div>

          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <div className="col-span-1 sm:col-span-2">
              <label className="label" htmlFor="price">Price</label>
              <input id="price" className="field" type="number" inputMode="decimal" step="0.01" min="0" value={f.price} onChange={(e) => set('price', e.target.value)} required />
            </div>
            <div>
              <label className="label" htmlFor="currency">Currency</label>
              <select id="currency" className="field" value={f.currency} onChange={(e) => set('currency', e.target.value as PurchaseForm['currency'])}>
                {['CHF', 'EUR', 'GBP', 'USD'].map((c) => <option key={c}>{c}</option>)}
              </select>
            </div>
            <div>
              <label className="label" htmlFor="returnable">Returnable</label>
              <select id="returnable" className="field" value={f.returnable} onChange={(e) => set('returnable', e.target.value as PurchaseForm['returnable'])}>
                <option value="true">yes</option><option value="false">no</option><option value="unknown">not stated</option>
              </select>
            </div>
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <label className="label" htmlFor="shopName">Shop name</label>
              <input id="shopName" className="field" value={f.shopName} onChange={(e) => set('shopName', e.target.value)} />
            </div>
            <div>
              <label className="label" htmlFor="shopType">Shop type</label>
              <select id="shopType" className="field" value={f.shopType} onChange={(e) => set('shopType', e.target.value)}>
                {SHOP_TYPES.map((s) => <option key={s} value={s}>{nice(s)}</option>)}
              </select>
            </div>
          </div>

          <details className="rounded-xl border border-line px-3 py-2">
            <summary className="cursor-pointer text-sm text-muted">More details</summary>
            <div className="mt-3 grid gap-3 sm:grid-cols-2">
              <div>
                <label className="label" htmlFor="shopId">Shop id</label>
                <input id="shopId" className="field" value={f.shopId} onChange={(e) => set('shopId', e.target.value)} />
              </div>
              <div>
                <label className="label" htmlFor="card">Card</label>
                <input id="card" className="field" value={f.card} onChange={(e) => set('card', e.target.value)} />
              </div>
              <div>
                <label className="label" htmlFor="itemCategory">Item category</label>
                <select id="itemCategory" className="field" value={f.itemCategory} onChange={(e) => set('itemCategory', e.target.value)}>
                  {ITEM_CATEGORIES.map((c) => <option key={c} value={c}>{nice(c)}</option>)}
                </select>
              </div>
              <div>
                <label className="label" htmlFor="country">Shop country</label>
                <input id="country" className="field" value={f.country} onChange={(e) => set('country', e.target.value)} maxLength={2} />
              </div>
              <div className="sm:col-span-2">
                <label className="label" htmlFor="description">Order description (untrusted text)</label>
                <input id="description" className="field" value={f.description} onChange={(e) => set('description', e.target.value)} />
              </div>
            </div>
          </details>

          <button className="btn-primary w-full sm:w-auto" disabled={busy} type="submit">{busy ? 'Checking…' : 'Check this purchase'}</button>
        </form>
      </section>

      <div id="result" className="scroll-mt-20">
        {err && <p className="card border-no/40 p-4 text-sm text-no">{err}</p>}
        {busy && <p className="text-sm text-muted">Checking against your policies…</p>}
        {result && (
          <DecisionCard
            d={result}
            defaultOpen
            onResolve={async (choice) => {
              setResult(await api.resolve(result.authorization_id, choice));
              window.dispatchEvent(new Event('leash:changed'));
            }}
          />
        )}
      </div>
    </div>
  );
}
