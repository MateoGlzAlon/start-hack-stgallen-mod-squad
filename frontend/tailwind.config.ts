import type { Config } from 'tailwindcss';

// WatchCat design tokens as CSS variables (see globals.css), so light and dark mode need no dark: variants anywhere.
const c = (name: string) => `rgb(var(--${name}) / <alpha-value>)`;

const config: Config = {
  content: ['./app/**/*.{ts,tsx}', './components/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        bg: c('bg'),                 // surface-page
        card: c('card'),             // surface-raised
        inverse: c('inverse'),       // surface-inverse
        fg: c('fg'),                 // ink
        muted: c('muted'),           // ink-muted
        faint: c('faint'),           // ink-faint
        line: c('line'),             // border
        accent: c('accent'),
        'accent-strong': c('accent-strong'),
        'accent-text': c('accent-text'), // accent that is readable as small text on the page
        'on-accent': c('on-accent'),     // text on a solid accent / signal fill
        'on-accent-strong': c('on-accent-strong'),
        ok: c('ok'),                 // signal-approve
        ask: c('ask'),               // signal-review
        no: c('no'),                 // signal-block
      },
      fontFamily: {
        display: ["'Space Grotesk'", 'system-ui', 'sans-serif'],
        sans: ["'IBM Plex Sans'", 'system-ui', 'sans-serif'],
        mono: ["'IBM Plex Mono'", 'ui-monospace', 'monospace'],
      },
      borderRadius: { card: '20px' }, // radius-lg; radius-md (12px) = rounded-xl, radius-full = rounded-full
      opacity: { 6: '0.06', 8: '0.08', 12: '0.12', 15: '0.15', 85: '0.85' },
    },
  },
  plugins: [],
};

export default config;
