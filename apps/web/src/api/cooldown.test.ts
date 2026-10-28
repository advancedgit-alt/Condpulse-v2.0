import { describe, expect, it } from 'vitest';
import { parseRetryAfterSeconds, remainingCooldown } from './cooldown';

describe('espera segura de autenticação', () => {
  it('aceita apenas segundos inteiros limitados e nunca reflete o header', () => {
    for (const [header, expected] of [['0', 1], ['1', 1], ['60', 60], ['900', 900]] as const) {
      expect(parseRetryAfterSeconds(header)).toBe(expected);
    }
    for (const header of [null, '', '001', '-1', '1.5', '9000', 'Infinity', 'Thu, 01 Jan 2037 00:00:00 GMT', 'PRIVATE\r\nHEADER']) {
      expect(parseRetryAfterSeconds(header)).toBe(60);
    }
  });
  it('calcula o prazo por relógio monotônico e não libera antes do limite', () => {
    expect(remainingCooldown(61_000, 1_001)).toBe(60);
    expect(remainingCooldown(61_000, 60_999)).toBe(1);
    expect(remainingCooldown(61_000, 61_000)).toBe(0);
    expect(remainingCooldown(undefined, 1_000)).toBe(0);
  });
});
