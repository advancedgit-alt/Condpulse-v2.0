// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { localRefresh } from './Inbox';

describe('aviso aberto na própria página', () => {
  it('pedido atualiza a lista e a conversa do pedido do aviso; mudança atualiza as mudanças', () => {
    expect(localRefresh('REQUEST_UPDATED', 'r-1')).toEqual([['requests'], ['request', 'r-1']]);
    expect(localRefresh('MOVE_DECIDED', 'm-1')).toEqual([['moves']]);
    expect(localRefresh('PARCEL_RECEIVED', 'p-1')).toEqual([]);
  });
});
