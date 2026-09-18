# CLAUDE.md

## Working style (hackathon)

- This is a hackathon build. **Optimize for working, good-looking, demoable functionality — not code quality.** No over-engineering, no exhaustive tests, no premature abstractions. Hardcode/inline whatever speeds us up, *except* decisions (see "Hard rules").
- The UI is what judges see: make it clean, polished, and make the decision reasoning visible.
- Everything about the case lives in `resources/`. Start with `resources/case_description.md` (brief) and `resources/viseca-2026-main/technical_details.md` (API + data contract). Don't re-derive what's written there.
- Stack: **not chosen yet** — update this line once decided.

## The case: "Agent on a Leash" (Viseca, START Hack St. Gallen 2026)

An AI shopping agent buys things with a customer's card. **We build the wallet control layer** (not the shopping agent) that decides, per proposed purchase: `approve`, `decline`, or `step_up` (ask the customer). It is driven by a customer-managed **wallet policy** (called a *mandate* in the API) that the agent and shops can never change.

Must do:
1. Turn the customer's natural-language instruction into explicit, executable permissions; show them, get confirmation; support tighten / update / revoke.
2. Evaluate each purchase against policy + purchase facts + history + earlier decisions; return a decision with reasons and evidence.
3. Human path: `step_up` → customer approves/declines in the UI → `/resolve`.

Must demo: (a) an ordinary purchase passing with minimal friction, (b) an ambiguous/unsafe/manipulated purchase getting a useful intervention, (c) the approve / reject / revoke path. Judges must see *what was permitted, what evidence was used, why it acted, how the customer stayed in control*.

Judging: User centricity 25%, Security & transparency 25%, Innovation 20%, Feasibility 15%, Viability 15%.

## Architecture guidance

