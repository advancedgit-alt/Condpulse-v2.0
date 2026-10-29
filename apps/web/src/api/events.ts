/**
 * Canal de atualização da unidade (CP-036). O servidor só diz QUAIS assuntos mudaram; a tela consulta o detalhe pelas
 * rotas normais. Ao reconectar, tudo é consultado de novo (eventos perdidos durante a queda não importam). Sessão
 * revogada encerra o canal sem nova tentativa; demais quedas voltam com espera crescente.
 */
export const TOPICS = ['reservations', 'parcels', 'arrivals', 'notifications', 'announcements', 'requests'] as const;
export type Topic = typeof TOPICS[number];
export type EndReason = 'revoked' | 'expired' | 'timeout' | 'unavailable' | 'shutdown';

export interface EventSourceLike {
  readonly readyState: number;
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void;
  onerror: ((event: Event) => void) | null;
  close(): void;
}
export type EventSourceFactory = (url: string) => EventSourceLike;
export interface Timers { set(callback: () => void, ms: number): unknown; clear(handle: unknown): void }

export interface UnitEventHandlers {
  /** Assuntos que mudaram: invalidar só as consultas deles. */
  changed(topics: Topic[]): void;
  /** Reconexão ou queda: consultar tudo de novo, pois algo pode ter mudado no intervalo. */
  resync(): void;
  /** Acesso perdido ou sessão a renovar. */
  ended(reason: 'revoked' | 'expired'): void;
}

const CLOSED = 2;
export const RETRY_BASE_MS = 30_000;
export const RETRY_MAX_MS = 300_000;
export const EXPIRED_RETRY_MS = 5_000;

export function unitEventsUrl(tenantId: string, unitId: string): string {
  return `/v1/tenants/${encodeURIComponent(tenantId)}/units/${encodeURIComponent(unitId)}/events`;
}

function parse(event: MessageEvent<string>): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(event.data);
    return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
  } catch { return null; }
}

export function connectUnitEvents(url: string, handlers: UnitEventHandlers, create: EventSourceFactory,
  timers: Timers = { set: (callback, ms) => setTimeout(callback, ms), clear: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>) }): () => void {
  let source: EventSourceLike | null = null;
  let disposed = false;
  let failures = 0;
  let opened = false;
  let pending: unknown = null;

  const schedule = (ms: number) => {
    if (disposed) return;
    if (pending !== null) timers.clear(pending);
    pending = timers.set(() => { pending = null; open(); }, ms);
  };
  const stop = () => { source?.close(); source = null; };

  function open() {
    if (disposed) return;
    stop();
    const current = create(url);
    source = current;
    current.addEventListener('ready', () => {
      if (source !== current) return;
      // Primeira abertura: a tela acabou de consultar. Reabertura: consultar tudo de novo.
      if (opened) handlers.resync();
      opened = true; failures = 0;
    });
    current.addEventListener('changed', (event) => {
      if (source !== current) return;
      const topics = parse(event)?.topics;
      if (!Array.isArray(topics)) { handlers.resync(); return; }
      const known = topics.filter((topic): topic is Topic => (TOPICS as readonly unknown[]).includes(topic));
      if (known.length !== topics.length) handlers.resync();
      else if (known.length > 0) handlers.changed(known);
    });
    current.addEventListener('end', (event) => {
      if (source !== current) return;
      const reason = parse(event)?.reason;
      if (reason === 'revoked') { stop(); disposed = true; handlers.ended('revoked'); return; }
      if (reason === 'expired') { stop(); handlers.ended('expired'); schedule(EXPIRED_RETRY_MS); }
      // timeout, shutdown, unavailable: o navegador reconecta sozinho e o "ready" seguinte consulta tudo de novo.
    });
    current.onerror = () => {
      if (source !== current || current.readyState !== CLOSED) return;
      // Recusa (401, 404 com a função desligada, 429, 503): o navegador desiste; tentar de novo com espera crescente.
      // Sem consulta aqui: a próxima abertura bem-sucedida já consulta tudo de novo.
      stop();
      schedule(Math.min(RETRY_MAX_MS, RETRY_BASE_MS * 2 ** failures));
      failures += 1;
    };
  }

  open();
  return () => { disposed = true; if (pending !== null) timers.clear(pending); pending = null; stop(); };
}
