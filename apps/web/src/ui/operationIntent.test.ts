// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import { pendingOperation, settleFailure } from './operationIntent';

describe('operação pendente por alvo', () => {
  it('reabrir com os mesmos dados reaproveita a chave; dados novos sem trava geram outra', () => {
    const slot = 'tenant:entry:permit:teste-1';
    const first = pendingOperation.keyFor(slot, '{"note":null}', { note: null });
    expect(pendingOperation.keyFor(slot, '{"note":null}', { note: null })).toBe(first);
    const other = pendingOperation.keyFor(slot, '{"note":"x"}', { note: 'x' });
    expect(other).not.toBe(first);
    pendingOperation.clear(slot);
    expect(pendingOperation.get(slot)).toBeNull();
  });
  it('resultado incerto trava o alvo: a chave e os dados continuam mesmo fora do formulário', () => {
    const slot = 'tenant:entry:permit:teste-2';
    const key = pendingOperation.keyFor(slot, '{"evidence":"DOCUMENT_CHECKED"}', { evidence: 'DOCUMENT_CHECKED' });
    let locked = false;
    const intent = { reset: () => pendingOperation.clear(slot) };
    settleFailure(new ApiError(503, 'ACCESS_DEPENDENCY_UNAVAILABLE', 'Indisponível', true), locked, intent, (value) => {
      locked = value; pendingOperation.lock(slot, value);
    });
    expect(locked).toBe(true);
    // Formulário reaberto (estado novo) encontra a mesma pendência; outros dados não trocam a chave travada.
    expect(pendingOperation.get(slot)).toMatchObject({ key, locked: true, body: { evidence: 'DOCUMENT_CHECKED' } });
    expect(pendingOperation.keyFor(slot, '{"evidence":"RECOGNIZED_BY_UNIT"}', { evidence: 'RECOGNIZED_BY_UNIT' })).toBe(key);
    // Chave recusada com envio travado não dá lugar a outra; recusa de regra prova que nada foi gravado e destrava.
    settleFailure(new ApiError(409, 'IDEMPOTENCY_KEY_REUSED', 'Outros dados'), true, intent, (value) => pendingOperation.lock(slot, value));
    expect(pendingOperation.get(slot)?.key).toBe(key);
    settleFailure(new ApiError(409, 'ACCESS_STATE_CHANGED', 'Mudou'), true, intent, (value) => pendingOperation.lock(slot, value));
    expect(pendingOperation.get(slot)?.locked).toBe(false);
    pendingOperation.clear(slot);
  });
});
