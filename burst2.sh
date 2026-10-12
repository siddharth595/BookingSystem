

#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:?Usage: ./burst.sh BASE_URL}"

: "${ADMIN_USERNAME:=siddnaya}"
: "${ADMIN_PASSWORD:?Set ADMIN_PASSWORD}"
: "${USER_USERNAME:=siddnaya1}"
: "${USER_PASSWORD:?Set USER_PASSWORD}"
: "${USER2_USERNAME:=siddnaya2}"
: "${USER3_USERNAME:=siddnaya3}"

HOT_USERS="${HOT_USERS:-3}"
HOT_REQUESTS="${HOT_REQUESTS:-3}"
PARALLEL="${PARALLEL:-32}"

if [[ "$HOT_USERS" != 2 && "$HOT_USERS" != 3 ]]; then
  echo "HOT_USERS must be 2 or 3" >&2
  exit 2
fi

if (( HOT_REQUESTS < HOT_USERS || PARALLEL < HOT_USERS )); then
  echo "HOT_REQUESTS and PARALLEL must be >= HOT_USERS" >&2
  exit 2
fi

: "${USER2_PASSWORD:?Set USER2_PASSWORD}"
if (( HOT_USERS == 3 )); then
  : "${USER3_PASSWORD:?Set USER3_PASSWORD}"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

token() {
  curl -fsS -X POST "$BASE_URL/auth/token" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg username "$1" --arg password "$2" \
      '{username:$username,password:$password}')" |
    jq -er '.access_token'
}

admin_token="$(token "$ADMIN_USERNAME" "$ADMIN_PASSWORD")"
user_token="$(token "$USER_USERNAME" "$USER_PASSWORD")"
user2_token="$(token "$USER2_USERNAME" "$USER2_PASSWORD")"
user3_token=""

if (( HOT_USERS == 3 )); then
  user3_token="$(token "$USER3_USERNAME" "$USER3_PASSWORD")"
fi

create_show() {
  curl -fsS -X POST "$BASE_URL/shows" \
    -H "Authorization: Bearer $admin_token" \
    -H 'Content-Type: application/json' \
    -d "$1"
}

# Dedicated show for hot-seat contention.
hot_show="$(create_show \
  '{"name":"hot-seat-contention","seats":["A1","A2","A3","A4","A5","A6","A7","A8","A9","A10","A11","A12","A13"],"price_paise":25000,"per_user_limit":4}')"
hot_show_id="$(jq -er '.id' <<< "$hot_show")"

# Separate show for the remaining tests.
show="$(create_show \
  '{"name":"burst2","seats":["A1","A2","A3","A4","A5","A6","A7","A8","A9","A10","A11","A12","A13"],"price_paise":25000,"per_user_limit":4}')"
show_id="$(jq -er '.id' <<< "$show")"
echo "showId of burst2: $show_id"
export BASE_URL user_token user2_token user3_token
export show_id hot_show_id work HOT_USERS

echo "Testing $HOT_USERS users competing for seat A1..."

# HOT SEAT: distinct users, distinct idempotency keys, same seat.
# A FIFO barrier releases all child processes after they have reached
# the synchronization point. This improves start-time alignment.
mkfifo "$work/barrier"
exec 3<>"$work/barrier"

seq 1 "$HOT_REQUESTS" |
  xargs -P "$PARALLEL" -I '{}' sh -c '
    i="$1"

    case "$(( (i - 1) % HOT_USERS + 1 ))" in
      1) token="$user_token" ;;
      2) token="$user2_token" ;;
      3) token="$user3_token" ;;
    esac

    # Wait for the parent to release the workers.
    IFS= read -r _ < "$work/barrier"

    curl -sS -X POST "$BASE_URL/shows/$hot_show_id/reserve" \
      -H "Authorization: Bearer $token" \
      -H "Idempotency-Key: hot-$i" \
      -H "Content-Type: application/json" \
      -d "{\"seats\":[\"A11\"]}" \
      -o "$work/hot-$i.json" \
      -w "%{http_code}\n" > "$work/hot-$i.status"
  ' sh '{}' &
hot_pid=$!

# Release the barrier. Requests will race for A1.
# This is a start gate, not a guarantee of identical network arrival times.
sleep 1
for ((i=1; i<=HOT_REQUESTS; i++)); do
  printf 'go\n' >&3
done

wait "$hot_pid"
exec 3>&-

