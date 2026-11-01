#!/bin/sh
# Worker contínuo na Fly (docs/FLY_WORKER.md). Nunca lê arquivo de ambiente nem imprime valor de credencial.
set -eu
fail() { printf '%s\n' "$1" >&2; exit 1; }

# Identidades privilegiadas, chaves da API/BFF e sobrescritas do Spring não entram no worker. Só nomes são inspecionados.
for key in $(env | sed 's/=.*//'); do
  case "$key" in
    CP_MIGRATION_*|CP_ONBOARDING_*|CP_BFF_*|CP_STORAGE_*|CP_PARCEL_CODE_KEY|CP_VERCEL_*|*SERVICE_ROLE*|*SECRET_KEY*) fail FLY_WORKER_PRIVILEGED_ENV_FORBIDDEN ;;
    SPRING_PROFILES_ACTIVE) ;;
    SPRING_*|CONDPULSE_*|SERVER_*|MANAGEMENT_*) fail FLY_WORKER_CONFIGURATION_OVERRIDE_FORBIDDEN ;;
  esac
done
[ -z "${JAVA_TOOL_OPTIONS:-}${JDK_JAVA_OPTIONS:-}${_JAVA_OPTIONS:-}" ] || fail FLY_WORKER_CONFIGURATION_OVERRIDE_FORBIDDEN
[ "${SPRING_PROFILES_ACTIVE:-worker}" = worker ] || fail FLY_WORKER_PROFILE_EXACT
# Destino fixo: pooler de São Paulo do projeto de homologação, TLS verify-full com a CA gravada abaixo.
[ "${DATABASE_URL:-}" = 'jdbc:postgresql://aws-1-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=/tmp/condpulse-db-ca.crt' ] \
  || fail FLY_WORKER_DATABASE_TARGET_EXACT
# Login exclusivo do worker (membro só de cp_worker), nunca o grupo nem um login runtime da API.
printf '%s' "${DATABASE_USER:-}" | grep -Eqx 'cp_worker_[a-f0-9]{32}\.rpnrqfrfwywglvyykgrg' || fail FLY_WORKER_IDENTITY_REQUIRED
[ -n "${DATABASE_PASSWORD:-}" ] || fail FLY_WORKER_DATABASE_PASSWORD_REQUIRED
[ -n "${CP_DB_CA_BASE64:-}" ] || fail FLY_WORKER_CA_REQUIRED
# Pool 1-2: o teto do login é 2 (ADR-0006); vazio ou ausente é inválido.
case "${DB_POOL_SIZE:-}" in 1|2) ;; *) fail FLY_WORKER_POOL_LIMIT ;; esac
for value in "${CP_WORKER_OUTBOX_ENABLED:-}" "${CP_WORKER_WAITLIST_ENABLED:-}" "${CP_WORKER_STAYS_ENABLED:-}"; do
  case "$value" in true|false) ;; *) fail FLY_WORKER_FLAG_INVALID ;; esac
done
# Reconciliação de credenciais depende do parceiro físico (CP-044): fica desligada aqui.
[ "${CP_WORKER_ACCESS_SYNC_ENABLED:-}" = false ] || fail FLY_WORKER_ACCESS_SYNC_FORBIDDEN
printf '%s' "${CP_WORKER_POLL_MILLIS:-}" | grep -Eqx '[1-9][0-9]{2,4}' || fail FLY_WORKER_POLL_INVALID
[ "$CP_WORKER_POLL_MILLIS" -ge 500 ] && [ "$CP_WORKER_POLL_MILLIS" -le 60000 ] || fail FLY_WORKER_POLL_INVALID
printf '%s' "${CP_WORKER_WAITLIST_MILLIS:-}" | grep -Eqx '[1-9][0-9]{4,6}' || fail FLY_WORKER_WAITLIST_INTERVAL_INVALID
[ "$CP_WORKER_WAITLIST_MILLIS" -ge 10000 ] && [ "$CP_WORKER_WAITLIST_MILLIS" -le 3600000 ] || fail FLY_WORKER_WAITLIST_INTERVAL_INVALID
# Alertas de permanência (V055): o intervalo é a tolerância do alerta, de 10 s a 10 min.
printf '%s' "${CP_WORKER_STAYS_MILLIS:-}" | grep -Eqx '[1-9][0-9]{4,5}' || fail FLY_WORKER_STAYS_INTERVAL_INVALID
[ "$CP_WORKER_STAYS_MILLIS" -ge 10000 ] && [ "$CP_WORKER_STAYS_MILLIS" -le 600000 ] || fail FLY_WORKER_STAYS_INTERVAL_INVALID

# /tmp é efêmero. Só a CA pública validada é gravada aqui, nunca um segredo.
umask 077
ca_tmp=$(mktemp /tmp/condpulse-db-ca.XXXXXX) || fail FLY_WORKER_CA_PREPARATION_FAILED
trap 'rm -f "$ca_tmp"' EXIT HUP INT TERM
printf '%s' "$CP_DB_CA_BASE64" | base64 --decode > "$ca_tmp" 2>/dev/null || fail FLY_WORKER_CA_ENCODING_INVALID
grep -q -- '-----BEGIN CERTIFICATE-----' "$ca_tmp" || fail FLY_WORKER_CA_FORMAT_INVALID
[ ! -L /tmp/condpulse-db-ca.crt ] || fail FLY_WORKER_CA_TARGET_INVALID
mv -f "$ca_tmp" /tmp/condpulse-db-ca.crt || fail FLY_WORKER_CA_PREPARATION_FAILED
trap - EXIT HUP INT TERM
unset CP_DB_CA_BASE64

export SPRING_PROFILES_ACTIVE=worker SPRING_FLYWAY_ENABLED=false SPRING_SQL_INIT_MODE=never
exec java -XX:MaxRAMPercentage=70 -jar /app/app.jar \
  --spring.profiles.active=worker --spring.flyway.enabled=false --spring.sql.init.mode=never
