// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { accessHomologation } from '../../api/accessCatalog';
import { homologationFixture } from '../../api/accessCatalog.fixtures';
import { decisionBody, dossierBody, emptyDecision, emptyDossier, safeLink, uuidLines } from './facial';

const ID = '50000000-0000-4000-8000-0000000000f1';
const EVIDENCE = '60000000-0000-4000-8000-0000000000f1';
const MODEL = '30000000-0000-4000-8000-0000000000f1';

describe('dossiê de homologação', () => {
  const filled = () => ({ ...emptyDossier(MODEL), firmwareVersion: '1.2.3', adapterName: 'controlid-rest', adapterVersion: '0.1.0', apiContractVersion: 'v1',
    operatingMode: 'EDGE_ONLINE' as const, topology: 'LOCAL_GATEWAY' as const, reason: 'Dossiê inicial sintético' });
  it('monta as 12 capacidades e os 10 limites, desconhecido como null', () => {
    const draft = filled();
    draft.capabilities.faceEnrollment = { documented: 'yes', observed: 'no' };
    draft.limits.faces = '3000';
    const body = dossierBody(ID, draft);
    if (typeof body === 'string') throw new Error(body);
    expect(Object.keys(body.capabilities)).toHaveLength(12);
    expect(body.capabilities.faceEnrollment).toEqual({ documented: true, observed: false });
    expect(body.capabilities.secureTransport).toEqual({ documented: null, observed: null });
    expect(Object.keys(body.limits)).toHaveLength(10);
    expect(body.limits).toMatchObject({ faces: 3000, users: null });
  });
  it('recusa firmware com curinga, adaptador inválido, limite negativo e motivo curto', () => {
    expect(dossierBody(ID, { ...filled(), firmwareVersion: '1.*' })).toMatch(/Firmware exato/);
    expect(dossierBody(ID, { ...filled(), adapterName: 'Control iD' })).toMatch(/Adaptador/);
    const draft = filled(); draft.limits.cards = '-1';
    expect(dossierBody(ID, draft)).toMatch(/Limites/);
    expect(dossierBody(ID, { ...filled(), reason: 'curto' })).toMatch(/motivo/);
  });
  it('evidências: UUID por linha, sem repetição', () => {
    expect(uuidLines(`${EVIDENCE}\n\n`)).toEqual({ values: [EVIDENCE] });
    expect(uuidLines(`${EVIDENCE}\n${EVIDENCE.toUpperCase()}`)).toEqual({ error: 'Há referência de evidência repetida.' });
    expect(uuidLines('senha123')).toHaveProperty('error');
  });
});

describe('decisão da homologação', () => {
  const trial = accessHomologation(homologationFixture({ status: 'EM_ENSAIO', version: 3 }));
  it('aprovação exige evidência e só lista capacidade observada; leva a versão lida', () => {
    const draft = { ...emptyDecision(), decision: 'APPROVE_LAB' as const, reason: 'Ensaio de bancada concluído' };
    expect(decisionBody(ID, draft, trial)).toMatch(/evidência/);
    const body = decisionBody(ID, { ...draft, evidence: EVIDENCE, approved: ['faceEnrollment'], restrictions: 'Só portas internas' }, trial);
    expect(body).toEqual({ decisionId: ID, decision: 'APPROVE_LAB', reason: 'Ensaio de bancada concluído', evidenceRefs: [EVIDENCE],
      approvedCapabilityNames: ['faceEnrollment'], reviewDueAt: null, restrictions: ['Só portas internas'], expectedVersion: 3 });
    const unobserved = accessHomologation(homologationFixture({ status: 'EM_ENSAIO', capabilities: Object.fromEntries(Object.entries(homologationFixture().capabilities as object)
      .map(([name, value]) => [name, { ...(value as object), observed: name === 'faceEnrollment' ? false : true }])) }));
    expect(decisionBody(ID, { ...draft, evidence: EVIDENCE, approved: ['faceEnrollment'] }, unobserved)).toMatch(/observada/);
  });
  it('reprovação não leva capacidades, restrições nem revisão; revisão só futura', () => {
    const reject = { ...emptyDecision(), decision: 'REJECT' as const, reason: 'Falhou no ensaio de queda' };
    expect(decisionBody(ID, { ...reject, approved: ['faceEnrollment'] }, trial)).toMatch(/Só aprovação/);
    expect(decisionBody(ID, { ...reject, restrictions: 'x' }, trial)).toMatch(/Restrições só/);
    expect(decisionBody(ID, { ...reject, reviewDue: '2031-01-01T10:00' }, trial)).toMatch(/revisão só vale/);
    expect(decisionBody(ID, { ...emptyDecision(), decision: 'APPROVE_LAB', reason: 'Ensaio concluído ok', evidence: EVIDENCE, reviewDue: '2000-01-01T10:00' }, trial))
      .toMatch(/futura/);
  });
  it('só https vira link', () => {
    expect(safeLink('https://www.controlid.com.br/docs')).toBe('https://www.controlid.com.br/docs');
    expect(safeLink('javascript:alert(1)')).toBeNull();
    expect(safeLink('http://exemplo.invalid')).toBeNull();
    expect(safeLink('https://user:pass@exemplo.invalid')).toBeNull();
  });
});
