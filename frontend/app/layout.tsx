import type { Metadata, Viewport } from 'next';
import './globals.css';
import Nav from '@/components/Nav';

export const metadata: Metadata = {
  title: 'WatchCat',
  description: 'AI shopping, verified before it clears. WatchCat checks every purchase your AI agent proposes against what you actually asked for, and shows its reasoning.',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#FAF7F2' },
    { media: '(prefers-color-scheme: dark)', color: '#17181C' },
  ],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <head>
        {/* Space Grotesk (display), IBM Plex Sans (UI), IBM Plex Mono (prices, currency codes, ids); system fonts if offline */}
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="" />
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500&family=IBM+Plex+Sans:wght@400;500;600&family=Space+Grotesk:wght@500;700&display=swap"
        />
      </head>
      <body>
        <Nav />
        <main className="mx-auto w-full max-w-3xl px-4 pb-28 pt-6 md:pb-14 md:pt-10">{children}</main>
        <footer className="mx-auto hidden max-w-3xl px-4 pb-10 text-xs text-muted md:block">WatchCat &middot; AI shopping, verified before it clears.</footer>
      </body>
    </html>
  );
}
