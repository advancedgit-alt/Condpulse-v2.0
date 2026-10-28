#!/usr/bin/env bash
# Disposable CI only: the manual CLI must fail safely before any network access.
set -euo pipefail
test "${CP_DB_TEST_OK:-}" = DISPOSABLE
output=$(mktemp /tmp/condpulse-onboarding-smoke.XXXXXX)
errors=$(mktemp /tmp/condpulse-onboarding-stderr.XXXXXX)
trap 'rm -f -- "$output" "$errors"' EXIT
set +e
docker run --rm --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --network none --cap-drop ALL --security-opt no-new-privileges --memory 256m \
  --cpus 1 --pids-limit 128 -e DATABASE_PASSWORD=CI_ONBOARDING_SECRET_SENTINEL \
  -e CP_ONBOARDING_AUTH_SECRET_KEY=CI_AUTH_SECRET_SENTINEL \
  condpulse-onboarding:ci > "$output" 2> "$errors"
status=$?
set -e
test "$status" = 1
node --input-type=module - "$output" "$errors" <<'NODE'
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const text=readFileSync(process.argv[2],'utf8');
const messages=text+readFileSync(process.argv[3],'utf8');
for(const forbidden of ['CI_ONBOARDING_SECRET_SENTINEL','CI_AUTH_SECRET_SENTINEL','Exception','Spring Boot'])
  assert.equal(messages.includes(forbidden),false);
const result=JSON.parse(text);
assert.equal(result.eligible,false);
assert.equal(result.applied,false);
assert.equal(result.writeOutcome,'NOT_ATTEMPTED');
assert.deepEqual(result.codes,['ONBOARDING_CONFIGURATION_OR_MANIFEST_INVALID']);
NODE
echo 'Onboarding CLI: isolated launcher and sanitized failure verified.'
