#!/usr/bin/env bash
#
# One-command burst test reproducing every invariant/gate from the brief against a running instance.
#
# NOTE on seeding: the API is peer-to-peer only (POST /wallets always starts a wallet at 0, and the
# only way a balance ever changes is via POST /transfers). There is deliberately no mint/deposit
# endpoint — it isn't part of the spec. So the very first balance in the system has to come from
# somewhere outside the API: this script seeds it with one direct SQL UPDATE against the wallets
# table via $DATABASE_URL, then does EVERYTHING else — every invariant this script actually checks —
# through the public HTTP API. That single seed statement is the only thing here that isn't an API
# call, and it never touches the transfers table.
#
# Portability note: this deliberately avoids `xargs -I{}` for anything beyond a fixed one-line
# command — BSD xargs (macOS) silently fails ("command line cannot be assembled") on the longer
# substituted commands GNU xargs tolerates. Instead, arguments are passed positionally
# (`xargs -n1 ... bash -c '...' _`) and shared values travel via exported environment variables,
# which works identically on both.
#
# Usage:
#   ./burst.sh                                    # against a local docker-compose instance
#   BASE_URL=https://your-app.example.com \
#   DATABASE_URL="postgresql://user:pass@host:5432/db" ./burst.sh   # against a deployed instance
#
set -uo pipefail

export BASE_URL="${BASE_URL:-http://localhost:8080}"
export DATABASE_URL="${DATABASE_URL:-postgresql://wallet:wallet@localhost:5432/wallet}"
export TOKEN_A="${TOKEN_A:-dev-token-alice}"
export TOKEN_B="${TOKEN_B:-dev-token-bob}"
export TOKEN_C="${TOKEN_C:-dev-token-carol}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

pass=0
fail=0
ok()  { printf '  OK   - %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  FAIL - %s\n' "$1"; fail=$((fail + 1)); }

for dep in curl jq psql xargs; do
  command -v "$dep" >/dev/null 2>&1 || { echo "missing dependency: $dep" >&2; exit 1; }
done

create_wallet() {
  curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $1" | jq -r .wallet_id
}

balance_of() {
  curl -s "$BASE_URL/wallets/$1" -H "Authorization: Bearer $2" | jq -r .balance_paise
}

seed_balance() {
  psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -q \
    -c "UPDATE wallets SET balance_paise = $2 WHERE id = '$1'" >/dev/null
}

post_transfer_status() {
  # post_transfer_status <token> <from> <to> <amount_paise> <idempotency_key> <out_body_file>
  curl -s -o "$6" -w '%{http_code}' -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"from\":\"$2\",\"to\":\"$3\",\"amount_paise\":$4,\"idempotency_key\":\"$5\"}"
}

echo "== Gate 1: 50 concurrent POST /wallets for a fresh user -> exactly one wallet =="
IDS_FILE="$TMP_DIR/gate1_ids.txt"
: > "$IDS_FILE"
export IDS_FILE
seq 1 50 | xargs -P50 -n1 bash -c '
  curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $TOKEN_A" | jq -r .wallet_id >> "$IDS_FILE"
' _
distinct=$(sort -u "$IDS_FILE" | grep -c . || true)
if [ "$distinct" = "1" ]; then
  ok "50 concurrent get-or-create -> 1 distinct wallet_id ($(head -1 "$IDS_FILE"))"
else
  bad "50 concurrent get-or-create -> $distinct distinct wallet ids (expected 1)"
fi

echo
echo "== Setup: two funded wallets for the transfer gates =="
WALLET_A=$(create_wallet "$TOKEN_A")
WALLET_B=$(create_wallet "$TOKEN_B")
seed_balance "$WALLET_A" 1000000
echo "  wallet A=$WALLET_A funded to 1,000,000 paise; wallet B=$WALLET_B starts at 0"
export WALLET_A WALLET_B

