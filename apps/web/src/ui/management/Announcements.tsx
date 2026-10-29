import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { announcements, type AnnouncementAudience, type AnnouncementDraftBody, type ManagedAnnouncement } from '../../api/announcements';
import { management } from '../../api/management';
import { displayText, multilineText } from '../../api/text';
import { Empty, ErrorState, Loading, Notice } from '../primitives';
import { formatWhen } from '../dateTime';
import { codeOf, repeatFailure, settleFailure, usePendingOperation } from '../operationIntent';
import { ActionFeedback, ReasonForm, useAccessGuard, useAction, useManagement, managementKey, type ActionResult } from './shared';

const stateLabels: Record<ManagedAnnouncement['state'], [string, string]> = {
  DRAFT: ['Rascunho', 'status-pending'], PUBLISHED: ['Publicado', 'status-ready'], ARCHIVED: ['Arquivado', 'status-neutral'],
};
const audienceText: Record<AnnouncementAudience, string> = { TENANT: 'Condomínio inteiro', BLOCKS: 'Blocos', UNITS: 'Unidades' };
const accessCodes = ['MFA_REQUIRED', 'STAFF_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'];

/**
 * Avisos da gestão (CP-032): rascunho, público (condomínio, blocos ou unidades), publicação e arquivamento. Publicado não
 * muda de texto; para corrigir, arquive e publique outro. Aberturas registradas não são ciência jurídica.
 */
