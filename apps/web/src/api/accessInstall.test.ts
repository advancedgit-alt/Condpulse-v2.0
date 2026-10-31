// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ApiError, CondPulseApi } from './client';
import {
  acceptanceSummary, accessArea, createAccessInstallApi, installationAcceptance, pointBinding, pointSummary,
} from './accessInstall';
import { visibleInstallationSections } from '../ui/installation/InstallationPage';
import { acceptanceBody, bindingBody } from '../ui/installation/Commissioning';
import { pointFields } from '../ui/installation/Inventory';
import { areaFields, rulesSignable } from '../ui/management/AccessMap';

const tenant = '20000000-0000-4000-8000-000000000001';
const areaA = '71000000-0000-4000-8000-000000000001';
const areaB = '71000000-0000-4000-8000-000000000002';
const point = '72000000-0000-4000-8000-000000000001';
const device = '73000000-0000-4000-8000-000000000001';
const channel = '74000000-0000-4000-8000-000000000001';
const homologation = '75000000-0000-4000-8000-000000000001';
const acceptanceId = '76000000-0000-4000-8000-000000000001';
const binding = '77000000-0000-4000-8000-000000000001';
const hash = 'a'.repeat(64);
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const area = { areaId: areaA, code: 'HALL-A', name: 'Hall A', areaKind: 'COMMON_AREA', blockId: null, parentAreaId: null, amenityId: null,
  timeZone: 'America/Sao_Paulo', essentialRoute: false, physicalBoundaryVerified: true, physicalSubtype: 'HALL', status: 'ACTIVE',
  updatedAt: '2026-10-31T12:00:00Z', version: 2 };
const acceptance = { acceptanceId, installationRevision: 1, status: 'TESTING', plantRef: 'obra/planta-v1', plantHash: hash, topologyManifestRef: 'obra/rede-v1',
  topologyHash: hash, capabilityEnvelopeHash: hash, testSuiteVersion: 'suite-1', testReportRef: 'obra/ensaio-1', criticalTestsPending: 0, criticalTestsFailed: 0,
  emergencyTestRef: 'obra/emergencia', rollbackPlanRef: 'obra/reversao', revocationCheckpointRef: null, technicalApproverAccountId: null, technicalSignedAt: null,
  rulesApproverAccountId: null, rulesSignedAt: null, effectiveAt: null, reviewDueAt: null, statusReason: null, version: 3 };
const bindingRow = { bindingId: binding, pointId: point, bindingRevision: 1, actuatorChannelId: channel, controllerDeviceId: device, entryReaderDeviceId: device,
  exitReaderDeviceId: null, doorContactChannelId: null, exitRequestChannelId: null, passageSensorChannelId: null, homologationId: homologation,
  installationAcceptanceId: null, directions: { entryReader: 'FROM_TO', exitReader: null }, status: 'DRAFT', validFrom: null, validUntil: null,
  wiringEvidenceRef: 'obra/ligacao-1', configurationHash: null, commissionedFingerprint: null, version: 0 };

