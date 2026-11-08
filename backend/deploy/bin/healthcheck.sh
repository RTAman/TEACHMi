#!/bin/sh
set -eu
# DB-backed Spring readiness only; no authentication or AI endpoints.
body=$(curl --noproxy '*' --fail --silent --show-error --connect-timeout 2 --max-time 5 http://127.0.0.1:8080/actuator/health)
printf '%s' "$body" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'