export function AnnouncementSection() {
  const scope = useManagement();
  const list = useQuery({ queryKey: managementKey(scope, 'announcements'), queryFn: ({ signal }) => announcements.managed(scope.tenantId, signal) });
  const blocks = useQuery({ queryKey: managementKey(scope, 'blocks'), queryFn: ({ signal }) => management.blocks(scope.tenantId, signal) });
  const units = useQuery({ queryKey: managementKey(scope, 'units'), queryFn: ({ signal }) => management.units(scope.tenantId, signal) });
  const [editing, setEditing] = useState<{ id: string | null; action: 'draft' | 'publish' | 'archive' } | null>(null);
  const [outcome, setOutcome] = useState<ActionResult | null>(null);
  if (useAccessGuard(list.error ?? blocks.error ?? units.error)) return null;
  const targets = {
    blocks: blocks.data?.map((block) => ({ id: block.blockId, label: block.name })) ?? [],
    units: units.data?.units.filter((unit) => unit.status === 'ACTIVE')
      .map((unit) => ({ id: unit.unitId, label: unit.blockName ? `${unit.blockName} · ${unit.label}` : unit.label })) ?? [],
  };
  const names = new Map([...targets.blocks, ...(units.data?.units.map((unit) => ({ id: unit.unitId, label: unit.blockName ? `${unit.blockName} · ${unit.label}` : unit.label })) ?? [])]
    .map((item) => [item.id, item.label]));
  const describe = (item: Pick<ManagedAnnouncement, 'audience' | 'audienceIds'>) => item.audience === 'TENANT' ? audienceText.TENANT
    : `${audienceText[item.audience]}: ${item.audienceIds.map((id) => names.get(id) ?? 'item não listado').join(', ')}`;
  const close = (result: ActionResult | null) => { setEditing(null); if (result) setOutcome(result); };
  const unavailable = codeOf(list.error) === 'API_NOT_AVAILABLE';
  return <section className="management-section" aria-labelledby="announcements-title">
    <div className="section-heading"><div><span className="section-index">AVISOS</span><h2 id="announcements-title">Avisos do condomínio</h2></div>
      <div className="button-row">
        <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar</button>
        {!unavailable && editing?.id !== null ? <button className="button button-compact" type="button" onClick={() => { setOutcome(null); setEditing({ id: null, action: 'draft' }); }}>Novo aviso</button> : null}
      </div></div>
    <p className="field-help">Texto simples, sem formatação. A unidade vê só avisos publicados para ela e recebe o texto ao abrir; a abertura fica registrada com a conta de quem abriu, mas não é ciência jurídica nem prova de recebimento.</p>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {editing?.id === null ? <DraftForm targets={targets} close={close} /> : null}
    {unavailable ? <div className="panel"><Empty title="Avisos indisponíveis">Os avisos ainda não estão ativos neste ambiente.</Empty></div>
      : list.isPending ? <div className="panel"><Loading label="Consultando avisos…" /></div>
        : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
          : !list.data || list.data.announcements.length === 0 ? <div className="panel"><Empty title="Nenhum aviso">Crie um rascunho para publicar ao condomínio, a blocos ou a unidades.</Empty></div>
            : <ol className="management-list" aria-label="Avisos do condomínio">{list.data.announcements.map((item) => {
              const [label, tone] = stateLabels[item.state];
              const open = editing?.id === item.announcementId ? editing.action : null;
              return <li key={item.announcementId} className="management-row">
                <div><strong><bdi>{item.title}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
                  <span>{describe(item)}{item.publishedAt ? ` · publicado em ${formatWhen(item.publishedAt, scope.timeZone)}` : ` · atualizado em ${formatWhen(item.updatedAt, scope.timeZone)}`}
                    {item.archivedAt ? ` · arquivado em ${formatWhen(item.archivedAt, scope.timeZone)}` : ''}</span>
                  {item.publishedAt ? <span>Aberturas registradas: {item.opens}. Abertura não é ciência jurídica nem confirmação de recebimento.</span> : null}
                  <p className="announcement-body"><bdi>{item.body}</bdi></p></div>
                {open === 'draft' ? <DraftForm targets={targets} current={item} close={close} />
                  : open === 'publish' ? <PublishForm item={item} audience={describe(item)} close={close} />
                    : open === 'archive' ? <ArchiveForm item={item} close={close} />
                      : <div className="button-row">
                        {item.state === 'DRAFT' ? <>
                          <button className="button button-compact" type="button" onClick={() => { setOutcome(null); setEditing({ id: item.announcementId, action: 'publish' }); }}>Publicar</button>
                          <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setEditing({ id: item.announcementId, action: 'draft' }); }}>Editar rascunho</button>
                          <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setEditing({ id: item.announcementId, action: 'archive' }); }}>Descartar</button>
                        </> : item.state === 'PUBLISHED' ? <button className="button button-secondary button-compact" type="button"
                          onClick={() => { setOutcome(null); setEditing({ id: item.announcementId, action: 'archive' }); }}>Arquivar</button> : null}
                      </div>}
              </li>;
            })}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra os 100 avisos mais recentes.</Notice> : null}
  </section>;
}

