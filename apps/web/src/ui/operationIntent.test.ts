// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import { pendingOperation, settleFailure } from './operationIntent';

describe('operação pendente por alvo', () => {
  it('reabrir com os mesmos dados reaproveita a chave; dados novos sem trava geram outra', () => {
    const slot = 'tenant:entry:permit:teste-1';
    const first = pendingOperation.keyFor(slot, '{"note":null}', { note: null });
    pendingOperation.settle(slot); // resposta recebida (recusa de regra, por exemplo), sem trava
    expect(pendingOperation.keyFor(slot, '{"note":null}', { note: null })).toBe(first);
    pendingOperation.settle(slot);
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
  it('desde o início do envio a chave e os dados ficam presos ao alvo até a resposta', () => {
    const slot = 'tenant:entry:permit:teste-3';
    const key = pendingOperation.keyFor(slot, '{"evidence":"DOCUMENT_CHECKED"}', { evidence: 'DOCUMENT_CHECKED' });
    expect(pendingOperation.get(slot)).toMatchObject({ key, inFlight: true, locked: false });
    // Outro formulário do mesmo alvo, com outros dados, não troca a chave enquanto o primeiro envio não respondeu.
    expect(pendingOperation.keyFor(slot, '{"evidence":"RECOGNIZED_BY_UNIT"}', { evidence: 'RECOGNIZED_BY_UNIT' })).toBe(key);
    expect(pendingOperation.get(slot)?.body).toEqual({ evidence: 'DOCUMENT_CHECKED' });
    // Resposta recebida sem trava: dados novos voltam a gerar outra chave.
    pendingOperation.settle(slot);
    expect(pendingOperation.get(slot)?.inFlight).toBe(false);
    expect(pendingOperation.keyFor(slot, '{"evidence":"RECOGNIZED_BY_UNIT"}', { evidence: 'RECOGNIZED_BY_UNIT' })).not.toBe(key);
    // Resultado incerto trava e encerra o "em andamento" na mesma operação.
    pendingOperation.lock(slot, true);
    expect(pendingOperation.get(slot)).toMatchObject({ locked: true, inFlight: false });
    pendingOperation.clear(slot);
  });
  it('a pendência pertence à conta: outra conta, saída ou sessão perdida encontram a lista vazia', () => {
    const slot = 'tenant:ask:permit-4';
    pendingOperation.bindAccount('conta-a');
    pendingOperation.keyFor(slot, '{}', {});
    pendingOperation.lock(slot, true);
    pendingOperation.bindAccount('conta-a');
    expect(pendingOperation.get(slot)?.locked).toBe(true);
    pendingOperation.bindAccount('conta-b');
    expect(pendingOperation.get(slot)).toBeNull();
    pendingOperation.keyFor(slot, '{}', {});
    pendingOperation.clearAll();
    expect(pendingOperation.get(slot)).toBeNull();
  });
  it('avisa quem acompanha o alvo a cada mudança', () => {
    let calls = 0;
    const stop = pendingOperation.subscribe(() => { calls += 1; });
    const before = pendingOperation.revision();
    pendingOperation.keyFor('tenant:x', '{}', {});
    pendingOperation.settle('tenant:x');
    pendingOperation.clear('tenant:x');
    stop();
    pendingOperation.keyFor('tenant:x', '{}', {});
    expect(calls).toBe(3);
    expect(pendingOperation.revision()).toBeGreaterThan(before);
    pendingOperation.clear('tenant:x');
  });
  it('envio concluído mantém a identidade só para o formulário reaberto com os dados dele', () => {
    const slot = 'tenant:request-message:r-5';
    const key = pendingOperation.keyFor(slot, 'Mensagem', { body: 'Mensagem' });
    // Formulário reaberto durante o envio recebe a chave do envio em andamento como origem dos dados.
    const restored = pendingOperation.get(slot)!.key;
    pendingOperation.complete(slot);
    expect(pendingOperation.get(slot)).toMatchObject({ completed: true, inFlight: false, key });
    // O reaberto, com os mesmos dados, repete a mesma chave (o servidor devolve o que já foi gravado).
    expect(pendingOperation.keyFor(slot, 'Mensagem', { body: 'Mensagem' }, restored)).toBe(key);
    pendingOperation.complete(slot);
    // Quem não veio daquele envio (ou mudou o texto) começa uma operação nova.
    expect(pendingOperation.keyFor(slot, 'Mensagem', { body: 'Mensagem' })).not.toBe(key);
    pendingOperation.complete(slot);
    expect(pendingOperation.keyFor(slot, 'Outra', { body: 'Outra' }, restored)).not.toBe(key);
    pendingOperation.clear(slot);
  });
});

