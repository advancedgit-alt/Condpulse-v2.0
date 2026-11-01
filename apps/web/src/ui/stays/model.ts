import { displayText } from '../../api/text';
import { addDays } from '../dateTime';
import { stayLevelLabels, type Stay } from '../../api/stays';

/**
 * Regras puras das telas de permanência (CPX-017 a CPX-019). O servidor confere tudo de novo; aqui só se decide o que
 * aparece e se evita enviar o que ele recusaria com certeza.
 */
export type StayTone = 'status-overdue' | 'status-pending' | 'status-ready' | 'status-neutral';

/** Situação da linha: vencidas em destaque (N1/N2 em vermelho, vencida sem nível em atenção). */
export function stayStatus(stay: Pick<Stay, 'state' | 'level' | 'justified'>): { label: string; tone: StayTone } {
  if (stay.state === 'CLOSED') return { label: 'Saída registrada', tone: 'status-neutral' };
  if (stay.state === 'VOIDED') return { label: 'Entrada corrigida', tone: 'status-neutral' };
  if (stay.level === 'NONE') return { label: 'No prazo', tone: 'status-ready' };
  const label = stay.justified ? `${stayLevelLabels[stay.level]} · justificada` : stayLevelLabels[stay.level];
  return { label, tone: stay.level === 'DUE' ? 'status-pending' : 'status-overdue' };
}

/** Contagem do cabeçalho do painel. */
export function staySummary(stays: readonly Pick<Stay, 'overdue'>[]): string {
  const overdue = stays.filter((stay) => stay.overdue).length;
  const total = stays.length === 1 ? '1 permanência aberta' : `${stays.length} permanências abertas`;
  return overdue === 0 ? total : `${total} · ${overdue === 1 ? '1 vencida' : `${overdue} vencidas`}`;
}

/** Motivo da justificativa ou a mensagem do problema (3 a 500 caracteres, como o servidor). */
export function justificationReason(value: string): string | { problem: string } {
  const reason = displayText(value, 500);
  if (!reason || Array.from(reason).length < 3) return { problem: 'Informe o motivo, com 3 a 500 caracteres.' };
  return reason;
}

/** Dias aceitos pelo relatório de fechamento: hoje e até 31 dias atrás, no fuso do condomínio. */
export function dayCloseRange(today: string): { min: string; max: string } {
  return { min: addDays(today, -31), max: today };
}
export function dayCloseDate(value: string, today: string): string | null {
  const { min, max } = dayCloseRange(today);
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && value >= min && value <= max ? value : null;
}
