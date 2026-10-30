import { QueryClient, QueryObserver } from '@tanstack/react-query';
import { invalidateFresh } from '../src/ui/invalidateFresh';

/** Aviso do canal que chega durante a primeira consulta (ainda sem dados) não pode se perder. */
test('consulta de novo depois da primeira resposta, que pode ser anterior à mudança', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } } });
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
  const pending = invalidateFresh(client, ['u', 'inbox']);
  release();
  await pending;
  expect(client.getQueryData(['u', 'inbox'])).toBe('depois');
  expect(calls).toBe(2);
  unsubscribe(); client.clear();
});
