// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ApiError, CondPulseApi, publicPermit } from './client';
import { createVisitsApi, permitLink } from './visits';

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const csrf = () => json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' });
const token = 'A'.repeat(21) + '_-' + 'b'.repeat(20);
const view = { tenantName: 'CONDOMÍNIO FICTÍCIO', visitorFirstName: 'Visita', purpose: null, validFrom: '2030-01-01T12:00:00Z',
  validUntil: '2030-01-01T15:00:00Z', timeZone: 'America/Sao_Paulo', state: 'ACTIVE' };

describe('link de convite de visita (CP-037)', () => {
  it('visão pública só aceita os campos mínimos: sem unidade nem identificadores', () => {
    expect(publicPermit(view).visitorFirstName).toBe('Visita');
    expect(() => publicPermit({ ...view, unitLabel: '101' })).toThrow();
    expect(() => publicPermit({ ...view, permitId: '70000000-0000-4000-8000-000000000001' })).toThrow();
    expect(() => publicPermit({ ...view, state: 'REVOKED' })).toThrow();
    expect(() => publicPermit({ ...view, validUntil: view.validFrom })).toThrow();
    expect(() => publicPermit({ ...view, visitorFirstName: '' })).toThrow();
  });
  it('token do link criado tem formato fixo e nada a mais', () => {
    expect(permitLink({ token, validUntil: '2030-01-01T15:00:00Z' }).token).toBe(token);
    expect(() => permitLink({ token: 'curto', validUntil: '2030-01-01T15:00:00Z' })).toThrow();
    expect(() => permitLink({ token, validUntil: '2030-01-01T15:00:00Z', permitId: 'x' })).toThrow();
  });
  it('abrir o link manda o token no corpo, com CSRF, nunca na URL', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json(view));
    const client = new CondPulseApi({ fetch: fetcher });
    expect((await client.viewPermitLink(token)).tenantName).toBe('CONDOMÍNIO FICTÍCIO');
    const [url, init] = fetcher.mock.calls[1]!;
    expect(url).toBe('/auth/permit-links/view');
    expect(String(url)).not.toContain(token);
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({ token });
    expect(new Headers(init?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf');
  });
  it('token malformado não chama o servidor; link indisponível vira mensagem neutra', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    const client = new CondPulseApi({ fetch: fetcher });
    await expect(client.viewPermitLink('curto')).rejects.toMatchObject({ code: 'PERMIT_LINK_UNAVAILABLE' });
    expect(fetcher).not.toHaveBeenCalled();
    fetcher.mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code: 'PERMIT_LINK_UNAVAILABLE' }, 404));
    const error = await client.viewPermitLink(token).catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toMatch(/Peça um novo link/);
  });
  it('compartilhar e revogar usam POST com CSRF e sem chave de operação', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ token, validUntil: '2030-01-01T15:00:00Z' }, 201));
    const visits = createVisitsApi(new CondPulseApi({ fetch: fetcher }));
    expect((await visits.shareLink('00000000-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000001')).token).toBe(token);
    const [url, init] = fetcher.mock.calls[1]!;
    expect(url).toBe('/v1/tenants/00000000-0000-4000-8000-000000000001/permits/70000000-0000-4000-8000-000000000001/link');
    expect(init?.method).toBe('POST');
    expect(new Headers(init?.headers).has('Idempotency-Key')).toBe(false);
  });
});
