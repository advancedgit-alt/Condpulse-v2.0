// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ApiError, CondPulseApi } from './client';
import { createManagementApi, managedReservation } from './management';
import { amenityInput, cents } from '../ui/management/Setup';
import { groupSecret } from '../ui/Mfa';
import { invitationLink } from '../ui/management/People';
import { cancellationOutcome, decisionOutcome } from '../ui/management/Reservations';

const tenant = '20000000-0000-4000-8000-000000000001';
const amenity = '40000000-0000-4000-8000-000000000001';
const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
const csrf = (token: string) => json({ headerName: 'X-CSRF-Token', token });
const reservation = {
  reservationId: '50000000-0000-4000-8000-000000000001', kind: 'BOOKING', unitId: '30000000-0000-4000-8000-000000000001',
  unitLabel: '101', amenityId: amenity, amenityName: 'Salão', status: 'PENDING_APPROVAL', startsAt: '2030-01-01T15:00:00Z',
  endsAt: '2030-01-01T16:00:00Z', occupiedFrom: '2030-01-01T15:00:00Z', occupiedUntil: '2030-01-01T16:15:00Z',
  expiresAt: '2030-01-01T12:00:00Z', acceptedPolicyVersion: 1, feeCents: 0, version: 0, decisionReason: null,
  requesterName: 'Morador', createdAt: '2029-12-31T12:00:00Z',
};

describe('contratos da gestão', () => {
  it('aceita reserva e bloqueio coerentes e recusa combinação de tipo e unidade inconsistente', () => {
    expect(managedReservation(reservation).status).toBe('PENDING_APPROVAL');
    expect(managedReservation({ ...reservation, kind: 'BLOCK', unitId: null, unitLabel: null, requesterName: null }).kind).toBe('BLOCK');
    expect(() => managedReservation({ ...reservation, kind: 'BLOCK' })).toThrow(ApiError);
    expect(() => managedReservation({ ...reservation, unitId: null })).toThrow(ApiError);
    expect(() => managedReservation({ ...reservation, status: 'PAID' })).toThrow(ApiError);
  });

  it('bloqueio envia chave idempotente e lê a repetição; cursor de auditoria malformado é rejeitado', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => {
      const path = String(input);
      if (path === '/auth/csrf') return csrf('synthetic-csrf');
      if (path.includes('/maintenance-blocks')) return json({ ...reservation, kind: 'BLOCK', unitId: null, unitLabel: null,
        status: 'CONFIRMED', expiresAt: null, requesterName: 'Gestor', decisionReason: 'Pintura' }, 201, { 'Idempotency-Replayed': 'true' });
      return json({ events: [], nextCursor: 'not a cursor!' });
    });
    const client = createManagementApi(new CondPulseApi({ fetch: fetcher }));
    const result = await client.createMaintenanceBlock(tenant, amenity, 'web:synthetic-block-000001',
      { startsAt: '2030-01-01T10:00:00Z', endsAt: '2030-01-01T12:00:00Z', reason: 'Pintura' });
    expect(result.replayed).toBe(true); expect(result.reservation.kind).toBe('BLOCK');
    const call = fetcher.mock.calls.find(([path]) => String(path).includes('/maintenance-blocks'))!;
    const headers = new Headers(call[1]?.headers);
    expect(headers.get('Idempotency-Key')).toBe('web:synthetic-block-000001');
    expect(headers.get('X-CSRF-Token')).toBe('synthetic-csrf');
    await expect(client.audit(tenant, null)).rejects.toBeInstanceOf(ApiError);
    await expect(client.createMaintenanceBlock(tenant, amenity, 'curta', { startsAt: 'x', endsAt: 'y', reason: 'z' }))
      .rejects.toMatchObject({ code: 'INVALID_IDEMPOTENCY_KEY' });
  });

  it('PUT de gestão usa CSRF e identificadores validados antes da rede', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? csrf('synthetic-csrf')
      : json({ unitId: '30000000-0000-4000-8000-000000000001', label: '101', blockId: null, blockName: null, status: 'INACTIVE', version: 1 }));
    const client = createManagementApi(new CondPulseApi({ fetch: fetcher }));
    await expect(client.updateUnit(tenant, 'not-a-uuid', { label: '101', blockId: null, status: 'INACTIVE', expectedVersion: 0, reason: null }))
      .rejects.toMatchObject({ code: 'INVALID_RESOURCE_ID' });
    expect(fetcher).not.toHaveBeenCalled();
    const unit = await client.updateUnit(tenant, '30000000-0000-4000-8000-000000000001',
      { label: '101', blockId: null, status: 'INACTIVE', expectedVersion: 0, reason: 'Reforma' });
    expect(unit.status).toBe('INACTIVE');
    const put = fetcher.mock.calls.find(([, init]) => init?.method === 'PUT')!;
    expect(new Headers(put[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf');
  });
});

