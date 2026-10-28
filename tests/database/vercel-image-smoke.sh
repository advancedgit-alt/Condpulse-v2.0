#!/usr/bin/env bash
# No database/network connection: prove the actual Vercel launcher fails closed.
set -euo pipefail
image=${1:-condpulse-vercel:ci}
[[ "$image" =~ ^condpulse-vercel:ci$ ]] || exit 1
project=rpnrqfrfwywglvyykgrg
issuer="https://$project.supabase.co/auth/v1"
jdbc="jdbc:postgresql://db.$project.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=/tmp/condpulse-db-ca.crt"
common=(--rm --network none --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m --cap-drop ALL --security-opt no-new-privileges
  -e DATABASE_USER=cp_runtime_vercel_ci -e DATABASE_PASSWORD=SYNTHETIC_TEST_ONLY_password
  -e "DATABASE_URL=$jdbc" -e "CP_JWT_ISSUER=$issuer" -e "CP_JWT_JWK_SET_URI=$issuer/.well-known/jwks.json"
  -e CP_BFF_ORIGIN=https://condpulse.vercel.app -e CP_VERCEL_DB_CA_BASE64=SYNTHETIC_PUBLIC_CA_ONLY)

# Positive preflight is silent and does not connect to Postgres or start Spring.
output=$(docker run "${common[@]}" --entrypoint java "$image" -Xmx16m -cp /app/preflight VercelRuntimePreflight 2>&1)
test -z "$output"

reject() {
  local expected=$1
  shift
  local output
  if output=$(docker run "${common[@]}" "$@" "$image" 2>&1); then
    echo 'Vercel launcher accepted an invalid runtime configuration' >&2; exit 1
  fi
  test "$output" = "$expected"
}
reject VERCEL_API_DATABASE_PROJECT_EXACT -e "DATABASE_URL=${jdbc/db.$project.supabase.co/attacker.example.com}"
reject VERCEL_API_DATABASE_PROJECT_EXACT -e "DATABASE_URL=${jdbc/$project/bbbbbbbbbbbbbbbbbbbb}"
reject VERCEL_API_DATABASE_TARGET_INVALID -e "DATABASE_URL=${jdbc/:5432\//:6543/}"
reject VERCEL_API_DATABASE_TARGET_INVALID -e "DATABASE_URL=${jdbc/\/postgres?/\/another?}"
reject VERCEL_API_DATABASE_TARGET_INVALID -e "DATABASE_URL=${jdbc}#SYNTHETIC_SENTINEL"
reject VERCEL_API_DATABASE_TLS_REQUIRED -e "DATABASE_URL=jdbc:postgresql://db.$project.supabase.co:5432/postgres?sslmode=verify-full&options=SYNTHETIC_SENTINEL&sslrootcert=/tmp/condpulse-db-ca.crt"
reject VERCEL_API_DATABASE_TLS_REQUIRED -e "DATABASE_URL=$jdbc&sslmode=disable"
reject VERCEL_API_DATABASE_TLS_REQUIRED -e "DATABASE_URL=${jdbc/sslmode=/%73slmode=}"
reject VERCEL_API_RUNTIME_IDENTITY_REQUIRED -e "DATABASE_URL=${jdbc/db.$project.supabase.co/aws-0-sa-east-1.pooler.supabase.com}" -e DATABASE_USER=cp_runtime_vercel_ci.bbbbbbbbbbbbbbbbbbbb
reject VERCEL_API_JWKS_EXACT -e CP_JWT_JWK_SET_URI=https://attacker.example.com/jwks
reject VERCEL_API_ISSUER_EXACT -e CP_JWT_ISSUER=https://attacker.example.com/auth/v1
reject VERCEL_API_BFF_ORIGIN_REQUIRED -e CP_BFF_ORIGIN=https://condpulse.vercel.app/path
reject VERCEL_API_PRIVILEGED_ENV_FORBIDDEN -e SUPABASE_SERVICE_ROLE_KEY=SYNTHETIC_SENTINEL
reject VERCEL_API_CONFIGURATION_OVERRIDE_FORBIDDEN -e SPRING_DATASOURCE_USERNAME=postgres
synthetic_key=$(printf 'abcdefghijklmnopqrstuvwxyz012345' | base64 | tr -d '\n')
reject VERCEL_API_KEYS_MUST_DIFFER -e CP_BFF_ENABLED=true -e CP_SUPABASE_PUBLISHABLE_KEY=sb_publishable_SYNTHETIC_TEST_ONLY_1234567890 \
  -e "CP_BFF_ENCRYPTION_KEY=$synthetic_key" -e "CP_BFF_RATE_LIMIT_KEY=$synthetic_key"
echo 'Vercel launcher: project/JDBC/TLS/JWT/runtime identity guards verified without network.'
