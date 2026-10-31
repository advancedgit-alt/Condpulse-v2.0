// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ApiError, CondPulseApi } from './client';
import { createPlatformApi } from './platform';
import { createTechnicalGrantsApi, technicalGrant } from './technicalGrants';
import { emptyGrantDraft, grantBody } from '../ui/platform/TechnicalGrants';
import { visiblePlatformSections } from '../ui/platform/PlatformPage';

const tenant = '20000000-0000-4000-8000-000000000001';
const actor = '10000000-0000-4000-8000-000000000001';
const other = '10000000-0000-4000-8000-0000000000b2';
const grantId = '78000000-0000-4000-8000-000000000001';
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const grant = { grantId, tenantId: tenant, accountId: other, roleCode: 'MASTER_IMPLANTACAO', scopes: ['installation.read', 'installation.manage'],
  validFrom: '2026-10-31T12:00:00Z', validUntil: '2026-11-07T12:00:00Z', grantedVia: 'PLATFORM_API', status: 'ACTIVE', current: true, revokedAt: null, version: 0 };

describe('concessões técnicas no painel master', () => {
  it('sessão do painel com capacidades do acesso facial continua válida e libera a seção só com technical_grants.manage', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>(async () => json({ accountId: actor, role: 'PLATFORM_ADMIN', mfaRequired: false,
      capabilities: ['tenants.read', 'technical_grants.manage', 'access_catalog.manage', 'homologation.approve'] }));
    const session = await createPlatformApi(new CondPulseApi({ fetch: fetcher })).session();
    expect(visiblePlatformSections(session.capabilities).map((item) => item.id)).toContain('concessoes');
    expect(visiblePlatformSections(['tenants.read']).map((item) => item.id)).not.toContain('concessoes');
  });

  it('contrato: revogada tem horário, vigente é ativa, condomínio confere', () => {
    expect(technicalGrant(grant, tenant).current).toBe(true);
    expect(() => technicalGrant({ ...grant, status: 'REVOKED' }, tenant)).toThrow(ApiError);
    expect(() => technicalGrant({ ...grant, status: 'REVOKED', revokedAt: '2026-11-01T00:00:00Z' }, tenant)).toThrow(ApiError);
    expect(() => technicalGrant(grant, '20000000-0000-4000-8000-000000000009')).toThrow(ApiError);
    expect(() => technicalGrant({ ...grant, scopes: ['installation.read', 'installation.read'] }, tenant)).toThrow(ApiError);
  });

  it('formulário: conta válida e de outra pessoa, validade 1 a 90, motivo de 10 caracteres', () => {
    const draft = { ...emptyGrantDraft(), accountId: other.toUpperCase(), reason: 'Chamado OPS-123 aprovado' };
    expect(grantBody(grantId, { ...draft, accountId: actor }, actor)).toMatch(/si próprio/);
    expect(grantBody(grantId, { ...draft, accountId: 'x' }, actor)).toMatch(/UUID/);
    expect(grantBody(grantId, { ...draft, validDays: '91' }, actor)).toMatch(/1 a 90/);
    expect(grantBody(grantId, { ...draft, reason: 'curto' }, actor)).toMatch(/motivo/);
    expect(grantBody(grantId, { ...draft, scopes: [] }, actor)).toMatch(/escopo/);
    expect(grantBody(grantId, draft, actor)).toMatchObject({ accountId: other, roleCode: 'MASTER_IMPLANTACAO', validDays: 7,
      scopes: ['connector.rotate_secret', 'gateway.manage', 'homologation.request', 'installation.bind_channel', 'installation.commission', 'installation.manage', 'installation.read'] });
  });

  it('emissão confere a concessão devolvida; repetição chega como applied=false; revogação exige estado revogado', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>(async (input) => {
      const path = String(input);
      if (path === '/auth/csrf') return json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' });
      if (path.endsWith('/revoke')) return json(grant);
      return json({ grant, applied: false });
    });
    const client = createTechnicalGrantsApi(new CondPulseApi({ fetch: fetcher }));
    const body = { grantId, accountId: other, roleCode: 'MASTER_IMPLANTACAO' as const, scopes: ['installation.read' as const], validDays: 7, reason: 'Chamado OPS-123' };
    expect((await client.issue(tenant, body)).applied).toBe(false);
    await expect(client.issue(tenant, { ...body, accountId: '10000000-0000-4000-8000-0000000000c3' })).rejects.toMatchObject({ uncertain: true });
    await expect(client.revoke(tenant, grantId, { expectedVersion: 0, reason: 'Fim da obra' })).rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });
  });

  it('pedido para si próprio recebe a mensagem do servidor', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 't' })
      : json({ code: 'TECHNICAL_GRANT_SELF', status: 403 }, 403));
    const client = createTechnicalGrantsApi(new CondPulseApi({ fetch: fetcher }));
    await expect(client.issue(tenant, { grantId, accountId: actor, roleCode: 'MASTER_IMPLANTACAO', scopes: ['installation.read'], validDays: 1, reason: 'Chamado OPS-123' }))
      .rejects.toMatchObject({ code: 'TECHNICAL_GRANT_SELF', status: 403 });
  });
});
