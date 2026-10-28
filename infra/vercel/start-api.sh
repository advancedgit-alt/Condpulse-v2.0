#!/bin/sh
# Runtime-only entrypoint. Never source an env file or print credential values.
set -eu
fail() { printf '%s\n' "$1" >&2; exit 1; }

# Privileged identities/configuration belong exclusively to separate manual jobs.
# Inspect only names; values never enter logs or dynamic shell code.
for key in $(env | sed 's/=.*//'); do
  case "$key" in
    CP_MIGRATION_*|CP_ONBOARDING_*) fail VERCEL_API_PRIVILEGED_ENV_FORBIDDEN ;;
    SPRING_PROFILES_ACTIVE) ;;
    SPRING_*|CONDPULSE_*) fail VERCEL_API_CONFIGURATION_OVERRIDE_FORBIDDEN ;;
  esac
done
[ -z "${JAVA_TOOL_OPTIONS:-}${JDK_JAVA_OPTIONS:-}${_JAVA_OPTIONS:-}${SPRING_APPLICATION_JSON:-}${SPRING_CONFIG_IMPORT:-}${SPRING_CONFIG_LOCATION:-}${SPRING_CONFIG_ADDITIONAL_LOCATION:-}" ] || fail VERCEL_API_CONFIGURATION_OVERRIDE_FORBIDDEN
[ "${SPRING_PROFILES_ACTIVE:-api}" = api ] || fail VERCEL_API_PROFILE_EXACT
case "${DATABASE_USER:-}" in cp_runtime_*) ;; *) fail VERCEL_API_RUNTIME_IDENTITY_REQUIRED ;; esac
[ -n "${DATABASE_PASSWORD:-}" ] || fail VERCEL_API_DATABASE_PASSWORD_REQUIRED
[ -n "${DATABASE_URL:-}" ] || fail VERCEL_API_DATABASE_TARGET_INVALID
[ -n "${CP_VERCEL_DB_CA_BASE64:-}" ] || fail VERCEL_API_CA_REQUIRED
java -Xmx16m -XX:+UseSerialGC -cp /app/preflight VercelRuntimePreflight

# /tmp is ephemeral. Only the validated public CA is written here, never a secret.
umask 077
ca_tmp=$(mktemp /tmp/condpulse-db-ca.XXXXXX) || fail VERCEL_API_CA_PREPARATION_FAILED
trap 'rm -f "$ca_tmp"' EXIT HUP INT TERM
printf '%s' "$CP_VERCEL_DB_CA_BASE64" | base64 --decode > "$ca_tmp" 2>/dev/null || fail VERCEL_API_CA_ENCODING_INVALID
grep -q -- '-----BEGIN CERTIFICATE-----' "$ca_tmp" || fail VERCEL_API_CA_FORMAT_INVALID
[ ! -L /tmp/condpulse-db-ca.crt ] || fail VERCEL_API_CA_TARGET_INVALID
mv -f "$ca_tmp" /tmp/condpulse-db-ca.crt || fail VERCEL_API_CA_PREPARATION_FAILED
trap - EXIT HUP INT TERM
unset CP_VERCEL_DB_CA_BASE64

export SPRING_PROFILES_ACTIVE=api SPRING_FLYWAY_ENABLED=false SPRING_SQL_INIT_MODE=never
export CP_BFF_ENABLED="${CP_BFF_ENABLED:-false}" CP_RESERVATIONS_ENABLED="${CP_RESERVATIONS_ENABLED:-false}"
exec java -XX:MaxRAMPercentage=70 -jar /app/app.jar \
  --spring.profiles.active=api --spring.flyway.enabled=false --spring.sql.init.mode=never
