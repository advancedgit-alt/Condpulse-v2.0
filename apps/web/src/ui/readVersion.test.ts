// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';

// Renderizador mínimo: cada chamada do hook é uma renderização; o estado persiste entre elas como no React.
const slots: unknown[] = [];
let cursor = 0;
vi.mock('react', async (importOriginal) => ({
  ...await importOriginal<typeof import('react')>(),
  useState: <T>(initial: T | (() => T)) => {
    const index = cursor++;
    if (!(index in slots)) slots[index] = typeof initial === 'function' ? (initial as () => T)() : initial;
    return [slots[index] as T, (next: T) => { slots[index] = next; }];
  },
}));
const { useVersionedDraft } = await import('./readVersion');
const render = (current: number, reset: () => void) => { cursor = 0; return useVersionedDraft(current, reset); };

describe('useVersionedDraft', () => {
  it('congela a versão de origem, detecta a versão nova e só a adota com recarga explícita', () => {
    slots.length = 0;
    const reset = vi.fn();
    expect(render(3, reset)).toMatchObject({ version: 3, stale: false });
    // A lista foi consultada de novo e o registro mudou: o envio continua com a versão 3 e a tela pede revisão.
    const changed = render(4, reset);
    expect(changed).toMatchObject({ version: 3, stale: true });
    expect(reset).not.toHaveBeenCalled();
    changed.reload();
    expect(reset).toHaveBeenCalledTimes(1);
    expect(render(4, reset)).toMatchObject({ version: 4, stale: false });
  });
});
