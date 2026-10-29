import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import { api, ApiError, newOperationKey } from '../api/client';
import type { CancelReservation, CreateReservation, Reservation } from '../api/types';
import { Notice, errorMessage } from './primitives';
import { useAuth } from './Auth';

export interface Scope { accountId: string; tenantId: string; unitId: string; route: string }
type Intent = Scope & ({ kind: 'create'; body: CreateReservation } | { kind: 'cancel'; reservationId: string; body: CancelReservation; pendingRequest: boolean }) & { key: string };
interface Operation { intent: Intent; status: 'pending' | 'uncertain' | 'success' | 'failed'; error?: unknown; result?: Reservation }
interface OperationsValue {
  locked: boolean;
  completed: number;
  create(scope: Scope, body: CreateReservation): Promise<boolean>;
  /** pendingRequest: retirada de pedido ainda não decidido, com texto próprio no resultado. */
  cancel(scope: Scope, reservationId: string, body: CancelReservation, pendingRequest?: boolean): Promise<boolean>;
}
const OperationsContext = createContext<OperationsValue | null>(null);
export const scopeKey = (scope: Pick<Scope, 'accountId' | 'tenantId' | 'unitId'>) => ['private', scope.accountId, 'tenant', scope.tenantId, 'unit', scope.unitId] as const;

/** O título segue o estado devolvido pelo servidor: pedido pendente nunca aparece como reserva confirmada. */
function successTitle(operation: Operation): string {
  if (operation.intent.kind === 'cancel') return operation.intent.pendingRequest ? 'Pedido retirado.' : 'Reserva cancelada.';
  if (operation.result?.status === 'PENDING_APPROVAL') return 'Pedido enviado para aprovação.';
  return operation.result?.status === 'CONFIRMED' ? 'Reserva confirmada.' : 'Solicitação registrada.';
}