interface Target { id: string; label: string }
/** Rascunho novo (chave de operação pendente por condomínio) ou edição pela versão lida. */
function DraftForm({ targets, current, close }: { targets: { blocks: Target[]; units: Target[] }; current?: ManagedAnnouncement;
  close(result: ActionResult | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const intent = usePendingOperation(`${scope.tenantId}:announcement-draft`);
  const pending = !current ? intent.lockedBody as AnnouncementDraftBody | null : null;
  const [title, setTitle] = useState(current?.title ?? pending?.title ?? '');
  const [body, setBody] = useState(current?.body ?? pending?.body ?? '');
  const [audience, setAudience] = useState<AnnouncementAudience>(current?.audience ?? pending?.audience ?? 'TENANT');
  const [selected, setSelected] = useState<string[]>(current?.audienceIds ?? pending?.audienceIds ?? []);
  // Versão do conteúdo carregado no formulário: a lista atualizada (outro gestor salvou) não troca a versão enviada.
  // Assim a gravação sobre um rascunho alterado recebe ANNOUNCEMENT_CHANGED em vez de sobrescrever o texto do outro.
  const [readVersion] = useState(current?.version ?? 0);
  const outdated = current !== undefined && current.version !== readVersion;
  const locked = !current && intent.locked;
  const normalizedTitle = displayText(title, 160);
  const normalizedBody = multilineText(body, 5000);
  const ids = audience === 'TENANT' ? [] : selected.filter((id) => (audience === 'BLOCKS' ? targets.blocks : targets.units).some((item) => item.id === id) || current?.audienceIds.includes(id));
  const valid = normalizedTitle !== null && [...normalizedTitle].length >= 3 && normalizedBody !== null && (audience === 'TENANT' || (ids.length >= 1 && ids.length <= 200));
  const id = current ? `draft-${current.announcementId}` : 'draft-new';
  const refresh = () => queries.invalidateQueries({ queryKey: managementKey(scope, 'announcements') });
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    const draft: AnnouncementDraftBody = { title: normalizedTitle!, body: normalizedBody!, audience, audienceIds: [...ids].sort() };
    if (current) {
      const ok = await action.run(() => announcements.editDraft(scope.tenantId, current.announcementId, { ...draft, expectedVersion: readVersion }),
        (saved) => ({ tone: 'success', text: `Rascunho salvo: ${saved.title}.` }), async () => { await refresh(); });
      if (ok) close({ tone: 'success', text: `Rascunho salvo: ${draft.title}. Ainda não aparece para as unidades.` });
      else void refresh();
      return;
    }
    const key = intent.keyFor(JSON.stringify(draft), draft);
    let failure: unknown = null;
    let result: ActionResult | null = null;
    const ok = await action.run(async () => {
      try { return await announcements.createDraft(scope.tenantId, key, draft); }
      catch (error) { failure = error; throw repeatFailure(error); }
      finally { intent.settle(); }
    }, (done) => {
      result = { tone: done.replayed ? 'info' : 'success', text: done.replayed ? `Este rascunho já estava criado: ${done.announcement.title} (${stateLabels[done.announcement.state][0].toLowerCase()}).`
        : `Rascunho criado: ${done.announcement.title}. Ainda não aparece para as unidades; publique quando estiver pronto.` };
      return result;
    }, async () => { await refresh(); });
    if (ok) { intent.finish(); close(result); return; }
    if (accessCodes.includes(codeOf(failure))) return;
    settleFailure(failure, intent.locked, intent, intent.setLocked);
    void refresh();
  }
  const options = audience === 'BLOCKS' ? targets.blocks : audience === 'UNITS' ? targets.units : [];
  const busy = action.state.pending || locked || (!current && intent.inFlight);
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={current ? `Editar rascunho ${current.title}` : 'Novo aviso'}>
    <label htmlFor={`${id}-title`}>Título</label>
    <input id={`${id}-title`} maxLength={160} value={title} onChange={(event) => setTitle(event.target.value)} disabled={busy} autoComplete="off" />
    <label htmlFor={`${id}-body`}>Texto</label>
    <textarea id={`${id}-body`} maxLength={5000} rows={6} value={body} onChange={(event) => setBody(event.target.value)} disabled={busy} aria-describedby={`${id}-body-help`} />
    <span className="field-help" id={`${id}-body-help`}>Texto simples. Não publique dados de inadimplência, documentos pessoais ou dados sensíveis de moradores.</span>
    <fieldset className="weekday-fieldset"><legend>Público</legend>
      {(['TENANT', 'BLOCKS', 'UNITS'] as const).map((value) => <label key={value} className="weekday-option">
        <input type="radio" name={`${id}-audience`} value={value} checked={audience === value} disabled={busy}
          onChange={() => { setAudience(value); setSelected([]); }} />{audienceText[value]}</label>)}
    </fieldset>
    {audience !== 'TENANT' ? options.length === 0 ? <Notice tone="warning">Nenhum {audience === 'BLOCKS' ? 'bloco' : 'unidade ativa'} cadastrado para escolher.</Notice>
      : <fieldset className="audience-options"><legend>{audience === 'BLOCKS' ? 'Blocos' : 'Unidades ativas'} ({ids.length} de até 200)</legend>
        {options.map((item) => <label key={item.id} className="weekday-option"><input type="checkbox" checked={selected.includes(item.id)} disabled={busy}
          onChange={(event) => setSelected((previous) => event.target.checked ? [...previous, item.id] : previous.filter((value) => value !== item.id))} /><bdi>{item.label}</bdi></label>)}
      </fieldset> : null}
    <ActionFeedback state={action.state} success={false} />
    {outdated ? <Notice tone="warning">Outra pessoa da gestão salvou este rascunho depois que você abriu o formulário. Volte e abra de novo para ver a versão atual antes de salvar; salvar agora não sobrescreve a alteração dela.</Notice> : null}
    {!current && intent.completedElsewhere ? <Notice>Este rascunho já foi criado pelo formulário aberto antes. Criar de novo só confirma o mesmo rascunho; confira a lista.</Notice> : null}
    {locked ? <Notice tone="warning">O resultado não foi confirmado e o rascunho ficou travado, mesmo se você fechar o formulário. Use “Tentar confirmar o mesmo rascunho”: se ele já foi criado, nada novo é gravado.</Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || action.state.pending}>{action.state.pending ? 'Salvando…' : locked ? 'Tentar confirmar o mesmo rascunho' : current ? 'Salvar rascunho' : 'Criar rascunho'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.state.pending}>Voltar</button>
    </div>
  </form>;
}

function PublishForm({ item, audience, close }: { item: ManagedAnnouncement; audience: string; close(result: ActionResult | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  async function submit(event: FormEvent) {
    event.preventDefault();
    const ok = await action.run(() => announcements.publish(scope.tenantId, item.announcementId, item.version),
      (published) => ({ tone: 'success', text: `Aviso publicado: ${published.title}, para ${audience.toLowerCase()}.` }),
      async () => { await queries.invalidateQueries({ queryKey: managementKey(scope, 'announcements') }); });
    if (ok) close({ tone: 'success', text: `Aviso publicado: ${item.title}. As unidades do público já podem abrir.` });
    else void queries.invalidateQueries({ queryKey: managementKey(scope, 'announcements') });
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Publicar ${item.title}`}>
    <Notice tone="warning">Publicar para <strong>{audience}</strong>? Depois de publicado, o texto e o público não mudam; para corrigir, arquive e publique outro.</Notice>
    <ActionFeedback state={action.state} success={false} />
    <div className="button-row">
      <button className="button" type="submit" disabled={action.state.pending}>{action.state.pending ? 'Publicando…' : 'Confirmar publicação'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.state.pending}>Voltar</button>
    </div>
  </form>;
}

function ArchiveForm({ item, close }: { item: ManagedAnnouncement; close(result: ActionResult | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const draft = item.state === 'DRAFT';
  async function archive(reason: string | null) {
    const ok = await action.run(() => announcements.archive(scope.tenantId, item.announcementId, item.version, reason),
      () => ({ tone: 'success', text: '' }), async () => { await queries.invalidateQueries({ queryKey: managementKey(scope, 'announcements') }); });
    if (ok) close({ tone: 'success', text: draft ? `Rascunho descartado: ${item.title}.` : `Aviso arquivado: ${item.title}. Ele sai das unidades; as aberturas continuam registradas.` });
    else void queries.invalidateQueries({ queryKey: managementKey(scope, 'announcements') });
  }
  return <div className="reason-form">
    <Notice tone="warning">{draft ? 'Descartar o rascunho? Ele nunca foi publicado e não poderá ser editado depois.'
      : 'Arquivar o aviso? Ele sai das unidades e não volta a ser publicado; as aberturas registradas continuam guardadas.'}</Notice>
    <ReasonForm id={`archive-${item.announcementId}`} label="Motivo, registrado na auditoria" required={false} pending={action.state.pending}
      confirmLabel={draft ? 'Descartar rascunho' : 'Arquivar aviso'} tone="danger" submit={(reason) => void archive(reason)} cancel={() => close(null)} />
    <ActionFeedback state={action.state} success={false} />
  </div>;
}
