import type { Config } from 'tailwindcss';

// Colours are CSS variables (see globals.css) so light and dark mode need no dark: variants anywhere.
const c = (name: string) => `rgb(var(--${name}) / <alpha-value>)`;

const config: Config = {
  content: ['./app/**/*.{ts,tsx}', './components/**/*.{ts,tsx}'],
  theme: {
    extend: {
      opacity: { 6: '0.06', 8: '0.08', 12: '0.12', 15: '0.15', 85: '0.85' },
      colors: { bg: c('bg'), card: c('card'), fg: c('fg'), muted: c('muted'), line: c('line'), accent: c('accent'), ok: c('ok'), ask: c('ask'), no: c('no') },
    },
  },
  plugins: [],
};

export default config;
