#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

if [ -z "${CLOUDFLARE_API_TOKEN:-}" ]; then
  printf '%s\n' "当前终端没有 CLOUDFLARE_API_TOKEN，请在已设置 Token 的终端运行。" >&2
  exit 1
fi

export CLOUDFLARE_API_TOKEN
export CLOUDFLARE_ACCOUNT_ID="65c082d9a0e468603bc2707709713601"
export CLOUDFLARE_D1_DATABASE_ID="ec93d261-a08a-499b-9232-d451351dd429"
printf '%s\n' "Starting recipe backend with user-information (${CLOUDFLARE_D1_DATABASE_ID})."
exec ./mvnw spring-boot:run