# RETRY: same user, key and payload; 20 concurrent requests.
seq 1 20 | xargs -P "$PARALLEL" -I '{}' sh -c '
  curl -sS -X POST "$BASE_URL/shows/$show_id/reserve" \
    -H "Authorization: Bearer $user_token" \
    -H "Idempotency-Key: retry-A2" \
    -H "Content-Type: application/json" \
    -d "{\"seats\":[\"A10\"]}" \
    -o "$work/retry-$1.json" \
    -w "%{http_code}\n" > "$work/retry-$1.status"
' sh '{}'

# BOOKING LIMIT: ten distinct seat attempts by one user.
seq 3 12 | xargs -P "$PARALLEL" -I '{}' sh -c '
  curl -sS -X POST "$BASE_URL/shows/$show_id/reserve" \
    -H "Authorization: Bearer $user_token" \
    -H "Idempotency-Key: limit-$1" \
    -H "Content-Type: application/json" \
    -d "{\"seats\":[\"A$1\"]}" \
    -o "$work/limit-$1.json" \
    -w "%{http_code}\n" > "$work/limit-$1.status"
' sh '{}'

# ATOMICITY: A1 is deliberately reserved first.
seed_status="$(curl -sS -X POST "$BASE_URL/shows/$show_id/reserve" \
  -H "Authorization: Bearer $user_token" \
  -H 'Idempotency-Key: seed-A1' \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A1"]}' \
  -o "$work/seed.json" -w '%{http_code}')"

if [[ "$seed_status" != 201 ]]; then
  echo "Failed to seed A1; atomicity precondition not met." >&2
  cat "$work/seed.json" >&2
  exit 1
fi

curl -sS -X POST "$BASE_URL/shows/$show_id/reserve" \
  -H "Authorization: Bearer $user_token" \
  -H 'Idempotency-Key: atomic-check' \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A1","A13"]}' \
  -o "$work/atomic.json" \
  -w '%{http_code}\n' > "$work/atomic.status"

state="$(curl -fsS "$BASE_URL/shows/$show_id")"
hot_state="$(curl -fsS "$BASE_URL/shows/$hot_show_id")"

count_status() {
  local pattern="$1"
  shift
  grep -hEc "$pattern" "$@" | awk '{s+=$1} END {print s+0}'
}

hot_201="$(count_status '^201$' "$work"/hot-*.status)"
hot_409="$(count_status '^409$' "$work"/hot-*.status)"
hot_5xx="$(count_status '^5[0-9][0-9]$' "$work"/hot-*.status)"

retry_201="$(count_status '^201$' "$work"/retry-*.status)"
retry_200="$(count_status '^200$' "$work"/retry-*.status)"

limit_201="$(count_status '^201$' "$work"/limit-*.status)"
limit_409="$(count_status '^409$' "$work"/limit-*.status)"

server_errors="$(count_status '^5[0-9][0-9]$' "$work"/*.status)"

echo
echo "Hot seat: 201=$hot_201 409=$hot_409 5xx=$hot_5xx"
echo "Retry:    201=$retry_201 200=$retry_200"
echo "Limit:    201=$limit_201 409=$limit_409"
echo "All tests: 5xx=$server_errors"

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

# Validate expected outcomes.
[[ "$hot_201" == 1 &&
   "$hot_409" == "$((HOT_REQUESTS - 1))" &&
   "$hot_5xx" == 0 &&
   "$retry_201" == 1 &&
   "$retry_200" == 19 &&
   "$limit_201" == 2 &&
   "$limit_409" == 8 &&
   "$server_errors" == 0 &&
   "$(cat "$work/atomic.status")" == 409 &&
   "$(jq -r '.confirmed' <<< "$state")" == 4 &&
   "$(jq -r '.available + .held + .confirmed == .total_seats' <<< "$state")" == true &&
   "$(jq -r '.seats[] | select(.seat_id=="A13") | .status' <<< "$state")" == available &&
   "$(jq -r '.confirmed' <<< "$hot_state")" == 1 &&
   "$(jq -r '.seats[] | select(.seat_id=="A1") | .status' <<< "$hot_state")" == confirmed &&
   "$(jq -r '.available + .held + .confirmed == .total_seats' <<< "$hot_state")" == true ]] || {
  echo "One or more assertions failed." >&2
  exit 1
}

echo "All assertions passed."

