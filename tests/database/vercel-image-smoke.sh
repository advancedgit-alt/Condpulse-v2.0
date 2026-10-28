#!/usr/bin/env bash
set -euo pipefail
test "${CP_DB_TEST_OK:-}" = DISPOSABLE
output=$(mktemp /tmp/condpulse-vercel-smoke.XXXXXX)
trap 'rm -f -- "$output"' EXIT
for mode in missing privileged profile; do
  args=()
  expected=VERCEL_API_RUNTIME_IDENTITY_REQUIRED
  case "$mode" in
    privileged) args+=(-e CP_ONBOARDING_AUTH_SECRET_KEY=CI_VERCEL_SECRET_SENTINEL); expected=VERCEL_API_PRIVILEGED_ENV_FORBIDDEN ;;
    profile) args+=(-e SPRING_PROFILES_ACTIVE=migrate); expected=VERCEL_API_PROFILE_EXACT ;;
  esac
  set +e
  docker run --rm --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
    --network none --cap-drop ALL --security-opt no-new-privileges \
    --memory 256m --cpus 1 --pids-limit 128 "${args[@]}" \
    -e DATABASE_PASSWORD=CI_VERCEL_SECRET_SENTINEL condpulse-vercel:ci > "$output" 2>&1
  status=$?
  set -e
  test "$status" = 1
  test "$(cat "$output")" = "$expected"
done
echo 'Vercel API: container builds and rejects privileged/configuration mistakes before Java.'
