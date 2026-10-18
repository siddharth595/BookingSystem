
#!/usr/bin/env bash
set -Eeuo pipefail

# Usage:
# ADMIN_PASSWORD=... USER_PASSWORD=... USER2_PASSWORD=... \
# USER3_PASSWORD=... ./burst.sh http://localhost:8080

BASE_URL="${1:?Usage: ./burst.sh BASE_URL}"

: "${ADMIN_USERNAME:=siddnaya}"
: "${ADMIN_PASSWORD:?Set ADMIN_PASSWORD}"
: "${USER_USERNAME:=siddnaya1}"
: "${USER_PASSWORD:?Set USER_PASSWORD}"
: "${USER2_USERNAME:=siddnaya2}"
: "${USER2_PASSWORD:?Set USER2_PASSWORD}"
: "${USER3_USERNAME:=siddnaya3}"

HOT_USERS="${HOT_USERS:-3}"
HOT_REQUESTS="${HOT_REQUESTS:-3}"
PARALLEL="${PARALLEL:-32}"

# -------------------- Validation --------------------

if [[ "$HOT_USERS" != 2 && "$HOT_USERS" != 3 ]]; then
  echo "[ERROR] HOT_USERS must be 2 or 3" >&2
  exit 2
fi

if (( HOT_REQUESTS < HOT_USERS || PARALLEL < HOT_USERS )); then
  echo "[ERROR] HOT_REQUESTS and PARALLEL must be >= HOT_USERS" >&2
  exit 2
fi

if (( HOT_USERS == 3 )); then
  : "${USER3_PASSWORD:?Set USER3_PASSWORD}"
fi

# -------------------- Logging --------------------

log() {
  printf '[%s] [INFO] %s\n' \
    "$(date '+%H:%M:%S')" "$*" >&2
}

warn() {
  printf '[%s] [WARN] %s\n' \
    "$(date '+%H:%M:%S')" "$*" >&2
}

die() {
  printf '[%s] [ERROR] %s\n' \
    "$(date '+%H:%M:%S')" "$*" >&2
  exit 1
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# -------------------- Authentication --------------------

token() {
  local username="$1"
  local response
  local access_token

  log "Authenticating user: $username"

  if ! response="$(curl -fsS -X POST "$BASE_URL/auth/token" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc \
      --arg username "$username" \
      --arg password "$2" \
      '{username:$username,password:$password}')")"; then
    die "Authentication request failed for $username"
  fi

  if ! access_token="$(jq -er '.access_token' <<< "$response")"; then
    die "No access_token returned for $username. Response: $response"
  fi

  log "Authentication successful: $username"
  printf '%s' "$access_token"
}

# Capture tokens without mixing log output into command substitution.
# token() logs to stderr, so only the token reaches these variables.
admin_token="$(token "$ADMIN_USERNAME" "$ADMIN_PASSWORD")"
user_token="$(token "$USER_USERNAME" "$USER_PASSWORD")"
user2_token="$(token "$USER2_USERNAME" "$USER2_PASSWORD")"
user3_token=""

if (( HOT_USERS == 3 )); then
  user3_token="$(token "$USER3_USERNAME" "$USER3_PASSWORD")"
fi

# -------------------- Show creation --------------------

create_show() {
  curl -fsS -X POST "$BASE_URL/shows" \
    -H "Authorization: Bearer $admin_token" \
    -H 'Content-Type: application/json' \
    -d "$1"
}

SEATS='["A1","A2","A3","A4","A5","A6","A7","A8","A9","A10","A11","A12","A13"]'

log "Creating dedicated hot-seat show"

hot_show="$(create_show \
  "{\"name\":\"hot-seat-contention\",\"seats\":$SEATS,\"price_paise\":25000,\"per_user_limit\":4}")"

hot_show_id="$(jq -er '.id' <<< "$hot_show")"
log "Hot-seat show created: ID=$hot_show_id"

log "Creating main test show"

show="$(create_show \
  "{\"name\":\"burst2\",\"seats\":$SEATS,\"price_paise\":25000,\"per_user_limit\":4}")"

show_id="$(jq -er '.id' <<< "$show")"
log "Main show created: ID=$show_id"

export BASE_URL user_token user2_token user3_token
export show_id hot_show_id work HOT_USERS

# -------------------- HTTP request helper --------------------

