// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { ApiError, CondPulseApi } from '../../api/client';
import { contextSwitchResult, createPartnersApi } from '../../api/partners';
import { partnerKey, securityLogKey } from '../partner/model';
import { placeOf } from './paths';
import { completeSwitch, contextKeyOf, contextRefOfKey, isPreviousContextData, noteContext, planSwitch, resetContextTracker, sameContext } from './contextSwitch';

const own = '10000000-0000-4000-8000-000000000001';
const other = '10000000-0000-4000-8000-000000000002';
const partnerId = '30000000-0000-4000-8000-000000000001';
const otherPartner = '30000000-0000-4000-8000-000000000002';
const tenant = '20000000-0000-4000-8000-000000000001';
const lead = { kind: 'PARTNER', partnerId, role: 'SECURITY_LEAD' } as const;
const operator = { kind: 'PARTNER', partnerId: otherPartner, role: 'REMOTE_OPERATOR' } as const;
let counter = 0;
const ids = () => `5a000000-0000-4000-8000-${String(++counter).padStart(12, '0')}`;

afterEach(() => resetContextTracker());

describe('R4.4: contexto da casca', () => {
  it('condomínio, central e painel de parceiro são contextos; a entrada não é', () => {
    expect(contextKeyOf(placeOf(`/c/${tenant.toUpperCase()}/gestao/pendencias`))).toBe(`c:${tenant}`);
    expect(contextKeyOf(placeOf(`/c/${tenant}/portaria/visitas`))).toBe(`c:${tenant}`);
    expect(contextKeyOf(placeOf(`/p/${partnerId}/comando/log`))).toBe(`p:${partnerId}:comando`);
    expect(contextKeyOf(placeOf(`/p/${partnerId}/gestao`))).toBe(`p:${partnerId}:gestao`);
    expect(contextKeyOf(placeOf('/central/condominios'))).toBe('central');
    expect(contextKeyOf(placeOf('/entrada'))).toBeNull();
    expect(contextRefOfKey(`c:${tenant}`)).toEqual({ kind: 'TENANT', tenantId: tenant });
    expect(contextRefOfKey('central')).toEqual({ kind: 'PLATFORM' });
    expect(contextRefOfKey(`p:${partnerId}:comando`)).toBeNull();
  });

  it('encerrar a visão anterior apaga todo dado privado de contexto, mas não a entrada nem o segundo fator', () => {
    const queries = new QueryClient();
    const keys = [partnerKey(own, partnerId, 'team'), securityLogKey(own, tenant, 'integrity'), ['private', own, 'tenant', tenant, 'unit', 'x'],
      ['private', own, 'platform', 'tenants'], ['private', own, 'entry', 'platform'], ['private', own, 'mfa-status'], ['identity']];
    for (const key of keys) queries.setQueryData(key, { sintetico: true });
    queries.removeQueries({ predicate: (query) => isPreviousContextData(query.queryKey) });
    expect(queries.getQueryCache().getAll().map((query) => query.queryKey)).toEqual([['private', own, 'entry', 'platform'], ['private', own, 'mfa-status'], ['identity']]);
  });
});

describe('R4.4: chave e origem da troca', () => {
  it('primeira entrada vem de ENTRY; a mesma troca pendente mantém chave e origem (remontagem, nova tentativa)', () => {
    const first = planSwitch(own, lead, ids)!;
    expect(first.from).toEqual({ kind: 'ENTRY' });
    expect(planSwitch(own, lead, ids)).toBe(first);
    completeSwitch(own, first);
    expect(planSwitch(own, { ...lead, partnerId: partnerId.toUpperCase() }, ids), 'mesmo painel de novo: sem troca nem chamada').toBeNull();
  });
  it('a origem é o contexto anterior: outro painel, condomínio ou central; cada troca nova tem chave nova', () => {
    const first = planSwitch(own, lead, ids)!; completeSwitch(own, first);
    const second = planSwitch(own, operator, ids)!;
    expect(second.from).toEqual(lead); expect(second.switchId).not.toBe(first.switchId);
    completeSwitch(own, second);
    noteContext(own, { kind: 'TENANT', tenantId: tenant });
    expect(planSwitch(own, operator, ids)!.from).toEqual({ kind: 'TENANT', tenantId: tenant });
    noteContext(own, { kind: 'PLATFORM' });
    const fromCentral = planSwitch(own, lead, ids)!;
    expect(fromCentral.from).toEqual({ kind: 'PLATFORM' });
  });
  it('troca pendente abandonada por outro contexto não vaza a chave; outra conta começa do zero', () => {
    const pending = planSwitch(own, lead, ids)!;
    noteContext(own, { kind: 'TENANT', tenantId: tenant });
    const again = planSwitch(own, lead, ids)!;
    expect(again.switchId).not.toBe(pending.switchId);
    expect(again.from).toEqual({ kind: 'TENANT', tenantId: tenant });
    expect(planSwitch(other, lead, ids)!.from, 'conta nova na janela').toEqual({ kind: 'ENTRY' });
    expect(sameContext({ kind: 'TENANT', tenantId: tenant.toUpperCase() }, { kind: 'TENANT', tenantId: tenant })).toBe(true);
  });
});

describe('R4.4: chamada ao servidor', () => {
  const ok = (body: unknown) => vi.fn<typeof globalThis.fetch>(async (input) => String(input) === '/auth/csrf'
    ? new Response(JSON.stringify({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    : new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
  it('envia chave, papel e origem só com identificadores, com CSRF', async () => {
    const fetch = ok({ status: 'RECORDED', recorded: 2 });
    const result = await createPartnersApi(new CondPulseApi({ fetch })).switchContext(partnerId,
      { switchId: '5a000000-0000-4000-8000-000000000001', role: 'SECURITY_LEAD', from: { kind: 'TENANT', tenantId: tenant } });
    expect(result).toEqual({ status: 'RECORDED', recorded: 2 });
    const [path, init] = fetch.mock.calls.find(([input]) => String(input).includes('context-switches'))!;
    expect(String(path)).toBe(`/v1/partners/${partnerId}/context-switches`);
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({ switchId: '5a000000-0000-4000-8000-000000000001', role: 'SECURITY_LEAD', from: { kind: 'TENANT', tenantId: tenant } });
    expect(new Headers(init?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf');
  });
  it('resposta fora do contrato é recusada; log desligado é resposta válida', () => {
    expect(contextSwitchResult({ status: 'LOG_DISABLED', recorded: 0 })).toEqual({ status: 'LOG_DISABLED', recorded: 0 });
    expect(contextSwitchResult({ status: 'ALREADY_RECORDED', recorded: 0 }).status).toBe('ALREADY_RECORDED');
    for (const value of [{ status: 'OK', recorded: 0 }, { status: 'NOT_REQUIRED', recorded: 1 }, { status: 'RECORDED', recorded: -1 }, null])
      expect(() => contextSwitchResult(value)).toThrow(ApiError);
  });
});
