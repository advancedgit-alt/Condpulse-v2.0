import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, mkdtempSync, writeFileSync, rmSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {createHash, X509Certificate} from 'node:crypto';
import {tmpdir} from 'node:os';
import {join} from 'node:path';

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
    assert.match(source,/node --test(?: --test-reporter=tap)? tests\/onboarding-operations\.test\.mjs tests\/onboarding-operations-policy\.test\.mjs/);
  assert.match(foundation,/node --test --test-reporter=tap tests\/onboarding-operations-java\.test\.mjs/);
  assert.match(workflow,/807025ad50d4ed219d2c9c7d299c004f824eb00cf7f65afef607d07b72e6cafa/);
  assert.match(workflow,/ssl|PostgreSQL CA/);
});


test('the actual onboarding CA gate pins the DER certificate and rejects other trust anchors', async t=>{
  const match=workflow.match(/^ {10}# BEGIN AUDITABLE_ONBOARDING_CA_VALIDATION\n([\s\S]*?)^ {10}# END AUDITABLE_ONBOARDING_CA_VALIDATION$/m);
  assert.ok(match,'The workflow must expose its actual CA validation block to this offline regression.');
  const validation=match[1].split('\n').map(line=>line.replace(/^ {10}/,'')).join('\n');
  const pin='807025ad50d4ed219d2c9c7d299c004f824eb00cf7f65afef607d07b72e6cafa';
  assert.equal(validation.split(pin).length-1,1,'Exactly one reviewed certificate fingerprint must be pinned.');
  const officialPem=readFileSync(new URL('./fixtures/supabase-prod-ca-2021.crt',import.meta.url));
  const fingerprint=pem=>createHash('sha256').update(new X509Certificate(pem).raw).digest('hex');
  assert.equal(fingerprint(officialPem),pin);
  assert.notEqual(createHash('sha256').update(officialPem).digest('hex'),pin,
    'The fixture must distinguish the DER fingerprint from the hash of PEM text.');
  const temporary=mkdtempSync(join(tmpdir(),'condpulse-onboarding-ca-'));
  const environment={PATH:process.env.PATH};

  function validate(certificate,expectedSuccess,script=validation) {
    const result=spawnSync('bash',['-euo','pipefail','-c',script],{
      env:{...environment,ca:certificate},encoding:'utf8',timeout:10000,maxBuffer:65536
    });
    assert.ifError(result.error);
    assert.equal(result.signal,null,'Certificate verification must finish normally.');
    assert.equal(typeof result.status,'number');
    if(expectedSuccess) assert.equal(result.status,0,result.stderr||result.stdout);
    else assert.notEqual(result.status,0,'An untrusted or malformed certificate must be rejected.');
  }

  try {
    const official=join(temporary,'official.crt');
    writeFileSync(official,officialPem,{mode:0o600});
    await t.test('accepts the official public PEM certificate',()=>validate(official,true));

    const reformatted=join(temporary,'extra-newline.crt');
    writeFileSync(reformatted,Buffer.concat([officialPem,Buffer.from('\n')]),{mode:0o600});
    await t.test('accepts the same certificate with a harmless trailing newline',()=>validate(reformatted,true));

    const malformed=join(temporary,'malformed.crt');
    writeFileSync(malformed,'not an X.509 certificate\n',{mode:0o600});
    await t.test('rejects malformed input',()=>validate(malformed,false));

    const other=join(temporary,'other.crt');
    const generated=spawnSync('openssl',['req','-x509','-newkey','ec','-pkeyopt','ec_paramgen_curve:P-256',
      '-nodes','-days','2','-subj','/CN=CondPulse offline regression unrelated CA',
      '-addext','basicConstraints=critical,CA:TRUE','-addext','keyUsage=critical,keyCertSign,cRLSign',
      '-keyout',join(temporary,'ephemeral-test-key.pem'),'-out',other],{
      env:environment,encoding:'utf8',timeout:10000,maxBuffer:65536
    });
    assert.ifError(generated.error);
    assert.equal(generated.signal,null);
    assert.equal(generated.status,0,'Offline synthetic CA generation must succeed.');
    const otherFingerprint=fingerprint(readFileSync(other));
    assert.notEqual(otherFingerprint,pin);
    await t.test('rejects a different valid self-signed CA with the production pin',()=>validate(other,false));
    // Test-only positive control: the same synthetic CA satisfies the other real checks.
    // Its fingerprint is never written back to the workflow or used with managed credentials.
    await t.test('proves the different CA was rejected by the pin',()=>
      validate(other,true,validation.replace(pin,otherFingerprint)));
  } finally {
    rmSync(temporary,{recursive:true,force:true});
  }
});

