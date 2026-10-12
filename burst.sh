#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:?Usage: ./burst.sh BASE_URL}"
: "${ADMIN_USERNAME:=siddnaya}" "${ADMIN_PASSWORD:?Set ADMIN_PASSWORD}" "${USER_USERNAME:=siddnaya1}" "${USER_PASSWORD:?Set USER_PASSWORD}"
HOT_REQUESTS="${HOT_REQUESTS:-1000}"
PARALLEL="${PARALLEL:-32}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

token() {
  curl -fsS -X POST "$BASE_URL/auth/token" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg username "$1" --arg password "$2" '{username:$username,password:$password}')" | jq -er '.access_token'
}
admin_token="$(token "$ADMIN_USERNAME" "$ADMIN_PASSWORD")"
user_token="$(token "$USER_USERNAME" "$USER_PASSWORD")"
show="$(curl -fsS -X POST "$BASE_URL/shows" -H "Authorization: Bearer $admin_token" \
  -H 'Content-Type: application/json' -d '{"name":"burst","seats":["A1","A2","A3","A4","A5","A6","A7","A8","A9","A10","A11","A12","A13"],"price_paise":25000,"per_user_limit":4}')"
show_id="$(jq -er '.id' <<< "$show")"
export BASE_URL user_token show_id work

seq 1 "$HOT_REQUESTS" | xargs -P "$PARALLEL" -I '{}' sh -c '
  curl -sS -X POST "$BASE_URL/shows/$show_id/reserve" -H "Authorization: Bearer $user_token" \
    -H "Idempotency-Key: hot-$1" -H "Content-Type: application/json" -d "{\"seats\":[\"A1\"]}" \
    -o "$work/hot-$1.json" -w "%{http_code}\n" > "$work/hot-$1.status"
' sh '{}'
seq 1 20 | xargs -P "$PARALLEL" -I '{}' sh -c '
  curl -sS -X POST "$BASE_URL/shows/$show_id/reserve" -H "Authorization: Bearer $user_token" \
    -H "Idempotency-Key: retry-A2" -H "Content-Type: application/json" -d "{\"seats\":[\"A2\"]}" \
    -o "$work/retry-$1.json" -w "%{http_code}\n" > "$work/retry-$1.status"
' sh '{}'
seq 3 12 | xargs -P "$PARALLEL" -I '{}' sh -c '
  curl -sS -X POST "$BASE_URL/shows/$show_id/reserve" -H "Authorization: Bearer $user_token" \
    -H "Idempotency-Key: limit-$1" -H "Content-Type: application/json" -d "{\"seats\":[\"A$1\"]}" \
    -o "$work/limit-$1.json" -w "%{http_code}\n" > "$work/limit-$1.status"
' sh '{}'
curl -sS -X POST "$BASE_URL/shows/$show_id/reserve" -H "Authorization: Bearer $user_token" \
  -H 'Idempotency-Key: atomic-check' -H 'Content-Type: application/json' -d '{"seats":["A1","A13"]}' \
  -o "$work/atomic.json" -w '%{http_code}\n' > "$work/atomic.status"
state="$(curl -fsS "$BASE_URL/shows/$show_id")"
hot_201="$(cat "$work"/hot-*.status | grep -c '^201$' || true)"
hot_409="$(cat "$work"/hot-*.status | grep -c '^409$' || true)"
retry_201="$(cat "$work"/retry-*.status | grep -c '^201$' || true)"
retry_200="$(cat "$work"/retry-*.status | grep -c '^200$' || true)"
limit_201="$(cat "$work"/limit-*.status | grep -c '^201$' || true)"
limit_409="$(cat "$work"/limit-*.status | grep -c '^409$' || true)"
server_errors="$(cat "$work"/*.status | grep -Ec '^5[0-9][0-9]$' || true)"
echo "hot seat: 201=$hot_201 409=$hot_409"
echo "retry: 201=$retry_201 200=$retry_200"
echo "limit: 201=$limit_201 409=$limit_409"
echo "all requests: 5xx=$server_errors"
echo "$state" | jq '{total_seats,available,held,confirmed,reconciled:(.available+.held+.confirmed==.total_seats)}'
[[ "$hot_201" == 1 && "$hot_409" == "$((HOT_REQUESTS-1))" && "$retry_201" == 1 && "$retry_200" == 19 && \
   "$limit_201" == 2 && "$limit_409" == 8 && "$server_errors" == 0 && \
   "$(cat "$work/atomic.status")" == 409 && "$(jq -r '.confirmed' <<< "$state")" == 4 && \
   "$(jq -r '.available + .held + .confirmed == .total_seats' <<< "$state")" == true && \
   "$(jq -r '.seats[] | select(.seat_id == "A13") | .status' <<< "$state")" == available ]] || exit 1
