import type { RequestCategory, RequestState } from '../api/requests';

/** Rótulos dos pedidos (MOR-07): enviado, em análise, em execução, aguardando a unidade, concluído e encerrado. */
export const categoryLabels: Record<RequestCategory, string> = {
  MAINTENANCE: 'Manutenção', CLEANING: 'Limpeza', SECURITY: 'Segurança', NOISE: 'Barulho', ADMINISTRATIVE: 'Administrativo', OTHER: 'Outro',
};
export const stateLabels: Record<RequestState, [string, string]> = {
  OPEN: ['Enviado', 'status-pending'], TRIAGED: ['Em análise', 'status-pending'], IN_PROGRESS: ['Em execução', 'status-ready'],
  WAITING_RESIDENT: ['Aguardando a unidade', 'status-pending'], RESOLVED: ['Concluído', 'status-ready'], CLOSED: ['Encerrado', 'status-neutral'],
};
/** Data civil AAAA-MM-DD exibida como DD/MM/AAAA, sem conversão de fuso. */
export function civilDate(value: string): string {
  const [year, month, day] = value.split('-');
  return `${day}/${month}/${year}`;
}
