#!/usr/bin/env bash
# Sem conexao ou credenciais: verifica launcher separado e erro sanitizado.
set -euo pipefail
test "${CP_DB_TEST_OK:-}" = DISPOSABLE
artifact_manifest=$(docker run --rm --read-only --network none --cap-drop ALL \
  --security-opt no-new-privileges --entrypoint sha256sum condpulse-baseline:ci \
  /app/classes/bootstrap/foundation-manifest.json)
test "$artifact_manifest" = '45fd75474bbbc505d6cea3232b19b19c03f82c6f2a8e5bc57bc65dbb00559d5e  /app/classes/bootstrap/foundation-manifest.json'
docker run --rm --read-only --network none --cap-drop ALL \
  --security-opt no-new-privileges --entrypoint /bin/sh condpulse-baseline:ci -c \
  'test -s /app/classes/db/migration/V001__foundation.sql && test -s /app/classes/db/migration/V002__roles_and_rls.sql && test -s /app/classes/bootstrap/attestation-snapshot.sql'
output=$(mktemp /tmp/condpulse-baseline-smoke.XXXXXX)
errors=$(mktemp /tmp/condpulse-baseline-stderr.XXXXXX)
trap 'rm -f -- "$output" "$errors"' EXIT
set +e
docker run --rm --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --network none --cap-drop ALL --security-opt no-new-privileges --memory 256m \
  --cpus 1 --pids-limit 128 -e DATABASE_PASSWORD=CI_BASELINE_SECRET_SENTINEL \
  condpulse-baseline:ci > "$output" 2> "$errors"
status=$?
set -e
test "$status" = 1
node --input-type=module - "$output" "$errors" <<'NODE'
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const text=readFileSync(process.argv[2],'utf8');
const messages=text+readFileSync(process.argv[3],'utf8');
assert.equal(messages.includes('CI_BASELINE_SECRET_SENTINEL'),false);
assert.equal(messages.includes('Exception'),false);
const result=JSON.parse(text);
assert.equal(result.eligible,false);
assert.equal(result.baselineCreated,false);
assert.deepEqual(result.codes,['PROJECT_NOT_ALLOWED']);
NODE
echo 'Imagem baseline: launcher isolado, sem rede, sem escrita e erro sanitizado verificados.'
