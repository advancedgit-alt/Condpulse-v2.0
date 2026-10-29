// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { codePoints, collapseText, displayText } from './text';

/** Mesmos casos de DisplayTextTest.java: web e servidor aceitam e recusam o mesmo texto. */
describe('texto exibido', () => {
  it('junta espaços Unicode e conta code points', () => {
    expect(displayText(' Condomínio  \tSol　\n', 160)).toBe('Condomínio Sol');
    const houses = '\u{1F3E0}'.repeat(160);
    expect(houses.length).toBe(320);
    expect(displayText(houses, 160)).toBe(houses);
    expect(displayText(`${houses}\u{1F3E0}`, 160)).toBeNull();
    expect(displayText('a\u0085b', 160)).toBe('a b');
    expect(codePoints('🏠a')).toBe(2);
  });
  it('recusa vazio, controle e formatação invisível', () => {
    for (const value of ['', '   ', '  ', '​', 'a​b', 'a‮b', 'a⁦b', 'a﻿b', 'a\u0000b', 'a\u0007b',
      'ab', 'a\ud800b', 'a͸b']) expect(displayText(value, 160), JSON.stringify(value)).toBeNull();
    expect(displayText('Condomínio São João — Bloco 2 🏠', 160)).toBe('Condomínio São João — Bloco 2 🏠');
  });
  it('collapse não valida', () => {
    expect(collapseText('   ')).toBe('');
    expect(collapseText('a​b')).toBe('a​b');
    expect(collapseText('﻿a')).toBe('﻿a');
  });
});
