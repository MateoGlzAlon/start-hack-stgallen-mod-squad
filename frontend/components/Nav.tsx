'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useState } from 'react';
import { api, type Status } from '@/lib/api';
import { Bell, List, Play, Shield } from './icons';

const TABS = [
  { href: '/', label: 'Policies', Icon: Shield },
  { href: '/inbox', label: 'Inbox', Icon: Bell },
  { href: '/activity', label: 'Activity', Icon: List },
  { href: '/try', label: 'Try', Icon: Play },
];

function usePending() {
  const [n, setN] = useState(0);
  useEffect(() => {
    let live = true;
    const load = () => api.decisions('pending_human').then((d) => live && setN(d.length)).catch(() => {});
    load();
    const t = setInterval(load, 5000);
    const on = () => load();
    window.addEventListener('leash:changed', on);
    return () => {
      live = false;
      clearInterval(t);
      window.removeEventListener('leash:changed', on);
    };
  }, []);
  return n;
}

function StatusPill() {
  const [s, setS] = useState<Status | null | 'down'>(null);
  useEffect(() => {
    let live = true;
    const load = () => api.status().then((v) => live && setS(v)).catch(() => live && setS('down'));
    load();
    const t = setInterval(load, 30000);
    return () => {
      live = false;
      clearInterval(t);
    };
  }, []);
  if (s === null) return null;
  const down = s === 'down';
  const noAi = !down && !(s as Status).openai.configured;
  const tone = down ? 'bg-no' : noAi ? 'bg-ask' : 'bg-ok';
  const text = down ? 'Backend offline' : noAi ? 'No AI key' : (s as Status).openai.model;
  return (
    <span className="inline-flex items-center gap-2 rounded-full border border-line bg-card px-3 py-1 text-xs text-muted" title={down ? 'The backend does not answer' : 'Backend online'}>
      <span className={`h-2 w-2 rounded-full ${tone}`} />
      {text}
    </span>
  );
}

export default function Nav() {
  const path = usePathname();
  const pending = usePending();
  const active = (href: string) => (href === '/' ? path === '/' : path.startsWith(href));
  const badge = (href: string) =>
    href === '/inbox' && pending > 0 ? (
      <span className="ml-1 inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-ask px-1.5 text-[11px] font-semibold text-black">{pending}</span>
    ) : null;

  return (
    <>
      <header className="sticky top-0 z-20 border-b border-line bg-bg/85 pt-[env(safe-area-inset-top)] backdrop-blur">
        <div className="mx-auto flex h-14 max-w-3xl items-center justify-between gap-4 px-4">
          <Link href="/" className="flex items-center gap-2 font-semibold tracking-tight">
            <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-accent text-white"><Shield width={16} height={16} /></span>
            Leash
          </Link>
          <nav className="hidden items-center gap-1 md:flex">
            {TABS.map(({ href, label }) => (
              <Link key={href} href={href} className={`rounded-lg px-3 py-1.5 text-sm transition ${active(href) ? 'bg-fg/8 font-medium text-fg' : 'text-muted hover:text-fg'}`}>
                {label}
                {badge(href)}
              </Link>
            ))}
          </nav>
          <StatusPill />
        </div>
      </header>

      <nav className="fixed inset-x-0 bottom-0 z-20 border-t border-line bg-bg/90 pb-[env(safe-area-inset-bottom)] backdrop-blur md:hidden">
        <ul className="mx-auto grid max-w-3xl grid-cols-4">
          {TABS.map(({ href, label, Icon }) => (
            <li key={href}>
              <Link href={href} className={`relative flex min-h-14 flex-col items-center justify-center gap-0.5 text-[11px] ${active(href) ? 'text-accent' : 'text-muted'}`}>
                <Icon />
                {label}
                {href === '/inbox' && pending > 0 && (
                  <span className="absolute right-[calc(50%-22px)] top-1.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-ask px-1 text-[10px] font-semibold text-black">{pending}</span>
                )}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
    </>
  );
}
