// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { QueryClient, QueryObserver } from '@tanstack/react-query';
import { invalidateFresh } from './invalidateFresh';

/** Aviso do canal que chega durante a primeira consulta (ainda sem dados) não pode se perder. */
describe('atualização pedida pelo canal durante a primeira consulta', () => {
  async function scenario(invalidate: (client: QueryClient) => Promise<unknown>) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    let calls = 0;
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const observer = new QueryObserver(client, { queryKey: ['u', 'inbox'], queryFn: async () => {
      calls += 1;
      if (calls === 1) { await gate; return 'antes'; }
      return 'depois';
    } });
    const unsubscribe = observer.subscribe(() => undefined);
    await Promise.resolve();
    const pending = invalidate(client);
    release();
    await pending;
    await new Promise((resolve) => setTimeout(resolve, 10));
    const result = { data: client.getQueryData(['u', 'inbox']), calls };
    unsubscribe(); client.clear();
    return result;
  }
  it('consulta de novo depois da primeira resposta, que pode ser anterior à mudança', async () => {
    expect(await scenario((client) => invalidateFresh(client, ['u', 'inbox']))).toEqual({ data: 'depois', calls: 2 });
  });
  it('sem consulta em andamento, invalida uma vez', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    let calls = 0;
    const observer = new QueryObserver(client, { queryKey: ['u', 'inbox'], queryFn: async () => { calls += 1; return calls; } });
    const unsubscribe = observer.subscribe(() => undefined);
    await new Promise((resolve) => setTimeout(resolve, 10));
    await invalidateFresh(client, ['u', 'inbox']);
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(calls).toBe(2);
    unsubscribe(); client.clear();
  });
});
