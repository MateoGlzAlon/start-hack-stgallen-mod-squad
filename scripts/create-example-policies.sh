#!/usr/bin/env bash
# Creates every example policy from plans/EXAMPLE_POLICY_CURLS.md through POST /policies (they become active at once).
# Policies that already exist with the same instruction are skipped, so it is safe to run twice.
#   scripts/create-example-policies.sh            (or: make example-policies)
#   API=http://localhost:9090 scripts/create-example-policies.sh
set -euo pipefail
cd "$(dirname "$0")/.."

API=${API:-http://localhost:${BACKEND_PORT:-8080}}
FILE=plans/EXAMPLE_POLICY_CURLS.md

command -v jq >/dev/null || { echo "jq is required (sudo apt install jq)"; exit 1; }
curl -fsS -m 5 "$API/actuator/health" >/dev/null 2>&1 || { echo "Backend not reachable at $API - run 'make provision' first"; exit 1; }

# the sentences are the "instruction" values inside the curl blocks of the markdown file
instructions=$(sed -n 's/.*"instruction": "\([^"]*\)".*/\1/p' "$FILE")
total=$(printf '%s\n' "$instructions" | wc -l | tr -d ' ')
existing=$(curl -fsS "$API/policies" | jq -r '.[] | select(.status == "active") | .instruction')

n=0; created=0; skipped=0
while IFS= read -r sentence; do
  n=$((n + 1))
  echo "[$n/$total] ${sentence:0:100}"
  if grep -qxF -- "$sentence" <<<"$existing"; then
    echo "        already exists, skipped"
    skipped=$((skipped + 1))
    continue
  fi
  body=$(jq -n --arg i "$sentence" '{instruction: $i}')
  res=$(curl -sS -m 90 -X POST "$API/policies" -H 'Content-Type: application/json' -d "$body")
  if ! jq -e '.id' >/dev/null 2>&1 <<<"$res"; then
    echo "        FAILED: $(jq -r '.error.message // .' <<<"$res")"
    exit 1
  fi
  jq -r '"        -> \(.id) (\(.status))  rules: " + ([.hard_rules[] | "\(.field | sub("authorization\\."; "")) \(.operator) \(.value | tostring)"] | join(" | "))
         + (if (.guidance | length) > 0 then "  | guidance: " + (.guidance | join("; ")) else "" end)
         + "  | when unsure: \(.uncertainty_policy)"' <<<"$res"
  created=$((created + 1))
done <<<"$instructions"

echo
echo "created $created, skipped $skipped (already there). Active policies: $(curl -fsS "$API/policies" | jq '[.[] | select(.status == "active")] | length')"