# Runs curl, saving response body and HTTP status separately.
# Usage: request OUTPUT_PREFIX TOKEN KEY JSON_BODY URL
request() {
  local prefix="$1"
  local auth_token="$2"
  local idempotency_key="$3"
  local payload="$4"
  local url="$5"
  local curl_rc=0

  log "POST $url | key=$idempotency_key | payload=$payload"

  if curl -sS -X POST "$url" \
    -H "Authorization: Bearer $auth_token" \
    -H "Idempotency-Key: $idempotency_key" \
    -H 'Content-Type: application/json' \
    -d "$payload" \
    -o "$work/$prefix.json" \
    -w '%{http_code}\n' > "$work/$prefix.status"; then
    curl_rc=0
  else
    curl_rc=$?
  fi

  local status
  status="$(cat "$work/$prefix.status" 2>/dev/null || true)"

  log "$prefix completed | HTTP=${status:-EMPTY} | curl_exit=$curl_rc"

  if (( curl_rc != 0 )) || [[ ! "$status" =~ ^[0-9]{3}$ ]]; then
    warn "$prefix transport/request failure"
    warn "Response body: $(cat "$work/$prefix.json" 2>/dev/null || true)"
  fi

  printf '%s\n' "$status"
}

# -------------------- Seed A1 first --------------------
#
# IMPORTANT:
# User 1 needs A1 and A10 plus two successful limit-test
# bookings to reach the four-seat limit.
#
# Seeding after the limit test would fail with per_user_limit.

log "Seeding A1 on the main show for the atomicity test"

seed_status="$(request \
  "seed" \
  "$user_token" \
  "seed-A1-initial" \
  '{"seats":["A1"]}' \
  "$BASE_URL/shows/$show_id/reserve")"

if [[ "$seed_status" != "201" ]]; then
  warn "A1 seed failed: HTTP $seed_status"
  warn "Seed response: $(cat "$work/seed.json" 2>/dev/null || true)"

  if state="$(curl -fsS "$BASE_URL/shows/$show_id")"; then
    jq '{total_seats,available,held,confirmed,
         seat_A1:(.seats[] | select(.seat_id=="A1") | .status)}' \
      <<< "$state" >&2
  fi

  die "Cannot run atomicity test because A1 was not seeded"
fi

log "A1 successfully seeded"

# -------------------- Hot-seat contention --------------------

log "HOT SEAT TEST: $HOT_USERS users, $HOT_REQUESTS requests, seat A1"

mkfifo "$work/barrier"
exec 3<>"$work/barrier"

# Use a temporary worker script so debugging and error handling
# are easier to maintain.
cat > "$work/hot-worker.sh" <<'WORKER'
#!/usr/bin/env bash
set -u

i="$1"
user_number="$(( (i - 1) % HOT_USERS + 1 ))"

case "$user_number" in
  1) token="$user_token";  label="user1" ;;
  2) token="$user2_token"; label="user2" ;;
  3) token="$user3_token"; label="user3" ;;
  *) echo "[ERROR] Invalid user number $user_number" >&2; exit 1 ;;
esac

printf '[HOT %s] Assigned to %s; waiting at barrier\n' \
  "$i" "$label" >&2

if ! IFS= read -r _ < "$work/barrier"; then
  echo "[HOT $i] ERROR: barrier read failed" >&2
  exit 1
fi

printf '[HOT %s] Released; sending request as %s\n' \
  "$i" "$label" >&2

curl_rc=0

if curl -sS -X POST "$BASE_URL/shows/$hot_show_id/reserve" \
  -H "Authorization: Bearer $token" \
  -H "Idempotency-Key: hot-$i" \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A1"]}' \
  -o "$work/hot-$i.json" \
  -w '%{http_code}\n' > "$work/hot-$i.status"; then
  curl_rc=0
else
  curl_rc=$?
fi

status="$(cat "$work/hot-$i.status" 2>/dev/null || true)"

printf '[HOT %s] User=%s HTTP=%s curl_exit=%s\n' \
  "$i" "$label" "${status:-EMPTY}" "$curl_rc" >&2

if (( curl_rc != 0 )) || [[ "$status" != "201" && "$status" != "409" ]]; then
  printf '[HOT %s] Response: ' "$i" >&2
  cat "$work/hot-$i.json" >&2 2>/dev/null || true
  printf '\n' >&2
fi

# Nonzero worker exit on transport failure or unexpected HTTP status.
if (( curl_rc != 0 )) || [[ "$status" != "201" && "$status" != "409" ]]; then
  exit 1
fi
WORKER

chmod +x "$work/hot-worker.sh"

# Launch workers in the background. The FIFO releases them together.
seq 1 "$HOT_REQUESTS" |
  xargs -P "$PARALLEL" -I '{}' \
    "$work/hot-worker.sh" '{}' &
hot_pid=$!

log "Hot-seat workers launched; waiting briefly for initialization"
sleep 1

log "Releasing $HOT_REQUESTS hot-seat workers"

for ((i=1; i<=HOT_REQUESTS; i++)); do
  printf 'go\n' >&3
done

if wait "$hot_pid"; then
  log "Hot-seat worker pipeline completed"
