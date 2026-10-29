import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
import {execute,TARGET} from '../infra/scripts/onboarding-operations/execute.mjs';

const jar=resolve('apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar');
const javaHome=process.env.JAVA_HOME;
const available=Boolean(javaHome && existsSync(jar) && existsSync(join(javaHome,'bin','java')));

test('reviewed wrapper invokes real Java parser and returns only controlled failure before provider access',{
  skip:!available && process.env.CP_ONBOARDING_JAVA_TEST_REQUIRED!=='true' ? 'Requires built JAR and Java25; mandatory in CI' : false,
},()=>{
  assert.ok(available,'CI requires the built JAR and Java25');
  const runner=mkdtempSync(join(tmpdir(),'condpulse-onboarding-java-'));
  try {
    const root=join(runner,'condpulse-onboarding-operations');
    const classes=join(root,'classes');
    mkdirSync(classes,{recursive:true});
    const extract=spawnSync(join(javaHome,'bin','jar'),['-xf',jar],{cwd:classes,encoding:'utf8',timeout:30000,maxBuffer:16384,windowsHide:true});
    assert.equal(extract.status,0);
    // Intentionally invalid synthetic manifest. The production CLI must reject it before Auth/JDBC.
    const bytes=Buffer.from('{"syntheticInvalidManifest":true}','utf8');
    const sha='1'.repeat(40), manifestHash=createHash('sha256').update(bytes).digest('hex');
    const source={GITHUB_EVENT_NAME:'workflow_dispatch',GITHUB_REPOSITORY:TARGET.repository,GITHUB_REF:TARGET.ref,
      GITHUB_SHA:sha,CP_ONBOARDING_SOURCE_COMMIT:sha,CP_ONBOARDING_EXPECTED_COMMIT:sha,
      CP_ONBOARDING_OPERATION:'inspect',CP_ONBOARDING_MANIFEST_SHA256:manifestHash,
      CP_ONBOARDING_MANIFEST_BASE64:bytes.toString('base64'),CP_ONBOARDING_ACK:'',MAINTENANCE_CONFIRMED:'false',
      CP_ONBOARDING_DATABASE_USER:`cp_onboarding_ci.${TARGET.project}`,
      CP_ONBOARDING_DATABASE_PASSWORD:'synthetic_onboarding_ci_only_1234',
      CP_ONBOARDING_AUTH_SECRET_KEY:'sb_secret_synthetic_onboarding_ci_only_1234',
      RUNNER_TEMP:runner,JAVA_HOME:javaHome};
    const outcome=execute(source);
    assert.equal(outcome.exitCode,1);
    assert.equal(outcome.code,'');
    assert.deepEqual(outcome.codes,['ONBOARDING_CONFIGURATION_OR_MANIFEST_INVALID']);
    assert.equal(outcome.writeOutcome,'NOT_ATTEMPTED');
    assert.equal(outcome.applied,false);
    assert.equal(outcome.counts,null);
    assert.equal(outcome.fingerprint,'');
    assert.deepEqual(readdirSync(root).sort(),['classes']);
    assert.doesNotMatch(JSON.stringify(outcome),/synthetic|sb_secret_|cp_onboarding_ci/);
  } finally { rmSync(runner,{recursive:true,force:true}); }
});
