
#!/usr/bin/env bash
set -Eeuo pipefail

# Usage:
#   ./burst.sh http://localhost:8080
#
# Required environment variables:
#   ADMIN_USERNAME, ADMIN_PASSWORD, USER_PASSWORD
#
# Optional:
#   USER_COUNT=100 HOT_REQUESTS=100 PARALLEL=100

BASE_URL="${1:-${BASE_URL:-}}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Admin123@}"
USER_PASSWORD="${USER_PASSWORD:-User123@}"

USER_PREFIX="${USER_PREFIX:-user_}"
USER_COUNT="${USER_COUNT:-100}"
HOT_REQUESTS="${HOT_REQUESTS:-100}"
PARALLEL="${PARALLEL:-100}"

if [[ -z "$BASE_URL" || -z "$ADMIN_USERNAME" ||
      -z "$ADMIN_PASSWORD" || -z "$USER_PASSWORD" ]]; then
  echo "Usage: ADMIN_USERNAME=... ADMIN_PASSWORD=... USER_PASSWORD=... ./burst.sh <BASE_URL>"
  exit 1
fi

for cmd in curl jq xargs; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "Missing required command: $cmd"
    exit 1
  }
done

if (( USER_COUNT < 1 || HOT_REQUESTS < 1 ||
      PARALLEL < 1 || PARALLEL > HOT_REQUESTS )); then
  echo "USER_COUNT and HOT_REQUESTS must be positive; PARALLEL must be between 1 and HOT_REQUESTS."
  exit 1
fi

BASE_URL="${BASE_URL%/}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

log() {
    printf '[%s] [INFO] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

die() {
    printf '[%s] [ERROR] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2
    exit 1
}

# Authenticate and return access token.
get_token() {
  local username="$1"
  local password="$2"
  local response_file="$3"
  local status
  #echo "username: $username, password: $password" >&2
  status="$(curl -sS \
    -o "$response_file" \
    -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    -X POST "$BASE_URL/auth/token" \
    -d "$(jq -n --arg u "$username" --arg p "$password" \
      '{username:$u,password:$p}')")" || true

  if [[ "$status" != "200" ]]; then
    echo "Authentication failed for $username (HTTP $status)." >&2
    jq -c . "$response_file" >&2 2>/dev/null || true
    return 1
  fi

  jq -er '.access_token' "$response_file"
}

log "Authenticating admin..."
ADMIN_TOKEN="$(get_token "$ADMIN_USERNAME" "$ADMIN_PASSWORD" "$WORKDIR/admin.json")" ||
  die "Admin authentication failed."

# Authenticate all users BEFORE starting the burst.
log "Authenticating $USER_COUNT seeded users..."

for ((u=1; u<=USER_COUNT; u++)); do
  username="${USER_PREFIX}${u}"

  if ! get_token "$username" "$USER_PASSWORD" "$WORKDIR/auth-$u.json" \
      > "$WORKDIR/token-$u"; then
    die "Could not authenticate $username. Check your seeded accounts and shared password."
  fi

  [[ -s "$WORKDIR/token-$u" ]] || die "Empty token for $username."
done

log "All $USER_COUNT users authenticated."

# Create a fresh show so A1 starts available.
SEATS='["A1","A2","A3","A4","A5","A6","A7","A8","A9","A10","A11","A12","A13"]'

CREATE_STATUS="$(curl -sS \
  -o "$WORKDIR/create.json" \
  -w '%{http_code}' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -X POST "$BASE_URL/shows" \
  -d "$(jq -n \
    --arg name "hot-seat-burst-$(date +%s)" \
    --argjson seats "$SEATS" \
    '{name:$name,seats:$seats,price_paise:25000,per_user_limit:4}')")" ||
  die "Could not create test show."

if [[ "$CREATE_STATUS" != "200" && "$CREATE_STATUS" != "201" ]]; then
  echo "Show creation failed: HTTP $CREATE_STATUS" >&2
  jq . "$WORKDIR/create.json" >&2 || true
  exit 1
fi

SHOW_ID="$(jq -er '.id // .show_id' "$WORKDIR/create.json")"
log "Created show $SHOW_ID. Hot seat: A1"

# Ensure A1 is initially available.
curl -fsS \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$BASE_URL/shows/$SHOW_ID" > "$WORKDIR/before.json" ||
  die "Could not read initial show inventory."

INITIAL_A1="$(jq -r '
  [.seats[] | select(.seat_id == "A1") | .status][0] // "missing"
' "$WORKDIR/before.json")"

[[ "$(printf '%s' "$INITIAL_A1" | tr '[:lower:]' '[:upper:]')" == "AVAILABLE" ]]  ||
  die "A1 is not initially AVAILABLE (found: $INITIAL_A1). Use a fresh show."

# Worker: each request competes for A1.
# Accounts are distributed round-robin; each request gets its own idempotency key.
cat > "$WORKDIR/worker.sh" <<'WORKER'
#!/usr/bin/env bash
set -u

i="$1"
user_count="$2"
base_url="$3"
show_id="$4"
workdir="$5"

user_no=$(( ((i - 1) % user_count) + 1 ))
token="$(cat "$workdir/token-$user_no")"

status="$(curl -sS \
  -o "$workdir/body-$i.json" \
  -w '%{http_code}' \
  --connect-timeout 10 \
  --max-time 180 \
  -H "Authorization: Bearer $token" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: hot-$show_id-$i" \
  -X POST "$base_url/shows/$show_id/reserve" \
  -d '{"seats":["A2"]}')" || status="000"

