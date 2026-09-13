#!/usr/bin/env bash
#
# One-command burst test reproducing every invariant/gate from the brief against a running instance.
#
# Fully API-driven — no direct database access needed. Wallets are funded through the real
# POST /wallets/{id}/deposit endpoint (added beyond the exercise's minimum API specifically so this
# script never has to reach around the API to seed a starting balance).
#
# Portability note: this deliberately avoids `xargs -I{}` for anything beyond a fixed one-line
# command — BSD xargs (macOS) silently fails ("command line cannot be assembled") on the longer
# substituted commands GNU xargs tolerates. Instead, arguments are passed positionally
# (`xargs -n1 ... bash -c '...' _`) and shared values travel via exported environment variables,
# which works identically on both.
#
# Usage:
#   ./burst.sh                                          # against a local docker-compose instance
#   BASE_URL=https://your-app.example.com ./burst.sh    # against a deployed instance
#
set -uo pipefail

export BASE_URL="${BASE_URL:-http://localhost:8080}"
export TOKEN_A="${TOKEN_A:-dev-token-alice}"
export TOKEN_B="${TOKEN_B:-dev-token-bob}"
export TOKEN_C="${TOKEN_C:-dev-token-carol}"
# Used only by Gate 4b, deliberately disjoint from every wallet Gates 1-4 touch — see that section.
export TOKEN_D="${TOKEN_D:-dev-token-dave}"
export TOKEN_E="${TOKEN_E:-dev-token-erin}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

pass=0
fail=0
ok()  { printf '  OK   - %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  FAIL - %s\n' "$1"; fail=$((fail + 1)); }

for dep in curl jq xargs; do
  command -v "$dep" >/dev/null 2>&1 || { echo "missing dependency: $dep" >&2; exit 1; }
done

create_wallet() {
  curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $1" | jq -r .wallet_id
}

balance_of() {
  curl -s "$BASE_URL/wallets/$1" -H "Authorization: Bearer $2" | jq -r .balance_paise
}

deposit_status() {
  # deposit_status <token> <wallet_id> <amount_paise> <idempotency_key> <out_body_file>
  curl -s -o "$5" -w '%{http_code}' -X POST "$BASE_URL/wallets/$2/deposit" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"amount_paise\":$3,\"idempotency_key\":\"$4\"}"
}

