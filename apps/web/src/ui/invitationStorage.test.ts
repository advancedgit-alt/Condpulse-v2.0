// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { captureInvitationFromLocation, forgetInvitation, storedInvitation } from './invitationStorage';

const token = 'A'.repeat(21) + '_' + 'b'.repeat(20) + '-';
class MemoryStorage {
  private values = new Map<string, string>();
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { this.values.set(key, value); }
  removeItem(key: string): void { this.values.delete(key); }
}
function browser(hash: string, storage: unknown = new MemoryStorage()) {
  const replaceState = vi.fn();
  vi.stubGlobal('window', { location: { hash, pathname: '/convite' }, history: { state: { idx: 1 }, replaceState }, sessionStorage: storage });
  return { replaceState, storage: storage as MemoryStorage };
}

describe('convite guardado só nesta aba', () => {
  beforeEach(() => { expect(token).toHaveLength(43); });
  afterEach(() => { vi.unstubAllGlobals(); });
  it('lê o token do fragmento, limpa a barra de endereço e guarda até sair ou concluir', () => {
    const { replaceState } = browser(`#token=${token}`);
    expect(captureInvitationFromLocation()).toBe(token);
    expect(replaceState).toHaveBeenCalledWith({ idx: 1 }, '', '/convite');
    expect(storedInvitation()).toBe(token);
    forgetInvitation();
    expect(storedInvitation()).toBeNull();
  });
  it('fragmento malformado é removido da barra de endereço e não substitui o convite já guardado', () => {
    const storage = new MemoryStorage(); storage.setItem('condpulse:invitation', token);
    const { replaceState } = browser('#token=curto', storage);
    expect(captureInvitationFromLocation()).toBe(token);
    expect(replaceState).toHaveBeenCalledOnce();
    expect(storedInvitation()).toBe(token);
  });
  it('sem fragmento não mexe no histórico; valor adulterado no armazenamento é ignorado', () => {
    const storage = new MemoryStorage(); storage.setItem('condpulse:invitation', 'x'.repeat(42));
    const { replaceState } = browser('', storage);
    expect(captureInvitationFromLocation()).toBeNull();
    expect(replaceState).not.toHaveBeenCalled();
  });
  it('armazenamento bloqueado não impede o aceite nesta visita', () => {
    const blocked = { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); }, removeItem: () => { throw new Error('blocked'); } };
    browser(`#token=${token}`, blocked);
    expect(captureInvitationFromLocation()).toBe(token);
    expect(storedInvitation()).toBeNull();
    expect(() => forgetInvitation()).not.toThrow();
  });
  it('convite de gestor usa chave própria e a saída da conta descarta os dois', () => {
    const storage = new MemoryStorage(); storage.setItem('condpulse:invitation', token);
    browser(`#token=${'M'.repeat(43)}`, storage);
    expect(captureInvitationFromLocation('manager')).toBe('M'.repeat(43));
    expect(storedInvitation('manager')).toBe('M'.repeat(43));
    expect(storedInvitation()).toBe(token);
    forgetInvitation('manager');
    expect(storedInvitation('manager')).toBeNull(); expect(storedInvitation()).toBe(token);
    storage.setItem('condpulse:manager-invitation', 'M'.repeat(43));
    forgetInvitation();
    expect(storedInvitation()).toBeNull(); expect(storedInvitation('manager')).toBeNull();
  });
});
