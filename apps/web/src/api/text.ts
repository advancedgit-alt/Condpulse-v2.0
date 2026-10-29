/**
 * Texto exibido a pessoas (nomes, motivos), com a mesma regra do servidor (DisplayText.java): qualquer sequência de
 * espaço Unicode vira um espaço, o comprimento é contado em code points (como o PostgreSQL) e caracteres de controle,
 * de formatação invisível (U+200B, U+202E…), de uso privado, substitutos isolados e não atribuídos são recusados.
 */
const WHITESPACE = /\p{White_Space}+/gu;
const HIDDEN = /[\p{Cc}\p{Cf}\p{Co}\p{Cn}\p{Cs}]/u;

/** Espaços Unicode reduzidos a um espaço, sem espaço nas pontas. Não valida. */
export function collapseText(value: string): string {
  return value.replace(WHITESPACE, ' ').replace(/^ +| +$/g, '');
}
export function codePoints(value: string): number {
  return [...value].length;
}
/** Forma normalizada, ou null quando vazia, maior que o limite ou com caractere não exibível. */
export function displayText(value: string, maximum: number): string | null {
  const normalized = collapseText(value);
  if (!normalized || codePoints(normalized) > maximum || HIDDEN.test(normalized)) return null;
  return normalized;
}

/**
 * Texto de várias linhas (corpo de aviso), com a mesma regra do servidor (DisplayText.normalizeMultiline): cada linha
 * normalizada como displayText, quebras mantidas (no máximo uma linha em branco seguida), sem linhas em branco nas
 * pontas; null quando vazio, maior que o limite (quebras incluídas) ou com caractere não exibível.
 */
export function multilineText(value: string, maximum: number): string | null {
  const lines: string[] = [];
  let blank = false;
  for (const line of value.split(/\r\n|\r|\n|\u2028|\u2029/)) {
    const normalized = collapseText(line);
    if (!normalized) { if (lines.length > 0 && !blank) { lines.push(''); blank = true; } continue; }
    if (HIDDEN.test(normalized)) return null;
    lines.push(normalized); blank = false;
  }
  while (lines.length > 0 && lines.at(-1) === '') lines.pop();
  const joined = lines.join('\n');
  if (!joined || codePoints(joined) > maximum) return null;
  return joined;
}