describe('segundo fator no cliente', () => {
  it('valida o código antes da rede e renova o CSRF depois da sessão AAL2', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>(); let tokens = 0;
    fetcher.mockImplementation(async (input) => {
      const path = String(input);
      if (path === '/auth/csrf') return csrf(`synthetic-csrf-${++tokens}`);
      if (path === '/auth/mfa/verify') return json({ accountId: '10000000-0000-4000-8000-000000000001', expiresAt: '2030-01-01T20:00:00Z' });
      return json({ invitationId: '71000000-0000-4000-8000-000000000001', token: 'A'.repeat(43), expiresAt: '2030-01-08T00:00:00Z' }, 201);
    });
    const api = new CondPulseApi({ fetch: fetcher });
    await expect(api.verifyTotp('61000000-0000-4000-8000-000000000001', '12ab56')).rejects.toMatchObject({ code: 'MFA_CODE_FORMAT' });
    expect(fetcher).not.toHaveBeenCalled();
    await api.verifyTotp('61000000-0000-4000-8000-000000000001', '123456');
    await createManagementApi(api).createInvitation(tenant, { role: 'RESIDENT', unitId: null, displayName: 'X', email: 'x@example.invalid', validDays: 7 });
    const posts = fetcher.mock.calls.filter(([, init]) => init?.method === 'POST');
    expect(new Headers(posts[0]?.[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf-1');
    expect(new Headers(posts[1]?.[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf-2');
  });
});

describe('regras locais de formulário', () => {
  it('converte valores em reais sem ponto flutuante e recusa formatos ambíguos', () => {
    expect(cents('0')).toBe(0); expect(cents('25,5')).toBe(2550); expect(cents('1.234,56')).toBe(123456);
    expect(cents('100000,00')).toBe(10_000_000); expect(cents('1,234')).toBeNull(); expect(cents('abc')).toBeNull();
    // Ponto decimal sem vírgula (até 2 casas) e ponto de milhar em grupos de 3.
    expect(cents('50.00')).toBe(5000); expect(cents('50.5')).toBe(5050); expect(cents('1234.56')).toBe(123456);
    expect(cents('1.234')).toBe(123400); expect(cents('12.345,6')).toBe(1234560); expect(cents(' 7 ')).toBe(700);
    expect(cents('1.2.3.4')).toBeNull(); expect(cents('1.23.456')).toBeNull(); expect(cents('.5')).toBeNull();
    expect(cents('1,')).toBeNull(); expect(cents('1.234.5')).toBeNull(); expect(cents('-1')).toBeNull(); expect(cents('1e3')).toBeNull();
    expect(cents('1234567890')).toBeNull();
  });
  it('monta regra explícita e aponta o primeiro problema', () => {
    const draft = { name: '  Salão   de festas ', capacity: '20', approvalRequired: true, fee: '50,00', buffer: '30', active: true,
      weekDays: [6, 1], opensAt: '08:00', closesAt: '22:00', minimumLead: '0', maximumAdvance: '60', maximumDuration: '240',
      maximumActive: '1', cancellationLead: '1440', reason: '' };
    const input = amenityInput(draft, null);
    expect(typeof input).toBe('object');
    if (typeof input === 'object') {
      expect(input.name).toBe('Salão de festas'); expect(input.feeCents).toBe(5000);
      expect(input.policy.weekDays).toEqual([1, 6]); expect(input.expectedVersion).toBeNull(); expect(input.reason).toBeNull();
    }
    expect(amenityInput({ ...draft, closesAt: '07:00' }, 3)).toMatch(/fechamento/);
    expect(amenityInput({ ...draft, weekDays: [] }, 3)).toMatch(/dia da semana/);
    expect(amenityInput({ ...draft, capacity: '0' }, 3)).toMatch(/capacidade/);
    expect(amenityInput({ ...draft, fee: '50.00' }, 3)).toMatchObject({ feeCents: 5000 });
    expect(amenityInput({ ...draft, minimumLead: '525601' }, 3)).toMatch(/antecedência mínima/);
    expect(amenityInput({ ...draft, maximumAdvance: '0' }, 3)).toMatch(/janela máxima/);
    expect(amenityInput({ ...draft, maximumAdvance: '367' }, 3)).toMatch(/janela máxima/);
    expect(amenityInput({ ...draft, maximumDuration: '1441' }, 3)).toMatch(/duração máxima/);
    expect(amenityInput({ ...draft, maximumActive: '0' }, 3)).toMatch(/reservas ativas/);
    expect(amenityInput({ ...draft, maximumActive: '101' }, 3)).toMatch(/reservas ativas/);
    expect(amenityInput({ ...draft, cancellationLead: '525601' }, 3)).toMatch(/cancelamento/);
    expect(amenityInput({ ...draft, minimumLead: '525600', maximumAdvance: '366', maximumDuration: '1440', maximumActive: '100',
      cancellationLead: '525600' }, 3)).toMatchObject({ expectedVersion: 3 });
  });
  it('agrupa a chave do autenticador e monta o link com token só no fragmento', () => {
    expect(groupSecret('JBSWY3DPEHPK3PXP')).toBe('JBSW Y3DP EHPK 3PXP');
    const link = invitationLink('https://condpulse.example', 'A'.repeat(43));
    expect(new URL(link).search).toBe(''); expect(new URL(link).hash).toBe(`#token=${'A'.repeat(43)}`);
  });
});

describe('resultado mostrado após decisão ou cancelamento', () => {
  const pending = managedReservation(reservation);
  it('sem a indicação do servidor, só confirma sucesso quando a versão avançou pela própria ação', () => {
    expect(decisionOutcome({ reservation: { ...pending, status: 'CONFIRMED', version: 1 }, applied: null }, 'APPROVE', 0)).toEqual({ tone: 'success', text: 'Pedido aprovado. A reserva está confirmada.' });
    expect(decisionOutcome({ reservation: { ...pending, status: 'REJECTED', version: 1 }, applied: null }, 'REJECT', 0)).toEqual({ tone: 'success', text: 'Pedido rejeitado. O período foi liberado.' });
    // Repetição natural: outra pessoa já decidiu (versão não avançou a partir da lida) ou o resultado diverge do pedido.
    const replay = decisionOutcome({ reservation: { ...pending, status: 'CONFIRMED', version: 1 }, applied: null }, 'APPROVE', 1);
    expect(replay.tone).toBe('info'); expect(replay.text).toMatch(/já estava confirmada/); expect(replay.text).toMatch(/não foi registrado/);
    expect(decisionOutcome({ reservation: { ...pending, status: 'REJECTED', version: 1 }, applied: null }, 'APPROVE', 0).tone).toBe('info');
    expect(decisionOutcome({ reservation: { ...pending, status: 'EXPIRED', version: 1 }, applied: null }, 'APPROVE', 0).text).toMatch(/já estava expirada/);
  });
  it('com a indicação do servidor, a segunda decisão igual de outro gestor não aparece como sucesso', () => {
    // Dois gestores leram a versão 0: o primeiro gravou (versão 1); o segundo recebe o mesmo estado sem aplicar nada.
    const first = decisionOutcome({ reservation: { ...pending, status: 'CONFIRMED', version: 1 }, applied: true }, 'APPROVE', 0);
    expect(first.tone).toBe('success');
    const second = decisionOutcome({ reservation: { ...pending, status: 'CONFIRMED', version: 1 }, applied: false }, 'APPROVE', 0);
    expect(second.tone).toBe('info'); expect(second.text).toMatch(/não foi registrado/);
  });
  it('cancelamento repetido ou feito por outra pessoa não aparece como sucesso desta ação', () => {
    const confirmed = { ...pending, status: 'CONFIRMED' as const, version: 1 };
    expect(cancellationOutcome({ ...confirmed, status: 'CANCELLED', version: 2, decisionReason: 'Manutenção' }, 'Manutenção', 1))
      .toEqual({ tone: 'success', text: 'Reserva cancelada com motivo registrado.' });
    expect(cancellationOutcome({ ...confirmed, kind: 'BLOCK', unitId: null, unitLabel: null, requesterName: null, status: 'CANCELLED', version: 2,
      decisionReason: 'Obra' }, 'Obra', 1)).toEqual({ tone: 'success', text: 'Bloqueio encerrado. O período foi liberado.' });
    const other = cancellationOutcome({ ...confirmed, status: 'CANCELLED', version: 2, decisionReason: 'Outro motivo' }, 'Manutenção', 1);
    expect(other.tone).toBe('warning'); expect(other.text).toBe('A reserva já estava cancelada (motivo registrado: Outro motivo). O seu motivo não foi registrado.');
    expect(cancellationOutcome({ ...confirmed, status: 'CANCELLED', version: 3, decisionReason: 'Manutenção' }, 'Manutenção', 1).tone).toBe('warning');
  });
});
