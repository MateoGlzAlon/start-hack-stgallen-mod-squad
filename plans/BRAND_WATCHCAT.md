# WatchCat brand

The app is called **WatchCat** ("AI shopping, verified before it clears."). Source: the brand guidelines artifact
<https://claude.ai/artifact/GTD83qDoHcttPfDXeZdz7n> (tokens, logo, rules). The Java package and container names keep the old working name `leash`.

Where it lives in the code: `frontend/tailwind.config.ts` and `frontend/app/globals.css` (tokens), `frontend/components/WatchCatMark.tsx` and `frontend/app/icon.svg` (logo, app icon),
`frontend/public/brand/` (the three official SVGs), `frontend/app/layout.tsx` (fonts, title), `frontend/lib/format.ts` (verdict names and voice).

## Colour (light / dark)

| Token | Light | Dark | Use |
| --- | --- | --- | --- |
| surface-page | `#FAF7F2` | `#17181C` | page background |
| surface-raised | `#FFFFFF` | `#1F2024` | cards, panels |
| ink | `#17181C` | `#FAF7F2` | primary text |
| ink-muted | `#6B6658` | `#A39C8C` | secondary text |
| ink-faint | `#A39C8C` | `#6B6658` | placeholders |
| border | `#E4DFD3` | `#2A2B30` | hairlines (borders, never shadows) |
| accent | `#2FB6A7` | `#3BCBBB` | the "watching" colour: primary buttons, links, focus, verified badge |
| accent-strong | `#1F8A7E` | `#2FB6A7` | hover / active |
| signal-approve | `#2FB6A7` | `#3BCBBB` | Approved (same hue as accent) |
| signal-review | `#F2994A` | `#F2994A` | Needs review |
| signal-block | `#E85C5C` | `#E85C5C` | Blocked |

Two additions for legibility, not in the guidelines: text on a solid accent or signal fill is ink `#17181C`, and small accent-coloured text on the page uses `accent-strong` in light mode (`accent-text`), because teal `#2FB6A7` on cream is only about 2.4:1.

## Type, shape, spacing

- Space Grotesk 700/600 for headings and the wordmark, IBM Plex Sans 400/500/600 for the UI, IBM Plex Mono for prices, currency codes and ids (loaded from Google Fonts).
- Buttons, tabs and eyebrow labels: 13px, 600, uppercase. Body 15px / 1.6.
- Radius: cards 20px, buttons and inputs 12px, pills and verdict badges full. Spacing on a 4px scale.

## Rules

1. Flat, not soft: solid fills and hairline borders. No gradients, shadows or glass.
2. One signal colour at a time. Amber and red only for verdicts, never decoration.
3. Verdict first, reason second: "Blocked — over budget by CHF 14". Real merchant and product names, no hedging once a check has run; if unsure it says "needs your review".
4. The mark is a face, not a badge: shield plus cat. Ink mark on light, reverse mark on dark, never recoloured, never on a shadow, at least 20px (lockup 96px wide).
