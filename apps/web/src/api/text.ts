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
