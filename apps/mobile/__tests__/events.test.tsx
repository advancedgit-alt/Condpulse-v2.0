import { AppState, type AppStateStatus } from 'react-native';
import { cleanup, render, waitFor } from '@testing-library/react-native';
import { QueryClientProvider, useQuery } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { CLOSED, CONNECTING, connectUnitEvents, EXPIRED_RETRY_MS, OPEN, RETRY_BASE_MS, RETRY_MAX_MS, unitEventsPath, xhrEventSource,
  type EventSourceLike, type XhrLike } from '../src/api/events';
import { useUnitEvents } from '../src/ui/UnitEvents';

function manualTimers() {
  const list: { callback(): void; ms: number; cleared: boolean }[] = [];
  return {
    list,
    set: (callback: () => void, ms: number) => { const timer = { callback, ms, cleared: false }; list.push(timer); return timer; },
    clear: (handle: unknown) => { (handle as { cleared: boolean }).cleared = true; },
    fire() { const timer = list.filter((item) => !item.cleared).at(-1)!; timer.cleared = true; timer.callback(); },
    pending: () => list.filter((item) => !item.cleared),
  };
}

class FakeSource implements EventSourceLike {
  readyState = CONNECTING;
  closed = false;
  onerror: (() => void) | null = null;
  private listeners = new Map<string, ((event: { data: string }) => void)[]>();
  constructor(readonly url: string) {}
  addEventListener(type: string, listener: (event: { data: string }) => void) { this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]); }
  emit(type: string, data: string) { for (const listener of this.listeners.get(type) ?? []) listener({ data }); }
  fail(closed: boolean) { this.readyState = closed ? CLOSED : CONNECTING; this.onerror?.(); }
  close() { this.closed = true; this.readyState = CLOSED; }
}

describe('lógica do canal (igual à web)', () => {
  function harness() {
    const sources: FakeSource[] = [];
    const calls: string[] = [];
    const timers = manualTimers();
    const dispose = connectUnitEvents(unitEventsPath('t-1', 'u/1'), {
      changed: (topics) => calls.push(`changed:${topics.join(',')}`), resync: () => calls.push('resync'), ended: (reason) => calls.push(`ended:${reason}`),
    }, (url) => { const source = new FakeSource(url); sources.push(source); return source; }, timers);
    return { sources, calls, timers, dispose, last: () => sources.at(-1)! };
  }
  it('só assuntos conhecidos; desconhecido ou malformado consulta tudo', () => {
    const h = harness();
    expect(h.last().url).toBe('/v1/tenants/t-1/units/u%2F1/events');
    h.last().emit('ready', '{}');
    h.last().emit('changed', '{"topics":["reservations"]}');
    h.last().emit('changed', '{"topics":["reservations","x"]}');
    h.last().emit('changed', 'nao-json');
    expect(h.calls).toEqual(['changed:reservations', 'resync', 'resync']);
  });
  it('revogado encerra sem nova tentativa; expirado reabre em 5 s e consulta de novo', () => {
    const h = harness();
    h.last().emit('ready', '{}');
    h.last().emit('end', '{"reason":"expired"}');
    expect(h.timers.pending().at(-1)!.ms).toBe(EXPIRED_RETRY_MS);
    h.timers.fire();
    h.last().emit('ready', '{}');
    h.last().emit('end', '{"reason":"revoked"}');
    expect(h.calls).toEqual(['ended:expired', 'resync', 'ended:revoked']);
    h.last().fail(true);
    expect(h.timers.pending()).toHaveLength(0);
  });
  it('recusa espera 30 s dobrando até 5 min; abrir zera; fechar a tela cancela', () => {
    const h = harness();
    const waits: number[] = [];
    for (let i = 0; i < 6; i += 1) { h.last().fail(true); waits.push(h.timers.pending().at(-1)!.ms); h.timers.fire(); }
    expect(waits).toEqual([RETRY_BASE_MS, RETRY_BASE_MS * 2, RETRY_BASE_MS * 4, RETRY_BASE_MS * 8, RETRY_MAX_MS, RETRY_MAX_MS]);
    h.last().emit('ready', '{}');
    h.last().fail(true);
    expect(h.timers.pending().at(-1)!.ms).toBe(RETRY_BASE_MS);
    h.dispose();
    expect(h.timers.pending()).toHaveLength(0);
    expect(h.last().closed).toBe(true);
  });
});