else
  warn "Hot-seat worker pipeline returned a nonzero exit"
fi

exec 3>&-
log "Hot-seat test finished"

# -------------------- Idempotency/retry test --------------------
#
# Same user + same idempotency key + same payload.
# Expected: one 201 and nineteen 200 responses.

log "RETRY TEST: 20 requests using key retry-A2 and seat A10"

seq 1 20 | xargs -P "$PARALLEL" -I '{}' sh -c '
  i="$1"
  prefix="retry-$i"

  printf "[RETRY %s] Sending request\n" "$i" >&2

  curl_rc=0
  if curl -sS -X POST "$BASE_URL/shows/$show_id/reserve" \
    -H "Authorization: Bearer $user_token" \
    -H "Idempotency-Key: retry-A2" \
    -H "Content-Type: application/json" \
    -d "{\"seats\":[\"A10\"]}" \
    -o "$work/$prefix.json" \
    -w "%{http_code}\n" > "$work/$prefix.status"; then
    curl_rc=0
  else
    curl_rc=$?
  fi

  status="$(cat "$work/$prefix.status" 2>/dev/null || true)"
  printf "[RETRY %s] HTTP=%s curl_exit=%s\n" \
    "$i" "${status:-EMPTY}" "$curl_rc" >&2

  if (( curl_rc != 0 )) || [[ "$status" != "200" && "$status" != "201" ]]; then
    printf "[RETRY %s] Response: " "$i" >&2
    cat "$work/$prefix.json" >&2 2>/dev/null || true
    printf "\n" >&2
  fi

  if (( curl_rc != 0 )) || [[ "$status" != "200" && "$status" != "201" ]]; then
    exit 1
  fi
' sh '{}'

log "Retry test completed"

# -------------------- Booking-limit test --------------------
#
# User 1 already has A1 and A10, leaving two seats in their quota.
# Ten attempts target A3 through A12. A10 is already occupied;
# two of the remaining available seats can be booked.
#
# Expected aggregate: 2 x 201 and 8 x 409.

log "BOOKING LIMIT TEST: user1 attempts seats A3-A12"

seq 3 12 | xargs -P "$PARALLEL" -I '{}' sh -c '
  i="$1"
  prefix="limit-$i"

  printf "[LIMIT A%s] Sending request\n" "$i" >&2

  curl_rc=0
  if curl -sS -X POST "$BASE_URL/shows/$show_id/reserve" \
    -H "Authorization: Bearer $user_token" \
    -H "Idempotency-Key: limit-$i" \
    -H "Content-Type: application/json" \
    -d "{\"seats\":[\"A$((i))\"]}" \
    -o "$work/$prefix.json" \
    -w "%{http_code}\n" > "$work/$prefix.status"; then
    curl_rc=0
  else
    curl_rc=$?
  fi

  status="$(cat "$work/$prefix.status" 2>/dev/null || true)"
  printf "[LIMIT A%s] HTTP=%s curl_exit=%s\n" \
    "$i" "${status:-EMPTY}" "$curl_rc" >&2

  if (( curl_rc != 0 )) || [[ "$status" != "201" && "$status" != "409" ]]; then
    printf "[LIMIT A%s] Response: " "$i" >&2
    cat "$work/$prefix.json" >&2 2>/dev/null || true
    printf "\n" >&2
  fi

  if (( curl_rc != 0 )) || [[ "$status" != "201" && "$status" != "409" ]]; then
    exit 1
  fi
' sh '{}'

log "Booking-limit test completed"

# -------------------- Atomicity test --------------------
#
# Use user2 so user1's four-seat limit cannot cause a false
# positive. A1 is already booked; A13 must remain available.

log "ATOMICITY TEST: user2 requests occupied A1 and available A13"

atomic_status="$(request \
  "atomic" \
  "$user2_token" \
  "atomic-check" \
  '{"seats":["A1","A13"]}' \
  "$BASE_URL/shows/$show_id/reserve")"

if [[ "$atomic_status" != "409" ]]; then
  warn "Expected atomicity HTTP 409; got ${atomic_status:-EMPTY}"
fi

# -------------------- Fetch final state --------------------

log "Fetching final show states"

state="$(curl -fsS "$BASE_URL/shows/$show_id")"
hot_state="$(curl -fsS "$BASE_URL/shows/$hot_show_id")"

log "Main show state:"
jq '{total_seats,available,held,confirmed,
     seat_A1:(.seats[] | select(.seat_id=="A1") | .status),
     seat_A10:(.seats[] | select(.seat_id=="A10") | .status),
     seat_A13:(.seats[] | select(.seat_id=="A13") | .status),
     reconciled:(.available+.held+.confirmed==.total_seats)}' \
  <<< "$state" >&2