describe('contratos da implantação física', () => {
  it('área exige área reservável só para o tipo AMENITY', () => {
    expect(accessArea(area).name).toBe('Hall A');
    expect(() => accessArea({ ...area, areaKind: 'AMENITY' })).toThrow(ApiError);
    expect(() => accessArea({ ...area, status: 'GONE' })).toThrow(ApiError);
  });

  it('resumo da gestão recusa campo técnico (planta, relatório, equipamento)', () => {
    const summary = { pointId: point, code: 'P-01', name: 'Portaria', fromAreaId: areaA, toAreaId: areaB, pointKind: 'DOOR', flowDirection: 'BOTH', status: 'MAPPED' };
    expect(pointSummary(summary).name).toBe('Portaria');
    expect(() => pointSummary({ ...summary, emergencyDesignRef: 'obra/x' })).toThrow(ApiError);
    const managed = { acceptanceId, installationRevision: 1, status: 'TESTING', criticalTestsPending: 0, criticalTestsFailed: 0, technicalSignedAt: '2026-10-31T12:00:00Z',
      rulesSignedAt: null, rulesApproverAccountId: null, effectiveAt: null, reviewDueAt: null, version: 4 };
    expect(rulesSignable(acceptanceSummary(managed))).toBe(true);
    expect(() => acceptanceSummary({ ...managed, plantRef: 'obra/planta' })).toThrow(ApiError);
    expect(() => acceptanceSummary({ ...managed, technicalSignedAt: null, rulesSignedAt: '2026-10-31T12:00:00Z', rulesApproverAccountId: tenant })).toThrow(ApiError);
  });

  it('aceite aprovado exige as duas assinaturas, testes zerados e vigência', () => {
    expect(installationAcceptance(acceptance).status).toBe('TESTING');
    expect(() => installationAcceptance({ ...acceptance, status: 'APPROVED' })).toThrow(ApiError);
    expect(() => installationAcceptance({ ...acceptance, technicalSignedAt: '2026-10-31T12:00:00Z' })).toThrow(ApiError);
  });

  it('vínculo: leitor e sentido andam juntos; ativo tem aceite e início', () => {
    expect(pointBinding(bindingRow).directions.entryReader).toBe('FROM_TO');
    expect(() => pointBinding({ ...bindingRow, directions: { entryReader: null, exitReader: null } })).toThrow(ApiError);
    expect(() => pointBinding({ ...bindingRow, status: 'ACTIVE' })).toThrow(ApiError);
  });

  it('sessão técnica de outro condomínio é recusada; mutação confere o registro devolvido e usa CSRF', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => {
      const path = String(input);
      if (path === '/auth/csrf') return json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' });
      if (path.endsWith('/technical/session')) return json({ tenantId: '20000000-0000-4000-8000-000000000009', scopes: ['installation.read'], validUntil: '2026-11-30T00:00:00Z' });
      if (path.endsWith('/physical')) return json({ ...area, areaId: areaB });
      return json({ ...acceptance, technicalSignedAt: null });
    });
    const client = createAccessInstallApi(new CondPulseApi({ fetch: fetcher }));
    await expect(client.session(tenant)).rejects.toBeInstanceOf(ApiError);
    await expect(client.setAreaPhysical(tenant, areaA, { essentialRoute: true, physicalBoundaryVerified: true, expectedVersion: 2 }))
      .rejects.toMatchObject({ code: 'INVALID_API_RESPONSE', uncertain: true });
    await expect(client.signTechnical(tenant, acceptanceId, { expectedVersion: 3 })).rejects.toMatchObject({ uncertain: true });
    const call = fetcher.mock.calls.find(([path]) => String(path).endsWith('/physical'))!;
    expect(call[1]?.method).toBe('PUT');
    expect(new Headers(call[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf');
    await expect(client.points('not-a-uuid')).rejects.toMatchObject({ code: 'INVALID_RESOURCE_ID' });
  });

  it('erro de escopo técnico chega com texto próprio', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>(async () => json({ code: 'TECHNICAL_SCOPE_REQUIRED', status: 403 }, 403));
    const client = createAccessInstallApi(new CondPulseApi({ fetch: fetcher }));
    await expect(client.devices(tenant)).rejects.toMatchObject({ code: 'TECHNICAL_SCOPE_REQUIRED', message: 'Sua concessão técnica neste condomínio não inclui esta operação.' });
  });
});

describe('telas da implantação', () => {
  it('seções aparecem só com o escopo da rota', () => {
    const session = (scopes: string[]) => ({ tenantId: tenant, scopes, validUntil: '2026-11-30T00:00:00Z' }) as never;
    expect(visibleInstallationSections(session(['installation.manage']))).toEqual([]);
    expect(visibleInstallationSections(session(['installation.read'])).map((item) => item.id)).toEqual(['roteiro']);
    expect(visibleInstallationSections(session(['installation.read', 'installation.bind_channel'])).map((item) => item.id)).toEqual(['roteiro', 'vinculos']);
    expect(visibleInstallationSections(session(['installation.read', 'installation.manage', 'installation.commission'])).map((item) => item.id))
      .toEqual(['roteiro', 'areas', 'pontos', 'equipamentos', 'canais', 'aceite']);
  });

  it('formulários seguem as regras do servidor', () => {
    const draft = { pointId: point, controllerDeviceId: device, actuatorChannelId: channel, entryReaderDeviceId: device, entrySense: '' as const, exitReaderDeviceId: '',
      exitSense: '' as const, doorContactChannelId: '', exitRequestChannelId: '', passageSensorChannelId: '', homologationId: homologation.toUpperCase(), wiringEvidenceRef: ' obra/l1 ' };
    expect(bindingBody(binding, draft)).toBe('Cada leitor exige o sentido em relação ao ponto.');
    expect(bindingBody(binding, { ...draft, entrySense: 'TO_FROM' })).toMatchObject({ homologationId: homologation, wiringEvidenceRef: 'obra/l1',
      directions: { entryReader: 'TO_FROM', exitReader: null }, exitReaderDeviceId: null });
    expect(pointFields({ code: 'P', name: 'Portaria', fromAreaId: areaA, toAreaId: areaA, pointKind: 'DOOR', flowDirection: 'BOTH', egressClass: 'FREE_EGRESS',
      accessibleRouteRef: '', emergencyDesignRef: '', status: 'DRAFT' })).toBe('Escolha origem e destino diferentes.');
    expect(areaFields({ code: 'A', name: 'Academia', areaKind: 'AMENITY', physicalSubtype: '', blockId: '', parentAreaId: '', amenityId: '', timeZone: '', status: 'ACTIVE' }, null))
      .toMatch(/Área reservável/);
    const accepted = acceptanceBody(acceptanceId, { plantRef: 'obra/planta', plantHash: hash.toUpperCase(), topologyManifestRef: 'obra/rede', topologyHash: hash,
      capabilityEnvelopeHash: hash, testSuiteVersion: 'suite-1', testReportRef: 'obra/ensaio', criticalTestsPending: '2', criticalTestsFailed: '0',
      emergencyTestRef: 'obra/emergencia', rollbackPlanRef: 'obra/reversao', revocationCheckpointRef: '', reviewDue: '2027-01-31' });
    expect(accepted).toMatchObject({ plantHash: hash, criticalTestsPending: 2, revocationCheckpointRef: null, reviewDueAt: '2027-02-01T02:59:00.000Z' });
    expect(acceptanceBody(acceptanceId, { plantRef: 'x', plantHash: hash, topologyManifestRef: 'obra/rede', topologyHash: hash, capabilityEnvelopeHash: hash,
      testSuiteVersion: 's', testReportRef: 'obra/e', criticalTestsPending: '0', criticalTestsFailed: '0', emergencyTestRef: 'obra/em', rollbackPlanRef: 'obra/r',
      revocationCheckpointRef: '', reviewDue: '' })).toMatch(/Planta/);
  });
});

