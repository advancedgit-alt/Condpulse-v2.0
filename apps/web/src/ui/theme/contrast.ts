/**
 * Contraste WCAG 2.x entre cores opacas sRGB (#RRGGBB), pela luminância relativa. Mesma fórmula de
 * docs/identidade/kit-v1.0/handoff/validate-contrast.py. Não arredonda: 4,459 continua abaixo de 4,5.
 */
const HEX = /^#[0-9a-f]{6}$/i;

export function isHexColor(value: unknown): value is string {
  return typeof value === 'string' && HEX.test(value);
}

function channels(hex: string): [number, number, number] {
  if (!isHexColor(hex)) throw new Error(`Cor inválida: ${hex}`);
  return [1, 3, 5].map((start) => parseInt(hex.slice(start, start + 2), 16)) as [number, number, number];
}

export function relativeLuminance(hex: string): number {
  const linear = (value: number) => {
    const c = value / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  const [r, g, b] = channels(hex);
  return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
}

export function contrastRatio(a: string, b: string): number {
  const first = relativeLuminance(a); const second = relativeLuminance(b);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}

/** Mistura linear em sRGB: weight 0 devolve `base`, 1 devolve `other`. */
export function mix(base: string, other: string, weight: number): string {
  const a = channels(base); const b = channels(other);
  const blend = (from: number, to: number) => Math.round(from + (to - from) * weight).toString(16).padStart(2, '0');
  return `#${blend(a[0], b[0])}${blend(a[1], b[1])}${blend(a[2], b[2])}`.toUpperCase();
}

/** Mínimos usados no tema: texto comum 4,5:1 (WCAG 1.4.3) e componente/ícone necessário 3:1 (WCAG 1.4.11). */
export const MIN_TEXT = 4.5;
export const MIN_NON_TEXT = 3;

export function passes(foreground: string, backgrounds: readonly string[], minimum: number): boolean {
  return backgrounds.every((background) => contrastRatio(foreground, background) >= minimum);
}