log "Hot-seat show state:"
jq '{total_seats,available,held,confirmed,
     seat_A1:(.seats[] | select(.seat_id=="A1") | .status),
     reconciled:(.available+.held+.confirmed==.total_seats)}' \
  <<< "$hot_state" >&2

# -------------------- Count HTTP statuses --------------------

count_status() {
  local pattern="$1"
  shift

  # grep -c prints a count per file. No matches are valid and count as 0.
  { grep -hEc "$pattern" "$@" || true; } |
    awk '{sum += $1} END {print sum+0}'
}

hot_201="$(count_status '^201$' "$work"/hot-*.status)"
hot_409="$(count_status '^409$' "$work"/hot-*.status)"
hot_5xx="$(count_status '^5[0-9][0-9]$' "$work"/hot-*.status)"

retry_201="$(count_status '^201$' "$work"/retry-*.status)"
retry_200="$(count_status '^200$' "$work"/retry-*.status)"

limit_201="$(count_status '^201$' "$work"/limit-*.status)"
limit_409="$(count_status '^409$' "$work"/limit-*.status)"

server_errors="$(count_status '^5[0-9][0-9]$' "$work"/*.status)"
missing_status="$(find "$work" -name '*.status' -type f -size 0c | wc -l | tr -d ' ')"

echo
echo "================ TEST SUMMARY ================"
echo "Hot seat: 201=$hot_201 409=$hot_409 5xx=$hot_5xx"
echo "Retry:    201=$retry_201 200=$retry_200"
echo "Limit:    201=$limit_201 409=$limit_409"
echo "Atomicity: HTTP=$atomic_status"
echo "All tests: 5xx=$server_errors empty_status_files=$missing_status"

echo
echo "Main show:"
jq '{total_seats,available,held,confirmed,
     reconciled:(.available+.held+.confirmed==.total_seats)}' \
  <<< "$state"

echo
echo "Hot-seat show:"
jq '{total_seats,available,held,confirmed,
     seat_A1:(.seats[] | select(.seat_id=="A1") | .status),
     reconciled:(.available+.held+.confirmed==.total_seats)}' \
  <<< "$hot_state"

# -------------------- Assertions --------------------

log "Validating expected results"

assert_eq() {
  local name="$1"
  local actual="$2"
  local expected="$3"

  if [[ "$actual" == "$expected" ]]; then
    log "PASS: $name = $actual"
  else
    warn "FAIL: $name | expected=$expected actual=$actual"
    return 1
  fi
}

failures=0

assert_eq "hot_201" "$hot_201" 1 || failures=$((failures + 1))
assert_eq "hot_409" "$hot_409" "$((HOT_REQUESTS - 1))" || failures=$((failures + 1))
assert_eq "hot_5xx" "$hot_5xx" 0 || failures=$((failures + 1))

assert_eq "retry_201" "$retry_201" 1 || failures=$((failures + 1))
assert_eq "retry_200" "$retry_200" 19 || failures=$((failures + 1))

assert_eq "limit_201" "$limit_201" 2 || failures=$((failures + 1))
assert_eq "limit_409" "$limit_409" 8 || failures=$((failures + 1))

assert_eq "server_errors" "$server_errors" 0 || failures=$((failures + 1))
assert_eq "empty_status_files" "$missing_status" 0 || failures=$((failures + 1))
assert_eq "atomic_status" "$atomic_status" 409 || failures=$((failures + 1))

main_confirmed="$(jq -r '.confirmed' <<< "$state")"
main_reconciled="$(jq -r '.available + .held + .confirmed == .total_seats' <<< "$state")"
main_a13="$(jq -r '.seats[] | select(.seat_id=="A13") | .status' <<< "$state")"

hot_confirmed="$(jq -r '.confirmed' <<< "$hot_state")"
hot_a1="$(jq -r '.seats[] | select(.seat_id=="A1") | .status' <<< "$hot_state")"
hot_reconciled="$(jq -r '.available + .held + .confirmed == .total_seats' <<< "$hot_state")"

assert_eq "main_confirmed" "$main_confirmed" 4 || failures=$((failures + 1))
assert_eq "main_reconciled" "$main_reconciled" true || failures=$((failures + 1))
assert_eq "main_A13_status" "$main_a13" available || failures=$((failures + 1))

assert_eq "hot_confirmed" "$hot_confirmed" 1 || failures=$((failures + 1))
assert_eq "hot_A1_status" "$hot_a1" confirmed || failures=$((failures + 1))
assert_eq "hot_reconciled" "$hot_reconciled" true || failures=$((failures + 1))

echo
if (( failures > 0 )); then
  die "$failures assertion(s) failed. Review the logs above."
fi

log "All assertions passed."