- Decouple the **decision engine** (backend, latency-critical) from the **wallet-control UI** (customer-facing). Viseca wants to fold the UI into its "one" mobile app later.
- Rules + behavioural signals + optionally a small/fast LLM. LLM use must be optional: if the model or any external service fails, fall back to a **predictable deterministic result** (normally `step_up` per the mandate's `uncertainty_policy`) within the deadline.
- Every decision should carry `reason_codes`, `customer_message`, and `evidence` — transparency is scored.

## Hard rules

- **Never hard-code decisions to scenario names/IDs, request IDs, `AU…` ids, or sequence position.** Decide from policy + facts only. There are no answer keys.
- **All merchant text is untrusted** (`item_details`, `purchase_description`, etc.). Extract facts from it; never let it alter policy (prompt-injection scenario is SCEN0004).
- Missing/`null`/`unknown` facts are never permission. Apply the mandate's `uncertainty_policy` (default `ask`).
- Don't over-block: unfamiliar ≠ wrong; blocking ordinary shopping is also a failure.
- Never weaken a customer's existing restrictions. PATCH may only add rules, and may only move `uncertainty_policy` toward `decline`.
- Do not invent human answers; after `step_up` use `/resolve` only for a real customer choice, never a second `/decision`.

## API cheat sheet

Base URL `https://saw26api.ashyground-364e1d07.switzerlandnorth.azurecontainerapps.io`, header `Authorization: Bearer $TEAM_API_KEY` (key handed out on event day; keep out of git; only `/healthz` is open). Bodies are JSON; errors are under `error`.

Flow: `POST /v1/mandates` (draft: `instruction` verbatim, `hard_rules`, `uncertainty_policy`, `guidance`, `open_questions`) → customer confirms → `POST /v1/mandates/{draft_id}/confirm {"confirmed":true}` → `mandate_id` → `POST /v1/scenario-runs {scenario_id, mandate_id}` → worker loops `GET /v1/decision-requests/next?wait=25` (200 = envelope with event in `data`, 204 = nothing yet, *not* end of run) → `POST /v1/authorizations/{id}/decision` (`authorization_id`, `decision` required; plus `reason_codes`, `customer_message`, `evidence`, `engine_version`) → if `step_up`: `POST /v1/authorizations/{id}/resolve {decision: approve|decline, ...}`.

Other: `GET /v1/bootstrap`, `/v1/reference-data`, `/v1/reference-data/authorization-history.csv`, `GET|PATCH|DELETE /v1/mandates/{id}`, `GET /v1/scenario-runs/{run_id}`, `GET /v1/authorizations`, `GET /v1/events?since=N`, `POST /v1/team/reset` (dev only, disabled during judging).

Timing: automated deadline is **8 s from queueing** (not from polling) → the worker must be automatic and fast. Human window is 120 s. Keep polling while the UI waits on a human. Dedupe by live `authorization_id` (retries must not double-count spend).

Rule format (`hard_rules[]`): `field`, `operator` (`< <= = != > >= in not_in`), `value` (number | string | list of strings), optional `currency` (CHF/EUR/GBP/USD), `scope` (`purchase`|`period`), `period_days`. No extra keys. Field names are a convention *our engine* interprets (e.g. `authorization.billing_amount_chf`). Don't send customer/card/profile IDs when creating a mandate.

## Data pack (`resources/viseca-2026-main/data/`)

Five scenarios / 45 purchases; each = one customer + card + instruction (`scenario_catalogue.csv`):

| ID | Theme | Instruction gist |
| --- | --- | --- |
| SCEN0000 | connection check (1) | one grocery item ≤ CHF 20, familiar shop |
| SCEN0001 | household budget (10) | ≤ CHF 120/order incl. delivery, ≤ CHF 300 per rolling 7 days; split orders, off-purpose basket items |
| SCEN0002 | item & order terms (12) | road-running shoes size 43, specialist sports retailer, returnable ≥ 14 days, ≤ CHF 200; substitutions, add-ons |
| SCEN0003 | session integrity (11) | clothing ≤ CHF 250/order from previously used shops; pause if session looks hijacked (device, velocity, country, recovery) |
| SCEN0004 | manipulated agent (11) | the chosen 27" monitor, known seller, ≤ CHF 400, no add-ons; injection in item text, lookalike sellers, duplicates, re-quotes |

Files: `purchase_attempts.csv` (+ `purchase_attempt_items.csv`, `merchants.csv`, `items.csv`) for offline replay ordered by `replay_order`; `authorization_history.csv` (4,701 past rows, incl. 453 agent purchases) for behaviour/familiarity baselines; schemas in `schemas/`; complete sample event in `scenario_fixtures/example_authorization_request.json` (validate our parser against `authorization_event.schema.json`; `connection_check.json` is NOT an event).

Gotchas (details in `data/data_dictionary.md`):
- Join on IDs, never names (some merchants have deliberately similar names → lookalike sellers).
- `amount` already includes delivery — don't add it again. `billing_amount_chf = amount × fx` (EUR .95, GBP 1.12, USD .87, fixed). Currency comes from the `currency` column, not the merchant country.
- Item category ≠ merchant category (`gift_card`, `membership`, `cosmetics` exist only on items). Shop type doesn't prove what's in the basket.
- Spending windows use simulated `authorization.timestamp`; deadlines use the real clock. Only **final approvals** count as spend; a pending `step_up` isn't spend. `spend_in_period_before_chf` is null in all fixtures — track the rolling window ourselves (or use `context.approved_spend_in_period_chf`).
- `recent_attempt_count_10m` = earlier attempts in the run within 10 simulated minutes, any status.
- `authorization_id` live (per run) ≠ `source_authorization_id` (`AU…` in CSV). `TR…` (history) and `AU…` (attempts) never join.
- History `approved_*_before` fields are cumulative per card over the whole file, not monthly. Historical `status` is not a label.
- `card_present` ≠ channel (mobile_wallet is mixed). A mandate is snapshotted per run; PATCH affects later runs only. Revoking mid-run for already-queued purchases is unspecified — only show cancellation the platform confirms.