printf '%s\n' "$status" > "$workdir/status-$i"
WORKER
chmod +x "$WORKDIR/worker.sh"

export BASE_URL SHOW_ID USER_COUNT WORKDIR

log "Starting hot-seat storm:"
log "  Requests: $HOT_REQUESTS"
log "  Max parallel requests: $PARALLEL"
log "  Distinct user accounts: $USER_COUNT"
log "  Seat: A1"

START_TIME="$(date +%s)"

# Launch the requests concurrently.
seq 1 "$HOT_REQUESTS" |
  xargs -P "$PARALLEL" -I '{}' \
    "$WORKDIR/worker.sh" '{}' "$USER_COUNT" "$BASE_URL" "$SHOW_ID" "$WORKDIR"

END_TIME="$(date +%s)"
ELAPSED=$((END_TIME - START_TIME))
(( ELAPSED < 1 )) && ELAPSED=1

# Count outcomes.
count_status() {
  local pattern="$1"
  local count=0
  local f

  for f in "$WORKDIR"/status-*; do
    [[ -f "$f" ]] || continue
    if grep -Eq "$pattern" "$f"; then
      count=$((count + 1))
    fi
  done

  echo "$count"
}

TOTAL_RECORDED="$(find "$WORKDIR" -maxdepth 1 -name 'status-*' | wc -l | tr -d ' ')"
SUCCESS_201="$(count_status '^201$')"
IDEMPOTENT_200="$(count_status '^200$')"
DECLINED_409="$(count_status '^409$')"
RATE_LIMITED_429="$(count_status '^429$')"
SERVER_5XX="$(count_status '^5[0-9][0-9]$')"
TRANSPORT_ERRORS="$(count_status '^000$')"

OTHER="$(count_status '^(?!$)' 2>/dev/null || true)"
# Portable count of other HTTP statuses.
OTHER=0
for f in "$WORKDIR"/status-*; do
  [[ -f "$f" ]] || continue
  s="$(cat "$f")"
  case "$s" in
    200|201|409|429|5[0-9][0-9]|000) ;;
    *) OTHER=$((OTHER + 1)) ;;
  esac
done

