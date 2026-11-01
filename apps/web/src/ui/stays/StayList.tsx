import type { ReactNode } from 'react';
import { closureLabels, expectedSourceLabels, stayKindLabels, stayOrigin, type Stay } from '../../api/stays';
import { formatWhen } from '../dateTime';
import { stayStatus } from './model';

const place = (stay: Pick<Stay, 'unitLabel' | 'blockName'>) => `${stay.blockName ? `${stay.blockName} · ` : ''}${stay.unitLabel}`;

/**
 * Linhas de permanência com a origem do dado visível (R5.10): "aberta" quer dizer sem registro de saída, não prova de que
 * a pessoa continua no condomínio. children recebe ações por linha (justificar, confirmar saída).
 */
export function StayList({ stays, timeZone, label, action, showJustification = true }: { stays: readonly Stay[]; timeZone: string; label: string;
  action?: (stay: Stay) => ReactNode; showJustification?: boolean }) {
  return <ol className="management-list" aria-label={label}>{stays.map((stay) => {
    const status = stayStatus(stay);
    return <li key={stay.stayId} className="management-row" data-overdue={stay.overdue ? 'true' : undefined}>
      <div><strong><bdi>{stay.visitorName ?? 'Pessoa sem nome visível'}</bdi></strong> <span className={`status-pill ${status.tone}`}>{status.label}</span>
        <span>{stayKindLabels[stay.kind]} · unidade {place(stay)} · entrada em {formatWhen(stay.enteredAt, timeZone)}</span>
        <span>Saída prevista {formatWhen(stay.expectedExitAt, timeZone)} ({expectedSourceLabels[stay.expectedSource]})</span>
        <span className="field-help">Origem: {stayOrigin(stay)}. Sem registro de saída não prova que a pessoa continua no condomínio.</span>
        {stay.closure && stay.closedAt ? <span>Encerrada: {closureLabels[stay.closure]} em {formatWhen(stay.closedAt, timeZone)}</span> : null}
        {showJustification && stay.justification ? <span>Justificativa{stay.justifiedAt ? ` (${formatWhen(stay.justifiedAt, timeZone)})` : ''}: <bdi>{stay.justification}</bdi></span> : null}</div>
      {action ? action(stay) : null}
    </li>;
  })}</ol>;
}
