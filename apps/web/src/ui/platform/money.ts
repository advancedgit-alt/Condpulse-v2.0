/** Valores do financeiro em centavos de real (inteiros) e datas civis AAAA-MM-DD, sem fuso nem arredondamento. */
const BRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
export const MAX_CENTS = 100_000_000;

export function formatCents(cents: number): string {
  return BRL.format(cents / 100).replace(/ /g, ' ');
}
/** "1.234,56", "1234,5", "R$ 10" → centavos; null quando o texto não é um valor em reais válido. */
export function parseCents(text: string): number | null {
  const value = text.replace(/^\s*R\$\s*/i, '').trim();
  if (!/^(\d{1,3}(\.\d{3})+|\d+)(,\d{1,2})?$/.test(value)) return null;
  const [whole, fraction = ''] = value.replace(/\./g, '').split(',');
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  return Number.isSafeInteger(cents) && cents <= MAX_CENTS ? cents : null;
}
/** Valor para edição no formato brasileiro, sem símbolo: 123456 → "1.234,56". */
export function centsInput(cents: number): string {
  return formatCents(cents).replace(/^R\$\s*/, '');
}
export function formatCivil(date: string): string {
  const [year, month, day] = date.split('-');
  return `${day}/${month}/${year}`;
}
const PERIOD_LABELS = { MONTHLY: 'mês', YEARLY: 'ano' } as const;
export function perPeriod(period: 'MONTHLY' | 'YEARLY'): string { return PERIOD_LABELS[period]; }
export const METHOD_LABELS = { PIX: 'Pix', TRANSFERENCIA: 'Transferência', BOLETO: 'Boleto', OUTRO: 'Outro' } as const;
