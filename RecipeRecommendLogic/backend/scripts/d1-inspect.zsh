#!/bin/zsh
set -euo pipefail

: "${CLOUDFLARE_ACCOUNT_ID:?Set CLOUDFLARE_ACCOUNT_ID first}"
: "${CLOUDFLARE_API_TOKEN:?Set CLOUDFLARE_API_TOKEN first}"

request() {
    curl --silent --show-error --connect-timeout 10 --max-time 30 \
        --header "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" \
        "$@" | python3 -c '
import json, sys
response = json.load(sys.stdin)
print(json.dumps(response, ensure_ascii=False, indent=2))
if response.get("success") is not True:
    sys.exit("Cloudflare reported failure; inspect the errors above.")
for item in response.get("result", []):
    if isinstance(item, dict) and item.get("success") is False:
        sys.exit("A D1 query failed; inspect the result above.")
'
}

D1_BASE_URL="https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/d1/database"

if [[ -z "${CLOUDFLARE_D1_DATABASE_ID:-}" ]]; then
    print -- "Listing databases accessible to this token (page 1, at most 100)."
    request "${D1_BASE_URL}?page=1&per_page=100"
    print -- "Set CLOUDFLARE_D1_DATABASE_ID to the chosen database uuid, then run again."
    exit 0
fi

D1_QUERY_URL="${D1_BASE_URL}/${CLOUDFLARE_D1_DATABASE_ID}/query"

print -- "Checking the chosen database with SELECT 1."
request --header "Content-Type: application/json" \
    --data '{"sql":"SELECT 1 AS ok"}' \
    "$D1_QUERY_URL"

print -- "Reading table definitions."
request --header "Content-Type: application/json" \
    --data @- "$D1_QUERY_URL" <<'JSON'
{"sql":"SELECT name, sql FROM sqlite_schema WHERE type = 'table' ORDER BY name"}
JSON
