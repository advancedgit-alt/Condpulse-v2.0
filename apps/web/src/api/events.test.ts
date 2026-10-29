import { describe, expect, it } from 'vitest';
import { connectUnitEvents, EXPIRED_RETRY_MS, RETRY_BASE_MS, RETRY_MAX_MS, unitEventsUrl, type EventSourceLike } from './events';

class FakeSource implements EventSourceLike {
  readyState = 0;
  closed = false;
  onerror: ((event: Event) => void) | null = null;
  private listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>();
  constructor(readonly url: string) {}
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }
  emit(type: string, data: string) { for (const listener of this.listeners.get(type) ?? []) listener({ data } as MessageEvent<string>); }
  fail(closed: boolean) { this.readyState = closed ? 2 : 0; this.onerror?.(new Event('error')); }
  close() { this.closed = true; this.readyState = 2; }
}

function harness() {
  const sources: FakeSource[] = [];
  const calls: string[] = [];
  const timers: { callback(): void; ms: number; cleared: boolean }[] = [];
  const dispose = connectUnitEvents(unitEventsUrl('t-1', 'u/1'), {
    changed: (topics) => calls.push(`changed:${topics.join(',')}`),
    resync: () => calls.push('resync'),
    ended: (reason) => calls.push(`ended:${reason}`),
  }, (url) => { const source = new FakeSource(url); sources.push(source); return source; }, {
    set: (callback, ms) => { const timer = { callback, ms, cleared: false }; timers.push(timer); return timer; },
    clear: (handle) => { (handle as { cleared: boolean }).cleared = true; },
  });
  const fire = () => { const timer = timers.filter((item) => !item.cleared).at(-1)!; timer.cleared = true; timer.callback(); };
  return { sources, calls, timers, dispose, fire, last: () => sources.at(-1)! };
}

describe('canal de atualização da unidade', () => {
  it('abre a rota da unidade e invalida só os assuntos que mudaram', () => {
    const h = harness();
    expect(h.last().url).toBe('/v1/tenants/t-1/units/u%2F1/events');
    h.last().emit('ready', '{"topics":["parcels"]}');
    h.last().emit('ping', '{}');
    h.last().emit('changed', '{"topics":["parcels","notifications"]}');
    expect(h.calls).toEqual(['changed:parcels,notifications']);
  });

  it('assunto desconhecido ou evento malformado consulta tudo de novo', () => {
    const h = harness();
    h.last().emit('changed', '{"topics":["parcels","secret"]}');
    h.last().emit('changed', 'nao-json');
    h.last().emit('changed', '{"topics":"parcels"}');
    expect(h.calls).toEqual(['resync', 'resync', 'resync']);
  });

  it('reconexão do navegador consulta o estado de novo; a primeira abertura não', () => {
    const h = harness();
    h.last().emit('ready', '{}');
    h.last().emit('end', '{"reason":"timeout"}');
    h.last().fail(false); // navegador reconectando sozinho
    h.last().emit('ready', '{}');
    expect(h.calls).toEqual(['resync']);
    expect(h.sources).toHaveLength(1);
  });

  it('sessão revogada encerra para sempre, sem nova tentativa', () => {
    const h = harness();
    h.last().emit('end', '{"reason":"revoked"}');
    expect(h.calls).toEqual(['ended:revoked']);
    expect(h.last().closed).toBe(true);
    h.last().fail(true);
    expect(h.timers).toHaveLength(0);
  });

  it('acesso expirado pede renovação e reabre depois de alguns segundos', () => {
    const h = harness();
    h.last().emit('ready', '{}');
    h.last().emit('end', '{"reason":"expired"}');
    expect(h.calls).toEqual(['ended:expired']);
    expect(h.last().closed).toBe(true);
    expect(h.timers.at(-1)!.ms).toBe(EXPIRED_RETRY_MS);
    h.fire();
    expect(h.sources).toHaveLength(2);
    h.last().emit('ready', '{}');
    expect(h.calls).toEqual(['ended:expired', 'resync']);
  });

  it('recusa do servidor tenta de novo com espera crescente até o teto, sem consultar a cada falha', () => {
    const h = harness();
    const waits: number[] = [];
    for (let attempt = 0; attempt < 6; attempt += 1) {
      h.last().fail(true);
      waits.push(h.timers.at(-1)!.ms);
      h.fire();
    }
    expect(waits).toEqual([RETRY_BASE_MS, RETRY_BASE_MS * 2, RETRY_BASE_MS * 4, RETRY_BASE_MS * 8, RETRY_MAX_MS, RETRY_MAX_MS]);
    expect(h.calls).toEqual([]);
    h.last().emit('ready', '{}');
    h.last().fail(true);
    expect(h.timers.at(-1)!.ms).toBe(RETRY_BASE_MS); // abertura bem-sucedida zera a espera
  });

  it('eventos de uma conexão antiga são ignorados e fechar a tela encerra tudo', () => {
    const h = harness();
    const first = h.last();
    first.fail(true);
    h.fire();
    first.emit('changed', '{"topics":["parcels"]}');
    expect(h.calls).toEqual([]);
    h.last().fail(true);
    h.dispose();
    expect(h.last().closed).toBe(true);
    expect(h.timers.at(-1)!.cleared).toBe(true);
  });
});