echo
echo "========== HOT-SEAT RESULTS =========="
printf 'Requests configured:       %s\n' "$HOT_REQUESTS"
printf 'Responses recorded:        %s\n' "$TOTAL_RECORDED"
printf 'HTTP 201 (created):        %s\n' "$SUCCESS_201"
printf 'HTTP 200 (success/replay): %s\n' "$IDEMPOTENT_200"
printf 'HTTP 409 (conflict):       %s\n' "$DECLINED_409"
printf 'HTTP 429 (rate limited):   %s\n' "$RATE_LIMITED_429"
printf 'HTTP 5xx (server errors):  %s\n' "$SERVER_5XX"
printf 'Transport errors:          %s\n' "$TRANSPORT_ERRORS"
printf 'Other HTTP statuses:      %s\n' "$OTHER"
printf 'Elapsed seconds:           %s\n' "$ELAPSED"
printf 'Approx requests/sec:        %s\n' "$((HOT_REQUESTS / ELAPSED))"

echo
echo "========== DECLINE REASONS =========="
# Groups HTTP 409 response bodies by the API's reported reason.
for f in "$WORKDIR"/status-*; do
  [[ -f "$f" ]] || continue
  [[ "$(cat "$f")" == "409" ]] || continue

  body="${f/status-/body-}.json"
  reason="$(jq -r '
    .code // .error // .reason // .message // "unknown_reason"
    | if type == "string" then . else tostring end
  ' "$body" 2>/dev/null || echo unknown_reason)"

  printf '%s\n' "$reason"
done | sort | uniq -c | sort -nr

echo
echo "========== FINAL INVENTORY =========="

if ! curl -fsS \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$BASE_URL/shows/$SHOW_ID" > "$WORKDIR/final.json"; then
  die "Could not retrieve final inventory."
fi

jq '{
  total_seats,
  available,
  held,
  confirmed,
  seat_A1: ([.seats[] | select(.seat_id == "A1")][0] // null)
}' "$WORKDIR/final.json"

TOTAL="$(jq -r '.total_seats' "$WORKDIR/final.json")"
AVAILABLE="$(jq -r '.available' "$WORKDIR/final.json")"
HELD="$(jq -r '.held' "$WORKDIR/final.json")"
CONFIRMED="$(jq -r '.confirmed' "$WORKDIR/final.json")"
A1_STATUS="$(jq -r '
  [.seats[] | select(.seat_id == "A1") | .status][0] // "missing"
' "$WORKDIR/final.json")"

echo
echo "========== ASSERTIONS =========="

FAIL=0

if [[ "$TOTAL_RECORDED" -ne "$HOT_REQUESTS" ]]; then
  echo "FAIL: expected $HOT_REQUESTS responses, recorded $TOTAL_RECORDED."
  FAIL=1
else
  echo "PASS: all requests produced a recorded outcome."
fi

if [[ "$SUCCESS_201" -ne 1 ]]; then
  echo "FAIL: expected exactly one HTTP 201, got $SUCCESS_201."
  FAIL=1
else
  echo "PASS: exactly one reservation creation response."
fi

if [[ "$SERVER_5XX" -ne 0 ]]; then
  echo "FAIL: received $SERVER_5XX server errors."
  FAIL=1
else
  echo "PASS: no HTTP 5xx responses."
fi

if [[ "$TRANSPORT_ERRORS" -ne 0 ]]; then
  echo "FAIL: received $TRANSPORT_ERRORS transport errors."
  FAIL=1
else
  echo "PASS: no transport errors."
fi

if (( TOTAL != AVAILABLE + HELD + CONFIRMED )); then
  echo "FAIL: inventory does not reconcile."
  FAIL=1
else
  echo "PASS: inventory reconciles."
fi

if [[ "$(printf '%s' "$A1_STATUS" | tr '[:upper:]' '[:lower:]')" != "confirmed" ]]; then
  echo "FAIL: A1 status is $A1_STATUS, expected CONFIRMED."
  FAIL=1
else
  echo "PASS: A1 is confirmed."
fi

echo
echo "Test show ID: $SHOW_ID"
if (( FAIL != 0 )); then
  echo "RESULT: FAILED"
  exit 1
fi

echo "RESULT: PASSED"
