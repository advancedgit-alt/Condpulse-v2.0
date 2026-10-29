import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const workflow=readFileSync(new URL('../.github/workflows/onboarding-operations.yml',import.meta.url),'utf8');
const foundation=readFileSync(new URL('../.github/workflows/foundation.yml',import.meta.url),'utf8');
const [gate,operation]=workflow.split('  onboarding-operation:');

test('onboarding secrets require an exact manual default-branch gate and a dedicated environment',()=>{
  assert.ok(operation);
  assert.match(workflow,/on:\n  workflow_dispatch:/);
  assert.doesNotMatch(workflow,/pull_request_target|\n  push:|\n  schedule:/);
  for(const condition of ["github.event_name == 'workflow_dispatch'","github.repository == 'advancedgit-alt/Condpulse-v2.0'","github.ref == 'refs/heads/ccr-5e725112-3x8hyg'"])
    assert.equal(workflow.split(condition).length-1,2);
  assert.doesNotMatch(gate,/secrets\.|environment:/);
  assert.match(gate,/import \{validateRequest\} from '\.\/infra\/scripts\/onboarding-operations\/execute\.mjs'/);
  assert.match(operation,/needs: gate/);
  assert.match(operation,/environment:\n      name: condpulse-onboarding-operations/);
  assert.match(workflow,/permissions:\n  contents: read/);
  assert.doesNotMatch(workflow,/(?:contents|actions|id-token): write/);
  assert.match(workflow,/group: condpulse-database-operations-rpnrqfrfwywglvyykgrg\n  cancel-in-progress: false/);
  assert.equal((workflow.match(/ref: \$\{\{ github\.sha \}\}/g)||[]).length,2);
  assert.equal((workflow.match(/persist-credentials: false/g)||[]).length,2);
  for(const action of [...workflow.matchAll(/uses: (.+)/g)].map(x=>x[1])) assert.match(action,/@[a-f0-9]{40}$/);
});

test('only the final reviewed invocation receives private onboarding inputs',()=>{
  const secrets=[...workflow.matchAll(/secrets\.([A-Z0-9_]+)/g)].map(x=>x[1]).sort();
  assert.deepEqual(secrets,['CP_ONBOARDING_AUTH_SECRET_KEY','CP_ONBOARDING_DATABASE_PASSWORD','CP_ONBOARDING_DATABASE_USER','CP_ONBOARDING_MANIFEST_BASE64']);
  const build=operation.split('      - name: Execute only the selected onboarding operation')[0];
  assert.doesNotMatch(build,/secrets\./);
  assert.doesNotMatch(workflow,/CP_DATABASE_OPERATIONS_PASSWORD|CP_VERCEL_TOKEN|upload-artifact|actions\/cache|tee |set -x|vercel deploy|flyway|BootstrapBaseline|RuntimeConfiguration/);
  const runs=workflow.split(/\n        run: /).slice(1).map(x=>x.split(/\n      - |\n  onboarding-operation:/)[0]);
  for(const run of runs) assert.doesNotMatch(run,/\$\{\{ inputs\./);
  for(const input of ['operation','expected_commit','manifest_sha256','onboarding_ack','maintenance_confirmed'])
    assert.equal((workflow.match(new RegExp('\\$\\{\\{ inputs\\.'+input+' \\}\\}','g'))||[]).length,input==='expected_commit'?3:2);
  assert.equal((workflow.match(/CP_ONBOARDING_SOURCE_COMMIT: \$\{\{ github\.sha \}\}/g)||[]).length,2);
  assert.match(workflow,/run: node infra\/scripts\/onboarding-operations\/execute\.mjs/);
  assert.match(workflow,/if: always\(\)/);
  assert.match(workflow,/CLEANUP_TARGET_DENIED/);
});

test('CI exercises onboarding boundary tests and real Java protocol without managed secrets',()=>{
  for(const source of [workflow,foundation])
    assert.match(source,/node --test tests\/onboarding-operations\.test\.mjs tests\/onboarding-operations-policy\.test\.mjs/);
  assert.match(foundation,/node --test tests\/onboarding-operations-java\.test\.mjs/);
  assert.match(workflow,/807025ad50d4ed219d2c9c7d299c004f824eb00cf7f65afef607d07b72e6cafa/);
  assert.match(workflow,/ssl|PostgreSQL CA/);
});