class FakeXhr implements XhrLike {
  status = 0;
  responseText = '';
  headers: Record<string, string> = {};
  responseType = '';
  url = '';
  aborted = false;
  sent = false;
  onprogress: ((event: never) => unknown) | null = null;
  onload: ((event: never) => unknown) | null = null;
  onerror: ((event: never) => unknown) | null = null;
  private contentType = '';
  getResponseHeader(name: string) { return name.toLowerCase() === 'content-type' ? this.contentType : null; }
  open(_method: string, url: string) { this.url = url; }
  setRequestHeader(name: string, value: string) { this.headers[name] = value; }
  send() { this.sent = true; }
  abort() { this.aborted = true; }
  respond(status: number, contentType: string) { this.status = status; this.contentType = contentType; }
  chunk(text: string) { this.responseText += text; (this.onprogress as (() => void) | null)?.(); }
  finish() { (this.onload as (() => void) | null)?.(); }
  drop() { (this.onerror as (() => void) | null)?.(); }
}

describe('leitor SSE por XMLHttpRequest', () => {
  const flush = () => new Promise<void>((resolve) => setImmediate(() => resolve()));
  function harness(tokens: (string | null)[] = ['token-1', 'token-2', 'token-3']) {
    const requests: FakeXhr[] = [];
    const timers = manualTimers();
    const queue = [...tokens];
    const source = xhrEventSource('https://api.example.invalid/v1/tenants/t/units/u/events', async () => queue.shift() ?? null,
      () => { const xhr = new FakeXhr(); requests.push(xhr); return xhr; }, timers);
    const events: string[] = [];
    for (const type of ['ready', 'changed', 'end', 'ping']) source.addEventListener(type, (event) => events.push(`${type} ${event.data}`));
    const errors: number[] = [];
    source.onerror = () => errors.push(source.readyState);
    return { source, requests, timers, events, errors, last: () => requests.at(-1)! };
  }

  it('envia o token só no cabeçalho e entrega eventos mesmo com linhas cortadas entre pedaços', async () => {
    const h = harness();
    await flush();
    expect(h.last().url).not.toContain('token');
    expect(h.last().headers).toMatchObject({ Authorization: 'Bearer token-1', Accept: 'text/event-stream' });
    h.last().respond(200, 'text/event-stream;charset=UTF-8');
    h.last().chunk('retry: 7000\r\n\r\n: comentário\nevent: rea');
    h.last().chunk('dy\ndata: {"topics":[]}\n\nevent: changed\ndata: {"topics":');
    expect(h.events).toEqual(['ready {"topics":[]}']);
    expect(h.source.readyState).toBe(OPEN);
    h.last().chunk('["reservations"]}\n\n');
    expect(h.events).toEqual(['ready {"topics":[]}', 'changed {"topics":["reservations"]}']);
  });

  it('fim normal reconecta depois do intervalo informado, com token buscado de novo', async () => {
    const h = harness();
    await flush();
    h.last().respond(200, 'text/event-stream');
    h.last().chunk('retry: 7000\n\nevent: end\ndata: {"reason":"timeout"}\n\n');
    h.last().finish();
    expect(h.errors).toEqual([CONNECTING]);
    expect(h.timers.pending().at(-1)!.ms).toBe(7000);
    h.timers.fire();
    await flush();
    expect(h.requests).toHaveLength(2);
    expect(h.last().headers.Authorization).toBe('Bearer token-2');
  });

  it('recusa do servidor ou tipo errado fica fechado, sem reconectar sozinho', async () => {
    const h = harness();
    await flush();
    h.last().respond(404, 'application/problem+json');
    h.last().chunk('{"code":"X"}');
    h.last().finish();
    expect(h.events).toEqual([]);
    expect(h.errors).toEqual([CLOSED]);
    expect(h.timers.pending()).toHaveLength(0);
    const g = harness();
    await flush();
    g.last().respond(200, 'text/html');
    g.last().finish();
    expect(g.errors).toEqual([CLOSED]);
  });

  it('queda de rede depois de aberto reconecta; antes de abrir fica fechado', async () => {
    const h = harness();
    await flush();
    h.last().respond(200, 'text/event-stream');
    h.last().chunk('event: ready\ndata: {}\n\n');
    h.last().drop();
    expect(h.errors).toEqual([CONNECTING]);
    const g = harness();
    await flush();
    g.last().drop();
    expect(g.errors).toEqual([CLOSED]);
  });

  it('sem sessão não chama a rede; fechar aborta e impede reconexão', async () => {
    const h = harness([null]);
    await flush();
    expect(h.requests).toHaveLength(0);
    expect(h.errors).toEqual([CLOSED]);
    const g = harness();
    await flush();
    g.last().respond(200, 'text/event-stream');
    g.source.close();
    expect(g.last().aborted).toBe(true);
    g.last().chunk('event: changed\ndata: {"topics":["reservations"]}\n\n');
    g.last().finish();
    expect(g.events).toEqual([]);
    expect(g.timers.pending()).toHaveLength(0);
  });

  it('ouvinte que fecha o canal no meio do pedaço interrompe a entrega', async () => {
    const h = harness();
    await flush();
    h.source.addEventListener('end', () => h.source.close());
    h.last().respond(200, 'text/event-stream');
    h.last().chunk('event: end\ndata: {"reason":"revoked"}\n\nevent: changed\ndata: {"topics":["reservations"]}\n\n');
    expect(h.events).toEqual(['end {"reason":"revoked"}']);
  });
});