describe('falha de consulta com cache', () => {
  it('autorização vale mesmo com dados; falha recuperável só sem dados', async () => {
    const { queryFailure } = await import('../ui/queryFailure');
    const denied = new ApiError(404, 'TENANT_ACCESS_DENIED', 'x');
    const scope = new ApiError(403, 'TECHNICAL_SCOPE_REQUIRED', 'x');
    const down = new ApiError(503, 'INSTALLATION_UNAVAILABLE', 'x');
    expect(queryFailure([{ isError: true, error: down, data: [] }])).toBeNull();
    expect(queryFailure([{ isError: true, error: down, data: undefined }])).toBe(down);
    expect(queryFailure([{ isError: true, error: down, data: undefined }, { isError: true, error: denied, data: [] }])).toBe(denied);
    expect(queryFailure([{ isError: true, error: scope, data: [] }])).toBe(scope);
  });

  it('área: só a referência do tipo atual conta', () => {
    expect(areaFields({ code: 'A', name: 'Hall', areaKind: 'COMMON_AREA', physicalSubtype: '', blockId: '', parentAreaId: '', amenityId: '40000000-0000-4000-8000-000000000001',
      timeZone: '', status: 'ACTIVE' }, null)).toMatchObject({ amenityId: null });
  });
});

describe('local afetado por ação do aceite', () => {
  it('espelha o efeito do banco: enviados para assinar/aprovar/reprovar; em vigor para suspender/substituir', async () => {
    const { affectedBindings, affectedPlace } = await import('../ui/installation/Commissioning');
    const pending = pointBinding({ ...bindingRow, status: 'PENDING_COMMISSION', installationAcceptanceId: acceptanceId });
    const active = pointBinding({ ...bindingRow, bindingId: '77000000-0000-4000-8000-000000000002', pointId: '72000000-0000-4000-8000-000000000002',
      status: 'ACTIVE', installationAcceptanceId: acceptanceId, validFrom: '2026-10-31T12:00:00Z' });
    const all = [pending, active];
    for (const action of ['SIGN', 'TESTING', 'APPROVED', 'REJECTED'] as const) expect(affectedBindings(action, all)).toEqual([pending]);
    for (const action of ['SUSPENDED', 'SUPERSEDED'] as const) expect(affectedBindings(action, all)).toEqual([active]);
    expect(affectedBindings('DRAFT', all)).toEqual([]);
    const points = [{ pointId: '72000000-0000-4000-8000-000000000002', code: 'P-GARAGEM', name: 'Portão da garagem', fromAreaId: areaA, toAreaId: areaB,
      pointKind: 'VEHICLE_GATE' as const, flowDirection: 'BOTH' as const, egressClass: 'NOT_EGRESS' as const, accessibleRouteRef: null, emergencyDesignRef: null,
      status: 'COMMISSIONED' as const, version: 1 }];
    expect(affectedPlace('SUSPENDED', { installationRevision: 2 }, all, points, [])).toMatch(/^Revisão 2 \(vínculos em vigor são suspensos.*\): Portão da garagem \(P-GARAGEM\)/);
    expect(affectedPlace('SUSPENDED', { installationRevision: 2 }, [pending], points, [])).toBe('Revisão 2: nenhum vínculo em vigor nesta revisão');
  });
});