echo
echo "== Gate 2: 30 concurrent identical transfers (same idempotency key) -> exactly one movement =="
GATE2_DIR="$TMP_DIR/gate2"; mkdir -p "$GATE2_DIR"
export GATE2_DIR
seq 1 30 | xargs -P30 -n1 bash -c '
  i="$1"
  status=$(curl -s -o "$GATE2_DIR/$i.body" -w "%{http_code}" -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $TOKEN_A" -H "Content-Type: application/json" \
    -d "{\"from\":\"$WALLET_A\",\"to\":\"$WALLET_B\",\"amount_paise\":5000,\"idempotency_key\":\"storm-key\"}")
  echo "$status" > "$GATE2_DIR/$i.status"
' _
distinct_transfer_ids=$(for f in "$GATE2_DIR"/*.body; do jq -r .transfer_id "$f" 2>/dev/null; done | sort -u | grep -c . || true)
distinct_statuses=$(cat "$GATE2_DIR"/*.status | sort -u | grep -c . || true)
balance_a_after_storm=$(balance_of "$WALLET_A" "$TOKEN_A")
if [ "$distinct_transfer_ids" = "1" ] && [ "$distinct_statuses" = "1" ] && [ "$balance_a_after_storm" = "995000" ]; then
  ok "30 concurrent identical transfers -> 1 transfer id, 1 status code, exactly one 5000-paise debit"
else
  bad "30 concurrent identical transfers -> $distinct_transfer_ids transfer id(s), $distinct_statuses status code(s), balance_a=$balance_a_after_storm (expected 995000)"
fi

echo
echo "== Gate 2b: same idempotency key + different body -> 409 =="
post_transfer_status "$TOKEN_A" "$WALLET_A" "$WALLET_B" 1000 "conflict-key" "$TMP_DIR/c1.body" > /dev/null
status2=$(post_transfer_status "$TOKEN_A" "$WALLET_A" "$WALLET_B" 2000 "conflict-key" "$TMP_DIR/c2.body")
if [ "$status2" = "409" ]; then
  ok "same key + different body -> 409"
else
  bad "same key + different body -> $status2 (expected 409)"
fi

echo
echo "== Gate 3: conservation + no-overdraft under concurrent contention (incl. A->B and B->A) =="
WALLET_C=$(create_wallet "$TOKEN_C")
seed_balance "$WALLET_B" 50000
seed_balance "$WALLET_C" 50000
export WALLET_C
total_before=$(( $(balance_of "$WALLET_A" "$TOKEN_A") + $(balance_of "$WALLET_B" "$TOKEN_B") + $(balance_of "$WALLET_C" "$TOKEN_C") ))

GATE3_DIR="$TMP_DIR/gate3"; mkdir -p "$GATE3_DIR"
export GATE3_DIR
seq 1 90 | xargs -P30 -n1 bash -c '
  i="$1"
  case $(( i % 4 )) in
    0) FROM="$WALLET_A"; TO="$WALLET_B"; TOKEN="$TOKEN_A" ;;
    1) FROM="$WALLET_B"; TO="$WALLET_A"; TOKEN="$TOKEN_B" ;;
    2) FROM="$WALLET_B"; TO="$WALLET_C"; TOKEN="$TOKEN_B" ;;
    3) FROM="$WALLET_C"; TO="$WALLET_B"; TOKEN="$TOKEN_C" ;;
  esac
  amount=$(( 500 + (i % 7) * 137 ))
  status=$(curl -s -o "$GATE3_DIR/$i.body" -w "%{http_code}" -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"from\":\"$FROM\",\"to\":\"$TO\",\"amount_paise\":$amount,\"idempotency_key\":\"gate3-$i\"}")
  echo "$status" > "$GATE3_DIR/$i.status"
' _
server_errors=$(cat "$GATE3_DIR"/*.status 2>/dev/null | grep -c '^5' || true)

total_after=$(( $(balance_of "$WALLET_A" "$TOKEN_A") + $(balance_of "$WALLET_B" "$TOKEN_B") + $(balance_of "$WALLET_C" "$TOKEN_C") ))
bal_a=$(balance_of "$WALLET_A" "$TOKEN_A"); bal_b=$(balance_of "$WALLET_B" "$TOKEN_B"); bal_c=$(balance_of "$WALLET_C" "$TOKEN_C")

if [ "$server_errors" = "0" ]; then ok "no 5xx across 90 concurrent crossing transfers"; else bad "$server_errors requests returned 5xx"; fi
if [ "$total_after" = "$total_before" ]; then ok "conservation holds: total before=$total_before after=$total_after"; else bad "conservation broken: before=$total_before after=$total_after"; fi
if [ "$bal_a" -ge 0 ] && [ "$bal_b" -ge 0 ] && [ "$bal_c" -ge 0 ]; then
  ok "no negative balances (A=$bal_a B=$bal_b C=$bal_c)"
else
  bad "negative balance observed (A=$bal_a B=$bal_b C=$bal_c)"
fi

echo
echo "== Gate 3b: insufficient-funds contention never overdraws =="
WALLET_D=$(create_wallet "$TOKEN_A")
WALLET_E=$(create_wallet "$TOKEN_B")
seed_balance "$WALLET_D" 10000
export WALLET_D WALLET_E

GATE3B_DIR="$TMP_DIR/gate3b"; mkdir -p "$GATE3B_DIR"
export GATE3B_DIR
seq 1 24 | xargs -P24 -n1 bash -c '
  i="$1"
  status=$(curl -s -o "$GATE3B_DIR/$i.body" -w "%{http_code}" -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $TOKEN_A" -H "Content-Type: application/json" \
    -d "{\"from\":\"$WALLET_D\",\"to\":\"$WALLET_E\",\"amount_paise\":1000,\"idempotency_key\":\"od-$i\"}")
  echo "$status" > "$GATE3B_DIR/$i.status"
' _
succeeded=$(cat "$GATE3B_DIR"/*.status | grep -c '^201' || true)
server_errors=$(cat "$GATE3B_DIR"/*.status | grep -c '^5' || true)

bal_d=$(balance_of "$WALLET_D" "$TOKEN_A")
if [ "$server_errors" = "0" ] && [ "$succeeded" = "10" ] && [ "$bal_d" = "0" ]; then
  ok "exactly 10/24 concurrent 1000-paise debits succeeded against a 10000 balance; drained to exactly 0"
else
  bad "succeeded=$succeeded (expected 10), server_errors=$server_errors, balance_d=$bal_d (expected 0)"
fi

echo
echo "================================================================"
echo "  $pass passed, $fail failed"
echo "================================================================"
[ "$fail" -eq 0 ]
