import { useRef, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ATTACHMENTS_PER_REQUEST, attachments, attachmentTypes, fileProblem, sizeLabel, type Attachment } from '../api/attachments';
import { Notice, Loading, ErrorState, errorMessage } from './primitives';
import { formatWhen } from './dateTime';
import { codeOf, uncertain } from './operationIntent';

const rejectionLabels: Record<NonNullable<Attachment['rejection']>, string> = {
  SIZE_MISMATCH: 'o tamanho recebido não confere com o declarado',
  TYPE_MISMATCH: 'o conteúdo não é foto JPEG/PNG ou PDF como informado',
  NOT_UPLOADED: 'o arquivo não chegou ao armazenamento',
  UPLOAD_EXPIRED: 'o prazo de envio terminou antes do arquivo chegar',
};
type Phase = 'intent' | 'upload' | 'check';
const phaseLabels: Record<Phase, string> = { intent: 'Preparando o envio…', upload: 'Enviando o arquivo…', check: 'Conferindo o arquivo…' };

/**
 * Anexos de um pedido (CP-017), para a unidade e para a gestão. Lista os liberados; pendentes e recusados aparecem só
 * para quem enviou. O download pede um link de 60 segundos à API a cada clique. Módulo desligado no ambiente: a seção
 * some sem erro técnico.
 */
export function RequestAttachments({ tenantId, requestId, queryKey, canUpload, timeZone }:
  { tenantId: string; requestId: string; queryKey: readonly unknown[]; canUpload: boolean; timeZone: string }) {
  const queries = useQueryClient();
  const key = [...queryKey, 'attachments', requestId];
  const list = useQuery({ queryKey: key, queryFn: ({ signal }) => attachments.forRequest(tenantId, requestId, signal) });
  const [outcome, setOutcome] = useState<{ tone: 'success' | 'info' | 'error' | 'warning'; text: string } | null>(null);
  const [busyItem, setBusyItem] = useState<string | null>(null);
  if (codeOf(list.error) === 'API_NOT_AVAILABLE') return null;
  const refresh = () => queries.invalidateQueries({ queryKey: key });
  const items = list.data ?? [];
  const occupied = items.filter((item) => item.state === 'AVAILABLE' || item.state === 'PENDING').length;

  async function download(item: Attachment) {
    setBusyItem(item.attachmentId); setOutcome(null);
    try {
      const link = await attachments.download(tenantId, item.attachmentId);
      window.location.assign(link.url);
      setOutcome({ tone: 'info', text: `Download de ${item.fileName} iniciado. O link vale por 1 minuto; para baixar de novo, clique outra vez.` });
    } catch (error) {
      setOutcome({ tone: 'error', text: errorMessage(error) });
      void refresh();
    } finally { setBusyItem(null); }
  }
  async function check(item: Attachment) {
    setBusyItem(item.attachmentId); setOutcome(null);
    try { setOutcome(result(await attachments.complete(tenantId, item.attachmentId))); }
    catch (error) { setOutcome({ tone: uncertain(error) ? 'warning' : 'error', text: errorMessage(error) }); }
    finally { setBusyItem(null); void refresh(); }
  }

  return <div className="request-attachments" aria-label="Anexos do pedido" role="group">
    <strong>Anexos</strong>
    {list.isPending ? <Loading label="Consultando os anexos…" />
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : items.length === 0 ? <p className="field-help">Nenhum anexo neste pedido.</p>
          : <ul className="attachment-list" aria-label="Arquivos anexados">{items.map((item) =>
            <li key={item.attachmentId}>
              <span><bdi>{item.fileName}</bdi> · {sizeLabel(item.sizeBytes)} · enviado em {formatWhen(item.createdAt, timeZone)}{item.mine ? ' por você' : ''}</span>
              {item.state === 'AVAILABLE'
                ? <button className="button button-secondary button-compact" type="button" disabled={busyItem !== null}
                  aria-label={`Baixar ${item.fileName}`} onClick={() => void download(item)}>{busyItem === item.attachmentId ? 'Gerando link…' : 'Baixar'}</button>
                : item.state === 'PENDING'
                  ? <><span className="status-pill status-pending">Envio não confirmado</span>
                    <button className="button button-secondary button-compact" type="button" disabled={busyItem !== null}
                      aria-label={`Conferir envio de ${item.fileName}`} onClick={() => void check(item)}>{busyItem === item.attachmentId ? 'Conferindo…' : 'Conferir envio'}</button></>
                  : <span className="status-pill status-overdue">Recusado: {rejectionLabels[item.rejection!]}</span>}
            </li>)}</ul>}
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {canUpload && list.data ? occupied >= ATTACHMENTS_PER_REQUEST
      ? <p className="field-help">Este pedido já tem o máximo de {ATTACHMENTS_PER_REQUEST} anexos.</p>
      : <UploadForm tenantId={tenantId} requestId={requestId} done={(next) => { setOutcome(next); void refresh(); }} /> : null}
  </div>;
}

function result(item: Attachment): { tone: 'success' | 'error' | 'warning'; text: string } {
  if (item.state === 'AVAILABLE') return { tone: 'success', text: `Arquivo anexado: ${item.fileName}.` };
  if (item.state === 'REJECTED') return { tone: 'error', text: `Arquivo recusado: ${rejectionLabels[item.rejection!]}. Envie outro arquivo.` };
  return { tone: 'warning', text: 'O envio ainda não foi confirmado. Use “Conferir envio”.' };
}

function UploadForm({ tenantId, requestId, done }: { tenantId: string; requestId: string; done(outcome: { tone: 'success' | 'info' | 'error' | 'warning'; text: string } | null): void }) {
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [phase, setPhase] = useState<Phase | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const problem = file ? fileProblem(file) : null;
  const id = `attachment-file-${requestId}`;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!file || problem || phase) return;
    setFailure(null);
    try {
      setPhase('intent');
      const upload = await attachments.intent(tenantId, requestId, file);
      setPhase('upload');
      await attachments.put(upload, file);
      setPhase('check');
      const item = await attachments.complete(tenantId, upload.attachment.attachmentId);
      setFile(null); if (input.current) input.current.value = '';
      done(result(item));
    } catch (error) {
      setFailure(error);
      done(null);
    } finally { setPhase(null); }
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label="Anexar arquivo ao pedido">
    <label htmlFor={id}>Anexar foto ou PDF</label>
    <input id={id} ref={input} type="file" accept={attachmentTypes.join(',')} disabled={phase !== null}
      aria-describedby={`${id}-help`} onChange={(event) => { setFailure(null); setFile(event.target.files?.[0] ?? null); }} />
    <span className="field-help" id={`${id}-help`}>Foto JPEG ou PNG até 10 MB, ou PDF até 20 MB. O arquivo só aparece para a gestão depois de conferido.</span>
    {problem ? <Notice tone="error">{problem}</Notice> : null}
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}</Notice> : null}
    <div className="button-row"><button className="button button-compact" type="submit" disabled={!file || problem !== null || phase !== null}>
      {phase ? phaseLabels[phase] : 'Enviar anexo'}</button></div>
  </form>;
}
