import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const workflow=readFileSync(new URL('../.github/workflows/bff-configuration.yml',import.meta.url),'utf8');
const foundation=readFileSync(new URL('../.github/workflows/foundation.yml',import.meta.url),'utf8');

test('BFF staging releases only its two protected inputs after exact source gate',()=>{
  assert.match(workflow,/on:\s*\n  workflow_dispatch:/);
  assert.doesNotMatch(workflow,/pull_request_target|\n  push:|\n  schedule:/);
  assert.equal((workflow.match(/github\.event_name == 'workflow_dispatch'/g)||[]).length,2);
  assert.equal((workflow.match(/github\.repository == 'advancedgit-alt\/Condpulse-v2\.0'/g)||[]).length,2);
  assert.equal((workflow.match(/github\.ref == 'refs\/heads\/ccr-5e725112-3x8hyg'/g)||[]).length,2);
  assert.match(workflow,/permissions:\n  contents: read/);
  assert.doesNotMatch(workflow,/(?:contents|actions|id-token): write/);
  const gate=workflow.split('  bff-configuration:')[0];
  assert.doesNotMatch(gate,/secrets\.|environment:/);
  assert.match(gate,/import \{validateRequest\}/);
  assert.match(workflow,/needs: gate/);
  assert.match(workflow,/name: condpulse-database-operations/);
  const secrets=[...workflow.matchAll(/secrets\.([A-Z_]+)/g)].map(x=>x[1]).sort();
  assert.deepEqual(secrets,['CP_SUPABASE_PUBLISHABLE_KEY','CP_VERCEL_TOKEN']);
  assert.equal((workflow.match(/CP_BFF_CONFIG_SOURCE_COMMIT: \$\{\{ github\.sha \}\}/g)||[]).length,2);
  assert.equal((workflow.match(/ref: \$\{\{ github\.sha \}\}/g)||[]).length,2);
  assert.equal((workflow.match(/persist-credentials: false/g)||[]).length,2);
  for(const action of [...workflow.matchAll(/uses: (.+)/g)].map(x=>x[1]))assert.match(action,/@[a-f0-9]{40}$/);
  assert.match(workflow,/cancel-in-progress: false/);
  assert.match(workflow,/group: condpulse-database-operations-rpnrqfrfwywglvyykgrg/);
  assert.doesNotMatch(workflow,/upload-artifact|actions\/cache|curl|vercel deploy|npm install|DATABASE_PASSWORD|CP_DATABASE_OPERATIONS_PASSWORD/);
  // Inputs are data in env; no input expression is interpolated into shell source.
  const runs=workflow.split(/\n        run: /).slice(1).map(x=>x.split(/\n      - |\n  bff-configuration:/)[0]);
  for(const run of runs)assert.doesNotMatch(run,/\$\{\{ inputs\./);
});

test('credential-free CI runs BFF tests; protected step executes only reviewed helper',()=>{
  assert.match(foundation,/node --test[^\n]*tests\/bff-configuration\.test\.mjs[^\n]*tests\/bff-configuration-policy\.test\.mjs/);
  assert.match(workflow,/run: node --test tests\/bff-configuration\.test\.mjs tests\/bff-configuration-policy\.test\.mjs/);
  assert.match(workflow,/run: node infra\/scripts\/bff-configuration\/stage\.mjs\s*$/);
});
