import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { parcels, type Parcel } from '../api/parcels';
import { useAuth } from './Auth';
import { Empty, ErrorState, Icon, Loading, Notice } from './primitives';
import { dateInZone, formatWhen } from './dateTime';
import { ParcelDelegation } from './ParcelDelegation';
import './management/Management.css';

/**
 * Encomendas da unidade (MOR-05): somente o que a portaria registrou para esta unidade. A lista vem do servidor, que
 * confere o vínculo residencial em cada consulta; nada é inferido do aviso.
 */
export function ParcelsPage() {
  const { identity } = useAuth();
  const { tenantId = '', unitId = '' } = useParams();
  const membership = identity?.memberships.find((item) => item.tenantId === tenantId && item.unitId === unitId);
  if (!identity || !membership) return <div className="panel"><Empty title="Unidade indisponível">Selecione uma unidade vinculada ao seu cadastro para continuar.</Empty></div>;
  try { dateInZone(membership.timeZone); }
  catch { return <Notice tone="error">O fuso do condomínio precisa ser conferido pela gestão antes de consultar as encomendas.</Notice>; }
  return <UnitParcels key={`${identity.accountId}:${tenantId}:${unitId}`} accountId={identity.accountId} tenantId={tenantId} unitId={unitId}
    tenantName={membership.tenantName} timeZone={membership.timeZone} />;
}

function UnitParcels({ accountId, tenantId, unitId, tenantName, timeZone }: { accountId: string; tenantId: string; unitId: string; tenantName: string; timeZone: string }) {
  // Pendentes e retiradas em consultas separadas: retiradas recentes nunca empurram uma pendente antiga para fora da tela.
  const key = ['private', accountId, 'tenant', tenantId, 'unit', unitId, 'parcels'] as const;
  const waitingList = useQuery({ queryKey: [...key, 'RECEIVED'], queryFn: ({ signal }) => parcels.unitParcels(tenantId, unitId, 'RECEIVED', signal) });
  const deliveredList = useQuery({ queryKey: [...key, 'DELIVERED'], queryFn: ({ signal }) => parcels.unitParcels(tenantId, unitId, 'DELIVERED', signal) });
  const failure = waitingList.error ?? deliveredList.error;
  const code = failure instanceof ApiError ? failure.code : '';
  const fetching = waitingList.isFetching || deliveredList.isFetching;
  const refresh = () => { void waitingList.refetch(); void deliveredList.refetch(); };
  const waiting = waitingList.data?.parcels ?? [];
  const delivered = deliveredList.data?.parcels ?? [];
  return <>
    <div className="residence-heading"><div><span className="eyebrow">ENCOMENDAS</span><h1>Encomendas da unidade</h1>
      <p>Registradas pela portaria de {tenantName}. Na retirada, a portaria confere o documento de uma pessoa com vínculo com a unidade ou de quem você autorizar com código. Horários em {timeZone}.</p></div>
      <button className="button button-secondary button-compact" type="button" disabled={fetching} onClick={refresh}>Atualizar</button></div>
    {waitingList.isPending || deliveredList.isPending ? <div className="panel"><Loading label="Consultando encomendas…" /></div>
      : code === 'API_NOT_AVAILABLE' ? <div className="panel"><Empty title="Encomendas indisponíveis">As encomendas ainda não estão ativas neste ambiente.</Empty></div>
        : code === 'UNIT_ACCESS_DENIED' || code === 'TENANT_ACCESS_DENIED' ? <div className="panel"><Empty title="Unidade indisponível">As encomendas desta unidade não estão disponíveis para o seu vínculo.</Empty></div>
          : failure && (!waitingList.data || !deliveredList.data) ? <ErrorState error={failure} retry={refresh} />
            : <>
              <section className="management-section" aria-labelledby="waiting-title">
                <div className="section-heading"><div><span className="section-index">A RETIRAR</span><h2 id="waiting-title">Aguardando retirada</h2></div></div>
                {waiting.length === 0 ? <div className="panel"><Empty title="Nada a retirar">Quando a portaria registrar uma encomenda para a unidade, ela aparecerá aqui.</Empty></div>
                  : <ol className="management-list" aria-label="Encomendas a retirar">{waiting.map((item) => <ParcelRow key={item.parcelId} parcel={item} timeZone={timeZone}>
                    <ParcelDelegation baseKey={key} tenantId={tenantId} parcel={item} timeZone={timeZone} /></ParcelRow>)}</ol>}
                {waitingList.data?.truncated ? <Notice tone="warning">A lista mostra as 100 encomendas mais antigas aguardando retirada.</Notice> : null}
              </section>
              <section className="management-section" aria-labelledby="delivered-title">
                <div className="section-heading"><div><span className="section-index">HISTÓRICO</span><h2 id="delivered-title">Retiradas</h2></div></div>
                {delivered.length === 0 ? <p className="field-help">Nenhuma retirada registrada recentemente.</p>
                  : <ol className="management-list" aria-label="Encomendas retiradas">{delivered.map((item) => <ParcelRow key={item.parcelId} parcel={item} timeZone={timeZone} />)}</ol>}
                {deliveredList.data?.truncated ? <Notice tone="warning">O histórico mostra as 100 retiradas mais recentes.</Notice> : null}
              </section>
            </>}
  </>;
}

function ParcelRow({ parcel, timeZone, children }: { parcel: Parcel; timeZone: string; children?: ReactNode }) {
  return <li className="management-row">
    <div><strong><Icon name="box" /> {parcel.quantity} {parcel.quantity === 1 ? 'volume' : 'volumes'}</strong>
      <span className={`status-pill ${parcel.status === 'RECEIVED' ? 'status-pending' : 'status-ready'}`}>{parcel.status === 'RECEIVED' ? 'A retirar' : 'Retirada'}</span>
      <span>Recebida em {formatWhen(parcel.receivedAt, timeZone)} · guardada em <bdi>{parcel.storageLocation}</bdi></span>
      {parcel.description ? <span><bdi>{parcel.description}</bdi></span> : null}
      {parcel.handoff ? <span>Retirada por <bdi>{parcel.handoff.recipientName}</bdi>{parcel.handoff.evidenceKind === 'DELEGATION' ? ' (pessoa autorizada pela unidade, com código)' : ''} em {formatWhen(parcel.handoff.deliveredAt, timeZone)}</span> : null}</div>
    {children}
  </li>;
}
