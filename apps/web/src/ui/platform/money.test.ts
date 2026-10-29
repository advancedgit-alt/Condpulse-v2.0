// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { centsInput, formatCents, formatCivil, parseCents } from './money';

describe('valores e datas do financeiro', () => {
  it('converte reais em centavos sem arredondar', () => {
    expect(parseCents('1.234,56')).toBe(123_456);
    expect(parseCents('R$ 10')).toBe(1_000);
    expect(parseCents('0,5')).toBe(50);
    expect(parseCents('1000000,00')).toBe(100_000_000);
    for (const invalid of ['', '1,234', '1.23', '12,345', '-5', '1e3', '1.000.000,01', '10.00', 'abc', '1 000'])
      expect(parseCents(invalid), invalid).toBeNull();
  });
  it('mostra valores e datas civis no formato brasileiro', () => {
    expect(formatCents(123_456)).toBe('R$ 1.234,56');
    expect(centsInput(123_456)).toBe('1.234,56');
    expect(parseCents(centsInput(99))).toBe(99);
    expect(formatCivil('2030-01-31')).toBe('31/01/2030');
  });
});