describe('canal na tela da unidade', () => {
  afterEach(async () => { await cleanup(); jest.restoreAllMocks(); });
  it('mudança de reserva consulta de novo; segundo plano fecha; voltar consulta tudo e reabre', async () => {
    let listener: ((state: AppStateStatus) => void) | null = null;
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_type, handler) => { listener = handler as (state: AppStateStatus) => void; return { remove: jest.fn() } as never; });
    Object.defineProperty(AppState, 'currentState', { configurable: true, get: () => 'active' });
    const sources: FakeSource[] = [];
    const create = (url: string) => { const source = new FakeSource(url); sources.push(source); return source; };
    const fetches = { reservations: 0, other: 0 };
    function Screen() {
      useUnitEvents({ accountId: 'a', tenantId: 't', unitId: 'u', create });
      useQuery({ queryKey: ['private', 'a', 'tenant', 't', 'unit', 'u', 'reservations', 'p0'], queryFn: async () => { fetches.reservations += 1; return 1; } });
      useQuery({ queryKey: ['private', 'a', 'tenant', 't', 'unit', 'u', 'amenities'], queryFn: async () => { fetches.other += 1; return 1; } });
      return null;
    }
    const queries = newQueryClient(Infinity);
    await render(<QueryClientProvider client={queries}><Screen /></QueryClientProvider>);
    await waitFor(() => expect(fetches).toEqual({ reservations: 1, other: 1 }));
    expect(sources).toHaveLength(1);
    expect(sources[0]!.url).toBe('/v1/tenants/t/units/u/events');
    sources[0]!.emit('ready', '{}');
    sources[0]!.emit('changed', '{"topics":["reservations","parcels"]}');
    await waitFor(() => expect(fetches.reservations).toBe(2));
    expect(fetches.other).toBe(1);
    listener!('background');
    expect(sources[0]!.closed).toBe(true);
    listener!('active');
    await waitFor(() => expect(fetches).toEqual({ reservations: 3, other: 2 }));
    expect(sources).toHaveLength(2);
    await cleanup();
    expect(sources[1]!.closed).toBe(true);
    queries.clear();
  });
});