export function OperationsProvider({ children }: { children: ReactNode }) {
  const queries = useQueryClient();
  const auth = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [operation, setOperation] = useState<Operation | null>(null);
  const [completed, setCompleted] = useState(0);
  const [reconciled, setReconciled] = useState(false);
  const current = useRef<Operation | null>(null);
  const network = useRef(false);
  const alive = useRef(true);
  const controller = useRef<AbortController | null>(null);
  const tray = useRef<HTMLDivElement>(null);
  useEffect(() => { alive.current = true; return () => { alive.current = false; controller.current?.abort(); }; }, []);
  useEffect(() => { setReconciled(false); }, [operation?.intent.key]);
  useEffect(() => { if (operation) tray.current?.focus(); }, [operation?.status, operation?.intent.key]);
  const locked = operation?.status === 'pending' || operation?.status === 'uncertain';
  useEffect(() => {
    if (!locked) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [locked]);
  function update(value: Operation | null) { current.current = value; if (alive.current) setOperation(value); }
  async function perform(intent: Intent): Promise<boolean> {
    if (network.current) return false;
    network.current = true; controller.current = new AbortController();
    update({ intent, status: 'pending' });
    try {
      const result = intent.kind === 'create'
        ? await api.createReservation(intent.tenantId, intent.body, intent.key, controller.current.signal)
        : await api.cancelReservation(intent.tenantId, intent.reservationId, intent.body, intent.key, controller.current.signal);
      if (!alive.current) return false;
      update({ intent, status: 'success', result: result.data });
      setCompleted(value => value + 1);
      await queries.invalidateQueries({ queryKey: scopeKey(intent) });
      return true;
    } catch (error) {
      if (!alive.current) return false;
      const uncertain = !(error instanceof ApiError) || error.uncertain || error.status >= 500 || error.code === 'IDEMPOTENCY_KEY_EXPIRED';
      update({ intent, status: uncertain ? 'uncertain' : 'failed', error });
      if (error instanceof ApiError && [403, 404].includes(error.status)) {
        window.dispatchEvent(new CustomEvent('condpulse:context-denied', { detail: { scope: { accountId: intent.accountId, tenantId: intent.tenantId, unitId: intent.unitId, route: intent.route }, error } }));
      }
      if (error instanceof ApiError && error.code === 'AMENITY_POLICY_CHANGED') {
        await queries.invalidateQueries({ queryKey: scopeKey(intent) });
      }
      if (error instanceof ApiError && error.code === 'RESERVATION_CONFLICT') {
        // Consulta a ocupação atual; não repete o comando nem cria uma intenção nova.
        await queries.invalidateQueries({ queryKey: [...scopeKey(intent), 'availability'] });
      }
      return false;
    } finally { network.current = false; }
  }
  function start(intent: Intent) {
    if (current.current?.status === 'pending' || current.current?.status === 'uncertain') return Promise.resolve(false);
    return perform(intent);
  }
  const isOriginalContext = operation?.intent.route === location.pathname;
  return <OperationsContext.Provider value={{ locked, completed,
    create: (scope, body) => start({ ...scope, kind: 'create', body, key: newOperationKey() }),
    cancel: (scope, reservationId, body, pendingRequest = false) => start({ ...scope, kind: 'cancel', reservationId, body, pendingRequest, key: newOperationKey() }),
  }}>
    {operation ? <div className="operation-tray" ref={tray} tabIndex={-1} role="region" aria-label="Acompanhamento da solicitação">
      {locked && !isOriginalContext ? <Notice tone="warning"><p>Há uma solicitação de outro contexto aguardando confirmação. Volte para concluí-la antes de iniciar outra.</p><button className="button button-secondary" onClick={() => navigate(operation.intent.route)}>Voltar à solicitação</button></Notice>
        : isOriginalContext && operation.status === 'pending' ? <Notice>Confirmando a operação com o servidor. Aguarde antes de enviar outro pedido.</Notice>
          : isOriginalContext && operation.status === 'uncertain' ? <Notice tone="warning"><strong>Resultado ainda não confirmado.</strong><p>{errorMessage(operation.error)}</p>{operation.error instanceof ApiError && operation.error.code === 'IDEMPOTENCY_KEY_EXPIRED'
            ? <><p>Esta solicitação não pode mais ser repetida. Confira a agenda e, se necessário, procure a gestão antes de encerrar esta pendência local.</p><label className="checkbox-label"><input type="checkbox" checked={reconciled} onChange={event => setReconciled(event.target.checked)} /><span>Conferi a agenda ou encaminhei o caso à gestão.</span></label><button className="button button-secondary" disabled={!reconciled} onClick={() => update(null)}>Encerrar pendência</button></>
            : <><p>A próxima tentativa usa a mesma solicitação, sem criar um novo pedido.</p><button className="button button-secondary" onClick={() => void perform(operation.intent)}>Tentar confirmar novamente</button></>}
            <p>Se sair, a referência local deste pedido será apagada. Confira a agenda no próximo acesso.</p><button className="text-button" onClick={() => void auth.logout()}>Sair com pendência</button></Notice>
            : isOriginalContext && operation.status === 'failed' ? <Notice tone="error"><p>{errorMessage(operation.error)}</p><button className="text-button" onClick={() => update(null)}>Fechar aviso</button></Notice>
              : isOriginalContext && operation.status === 'success' ? <Notice tone="success"><strong>{successTitle(operation)}</strong><p>{operation.intent.kind === 'create' && operation.result?.status === 'PENDING_APPROVAL' ? 'O servidor registrou o pedido, que ainda depende da decisão da gestão. Acompanhe o estado na agenda.' : 'O servidor registrou a operação. Consulte a lista para verificar o estado atual.'}</p><button className="text-button" onClick={() => update(null)}>Fechar aviso</button></Notice> : null}
    </div> : null}
    {children}
  </OperationsContext.Provider>;
}

export function useOperations(): OperationsValue {
  const context = useContext(OperationsContext);
  if (!context) throw new Error('Contexto de operações ausente.');
  return context;
}
