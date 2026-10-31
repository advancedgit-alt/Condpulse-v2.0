// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { accessHomologation, accessModelProfile, allowedDecisions, homologationDetail } from './accessCatalog';

import { homologationFixture, profileFixture, caps } from './accessCatalog.fixtures';

describe('catálogo do acesso facial', () => {
  it('lê perfil documental com fontes e valores declarados', () => {
    const profile = accessModelProfile(profileFixture());
    expect(profile.sources[0]).toEqual({ id: 'CI01', title: 'Control iD iDFace', url: 'https://www.controlid.com.br/', consultedOn: '2026-10-30' });
    expect(() => accessModelProfile(profileFixture({ manufacturer: 'OUTRO' }))).toThrow();
    expect(() => accessModelProfile(profileFixture({ documentHash: 'x' }))).toThrow();
  });
  it('homologação: vigente só aprovada e capacidade aprovada só se observada', () => {
    expect(accessHomologation(homologationFixture()).status).toBe('CANDIDATO_DOCUMENTADO');
    expect(() => accessHomologation(homologationFixture({ current: true }))).toThrow();
    expect(() => accessHomologation(homologationFixture({ capabilities: caps(false), approvedCapabilities: ['faceEnrollment'] }))).toThrow();
    expect(() => accessHomologation(homologationFixture({ capabilities: { senha: { documented: true, observed: true } } }))).toThrow();
    expect(() => accessHomologation(homologationFixture({ limits: { faces: -1 } }))).toThrow();
  });
  it('detalhe precisa ser da homologação pedida', () => {
    expect(() => homologationDetail({ homologation: homologationFixture(), decisions: [] }, '40000000-0000-4000-8000-0000000000f2')).toThrow();
  });
  it('transições iguais às do banco', () => {
    expect(allowedDecisions('CANDIDATO_DOCUMENTADO')).toEqual(['START_TRIAL', 'REJECT']);
    expect(allowedDecisions('EM_ENSAIO')).toEqual(['APPROVE_LAB', 'REJECT']);
    expect(allowedDecisions('HOMOLOGADO')).toEqual(['SUSPEND']);
    expect(allowedDecisions('SUSPENSO')).toEqual(['REJECT']);
    expect(allowedDecisions('REPROVADO')).toEqual([]);
  });
});