fund() {
  # fund <token> <wallet_id> <amount_paise> — one-shot deposit, used purely for test setup below.
  deposit_status "$1" "$2" "$3" "fund-$2-$3" "$TMP_DIR/fund.body" >/dev/null
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
echo "== Gate 2: 30 concurrent identical deposits (same idempotency key) -> exactly one credit =="
# This funds wallet A for the transfer gates below AND proves deposit idempotency in the same step —
# deliberately not a separate throwaway wallet: get-or-create is idempotent per user, so a second
# "fresh" wallet for token A would actually be the SAME wallet Setup funds next, and an additive
# deposit on top of that would silently break the hard-coded balance math further down.
WALLET_A=$(create_wallet "$TOKEN_A")
WALLET_B=$(create_wallet "$TOKEN_B")
export WALLET_A WALLET_B
GATE2_DIR="$TMP_DIR/gate2"; mkdir -p "$GATE2_DIR"
export GATE2_DIR
seq 1 30 | xargs -P30 -n1 bash -c '
  i="$1"
  status=$(curl -s -o "$GATE2_DIR/$i.body" -w "%{http_code}" -X POST "$BASE_URL/wallets/$WALLET_A/deposit" \
    -H "Authorization: Bearer $TOKEN_A" -H "Content-Type: application/json" \
    -d "{\"amount_paise\":1000000,\"idempotency_key\":\"fund-a-key\"}")
  echo "$status" > "$GATE2_DIR/$i.status"
' _
distinct_deposit_ids=$(for f in "$GATE2_DIR"/*.body; do jq -r .deposit_id "$f" 2>/dev/null; done | sort -u | grep -c . || true)
balance_after_deposit_storm=$(balance_of "$WALLET_A" "$TOKEN_A")
echo "  wallet A=$WALLET_A funded to $balance_after_deposit_storm paise; wallet B=$WALLET_B starts at 0"
if [ "$distinct_deposit_ids" = "1" ] && [ "$balance_after_deposit_storm" = "1000000" ]; then
  ok "30 concurrent identical deposits -> 1 deposit id, exactly one 1,000,000-paise credit"
else
  bad "30 concurrent identical deposits -> $distinct_deposit_ids deposit id(s), balance=$balance_after_deposit_storm (expected 1000000)"
fi

echo
echo "== Gate 2b: same deposit idempotency key + different amount -> 409 =="
status2=$(deposit_status "$TOKEN_A" "$WALLET_A" 9999 "fund-a-key" "$TMP_DIR/dep-conflict.body")
balance_after_conflict=$(balance_of "$WALLET_A" "$TOKEN_A")
if [ "$status2" = "409" ] && [ "$balance_after_conflict" = "1000000" ]; then
  ok "same deposit key + different amount -> 409, balance still 1,000,000"
else
  bad "same deposit key + different amount -> $status2 (expected 409), balance=$balance_after_conflict (expected 1000000)"
fi

echo
echo "== Gate 3: 30 concurrent identical transfers (same idempotency key) -> exactly one movement =="
GATE3_DIR="$TMP_DIR/gate3"; mkdir -p "$GATE3_DIR"
export GATE3_DIR
seq 1 30 | xargs -P30 -n1 bash -c '
  i="$1"
  status=$(curl -s -o "$GATE3_DIR/$i.body" -w "%{http_code}" -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $TOKEN_A" -H "Content-Type: application/json" \
    -d "{\"from\":\"$WALLET_A\",\"to\":\"$WALLET_B\",\"amount_paise\":5000,\"idempotency_key\":\"storm-key\"}")
  echo "$status" > "$GATE3_DIR/$i.status"
' _
distinct_transfer_ids=$(for f in "$GATE3_DIR"/*.body; do jq -r .transfer_id "$f" 2>/dev/null; done | sort -u | grep -c . || true)
distinct_statuses=$(cat "$GATE3_DIR"/*.status | sort -u | grep -c . || true)
balance_a_after_storm=$(balance_of "$WALLET_A" "$TOKEN_A")
if [ "$distinct_transfer_ids" = "1" ] && [ "$distinct_statuses" = "1" ] && [ "$balance_a_after_storm" = "995000" ]; then
  ok "30 concurrent identical transfers -> 1 transfer id, 1 status code, exactly one 5000-paise debit"
else
  bad "30 concurrent identical transfers -> $distinct_transfer_ids transfer id(s), $distinct_statuses status code(s), balance_a=$balance_a_after_storm (expected 995000)"
fi

echo
echo "== Gate 3b: same transfer idempotency key + different body -> 409 =="
post_transfer_status "$TOKEN_A" "$WALLET_A" "$WALLET_B" 1000 "conflict-key" "$TMP_DIR/c1.body" > /dev/null
status3b=$(post_transfer_status "$TOKEN_A" "$WALLET_A" "$WALLET_B" 2000 "conflict-key" "$TMP_DIR/c2.body")
if [ "$status3b" = "409" ]; then
  ok "same transfer key + different body -> 409"
else
  bad "same transfer key + different body -> $status3b (expected 409)"
fi

echo
echo "== Gate 4: conservation + no-overdraft under concurrent contention (incl. A->B and B->A) =="
WALLET_C=$(create_wallet "$TOKEN_C")
fund "$TOKEN_B" "$WALLET_B" 50000
fund "$TOKEN_C" "$WALLET_C" 50000
export WALLET_C
total_before=$(( $(balance_of "$WALLET_A" "$TOKEN_A") + $(balance_of "$WALLET_B" "$TOKEN_B") + $(balance_of "$WALLET_C" "$TOKEN_C") ))

GATE4_DIR="$TMP_DIR/gate4"; mkdir -p "$GATE4_DIR"
export GATE4_DIR
seq 1 90 | xargs -P30 -n1 bash -c '
  i="$1"
  case $(( i % 4 )) in
    0) FROM="$WALLET_A"; TO="$WALLET_B"; TOKEN="$TOKEN_A" ;;
    1) FROM="$WALLET_B"; TO="$WALLET_A"; TOKEN="$TOKEN_B" ;;
    2) FROM="$WALLET_B"; TO="$WALLET_C"; TOKEN="$TOKEN_B" ;;
    3) FROM="$WALLET_C"; TO="$WALLET_B"; TOKEN="$TOKEN_C" ;;
  esac
  amount=$(( 500 + (i % 7) * 137 ))
  status=$(curl -s -o "$GATE4_DIR/$i.body" -w "%{http_code}" -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"from\":\"$FROM\",\"to\":\"$TO\",\"amount_paise\":$amount,\"idempotency_key\":\"gate4-$i\"}")
  echo "$status" > "$GATE4_DIR/$i.status"
' _
server_errors=$(cat "$GATE4_DIR"/*.status 2>/dev/null | grep -c '^5' || true)

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
echo "== Gate 4b: insufficient-funds contention never overdraws =="
# Uses TOKEN_D/TOKEN_E exclusively — NOT the A/B/C tokens Gates 1-4 already used. Wallet creation is
# idempotent per user, so reusing an earlier gate's token here would silently hand back that SAME
# wallet (not a fresh one) and this gate's fund() would add to whatever balance the earlier gate left
# behind — confusing to anyone inspecting the wallet afterward, even though each gate's own assertions
# still run correctly in sequence. Keeping every gate's wallets disjoint avoids that.
WALLET_D=$(create_wallet "$TOKEN_D")
WALLET_E=$(create_wallet "$TOKEN_E")
fund "$TOKEN_D" "$WALLET_D" 10000
export WALLET_D WALLET_E

GATE4B_DIR="$TMP_DIR/gate4b"; mkdir -p "$GATE4B_DIR"
export GATE4B_DIR
seq 1 24 | xargs -P24 -n1 bash -c '
  i="$1"
  status=$(curl -s -o "$GATE4B_DIR/$i.body" -w "%{http_code}" -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $TOKEN_D" -H "Content-Type: application/json" \
    -d "{\"from\":\"$WALLET_D\",\"to\":\"$WALLET_E\",\"amount_paise\":1000,\"idempotency_key\":\"od-$i\"}")
  echo "$status" > "$GATE4B_DIR/$i.status"
' _
succeeded=$(cat "$GATE4B_DIR"/*.status | grep -c '^201' || true)
server_errors=$(cat "$GATE4B_DIR"/*.status | grep -c '^5' || true)

bal_d=$(balance_of "$WALLET_D" "$TOKEN_D")
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
