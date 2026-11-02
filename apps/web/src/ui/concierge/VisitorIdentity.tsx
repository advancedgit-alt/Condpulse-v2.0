import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { stayIdentities, type IdentityBasis, type IdentityCandidate } from '../../api/stayIdentities';
import { Notice } from '../primitives';
import { ActionFeedback, useAction, useManagement } from '../management/shared';
import { conciergeKey, codeOf } from './shared';

/** Fieldset dentro do formulário de entrada; nenhuma associação automática por nome/documento. */
export function VisitorIdentity({ basis, busy }: { basis: IdentityBasis; busy: boolean }) {
  const scope = useManagement(); const queries = useQueryClient(); const search = useAction(); const verify = useAction();
  const [prefix, setPrefix] = useState(''); const [candidates, setCandidates] = useState<IdentityCandidate[] | null>(null);
  const [selected, setSelected] = useState<IdentityCandidate | null>(null); const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState<{ canonicalVisitorId: string | null; reason: string } | null>(null);
  const [documentChecked, setDocumentChecked] = useState(false); const [requestId] = useState(() => crypto.randomUUID());
  const key = conciergeKey(scope, 'identity', basis.arrivalId, basis.permitId);
  const source = useQuery({ queryKey: key, queryFn: ({ signal }) => stayIdentities.source(scope.tenantId, basis, signal),
    retry: false, refetchOnWindowFocus: false });
  // Compatibilidade durante a publicação por etapas: só a ausência da rota oculta este complemento.
  if (codeOf(source.error) === 'API_NOT_AVAILABLE') return null;
  const locked = busy || verify.state.pending || search.state.pending;
  async function find() {
    if (prefix.trim().length < 3 || locked) return;
    await search.run(() => stayIdentities.search(scope.tenantId, prefix.trim()), '', (rows) => { setCandidates(rows); setSelected(null); });
  }
  async function confirm() {
    if (!documentChecked || reason.trim().length < 3 || locked) return;
    const body = submitted ?? { canonicalVisitorId: selected?.visitorId ?? null, reason: reason.trim() };
    setSubmitted(body);
    await verify.run(() => stayIdentities.verify(scope.tenantId, { ...basis, requestId, ...body }), 'Conferência de identidade registrada.', (value) => { queries.setQueryData(key, value); });
  }
  return <fieldset className="panel" disabled={locked}>
    <legend>Identidade para controle de reentrada</legend>
    <p className="field-help">Confira a pessoa e procure um cadastro anterior antes de registrar uma nova identidade. Nome e final de documento são apenas apoio à conferência humana. Não informe documento completo.</p>
    {source.isPending ? <p role="status">Consultando conferência…</p> : source.isError ? <Notice tone="warning">Não foi possível consultar a identidade. <button type="button" onClick={() => void source.refetch()}>Consultar novamente</button></Notice>
      : source.data ? <Notice tone="success">Este cadastro tem conferência ativa da portaria. Referência: <code>{source.data.checkId}</code>. Uma associação incorreta exige correção pela gestão, com motivo e histórico.</Notice>
        : <>
          <label>Buscar identidade já conferida<input disabled={submitted !== null} value={prefix} onChange={(event) => { setPrefix(event.target.value); setSelected(null); setCandidates(null); }} minLength={3} maxLength={80} autoComplete="off" /></label>
          <button type="button" className="button button-secondary" disabled={locked || submitted !== null || prefix.trim().length < 3} onClick={() => void find()}>Buscar cadastro anterior</button>
          <ActionFeedback state={search.state} success={false} />
          {candidates !== null ? <div><p>{candidates.length ? 'Escolha somente após conferir que é a mesma pessoa:' : 'Nenhuma identidade encontrada com esse início de nome.'}</p>
            {candidates.map((candidate) => <label className="checkbox-row" key={candidate.visitorId}><input disabled={submitted !== null} type="radio" name={`identity-${basis.arrivalId ?? basis.permitId}`} checked={selected?.visitorId === candidate.visitorId}
              onChange={() => setSelected(candidate)} /> <bdi>{candidate.name}</bdi>{candidate.documentLast4 ? ` · final ${candidate.documentLast4}` : ''}</label>)}
            <label className="checkbox-row"><input disabled={submitted !== null} type="radio" name={`identity-${basis.arrivalId ?? basis.permitId}`} checked={selected === null} onChange={() => setSelected(null)} /> Cadastros conferidos: esta é uma nova identidade</label>
          </div> : null}
          <label className="checkbox-row"><input disabled={submitted !== null} type="checkbox" checked={documentChecked} onChange={(event) => setDocumentChecked(event.target.checked)} /> Conferi o documento e a associação escolhida pessoalmente</label>
          <label>Motivo da conferência<input disabled={submitted !== null} value={reason} onChange={(event) => setReason(event.target.value)} minLength={3} maxLength={500} autoComplete="off" /></label>
          <button type="button" className="button button-secondary" disabled={locked || !documentChecked || reason.trim().length < 3 || candidates === null}
            onClick={() => void confirm()}>{selected ? 'Vincular à identidade conferida' : 'Registrar nova identidade conferida'}</button>
          <ActionFeedback state={verify.state} />
          {submitted !== null && verify.state.error ? <Notice tone="warning">Confira o resultado antes de mudar os dados. Repetir envia a mesma conferência. <button type="button" onClick={() => void source.refetch()}>Consultar conferência registrada</button></Notice> : null}
        </>}
  </fieldset>;
}
