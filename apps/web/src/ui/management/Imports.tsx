import { useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { IMPORT_MAX_BYTES, imports, type ImportBatch, type ImportDetail, type ImportRow } from '../../api/imports';
import { Empty, ErrorState, Loading, Notice } from '../primitives';
import { formatWhen } from '../dateTime';
import { ActionFeedback, managementKey, useAccessGuard, useAction, useManagement } from './shared';

const TEMPLATE = 'bloco;unidade;codigo_externo\nBloco A;101;\nBloco A;102;\n;Casa 1;\n';
const stateLabels: Record<ImportBatch['state'], string> = { PREVIEW: 'Em prévia', APPROVED: 'Aprovado', DISCARDED: 'Descartado' };
const rowErrors: Record<string, string> = {
  UNIT_LABEL_REQUIRED: 'Unidade em branco', UNIT_LABEL_TOO_LONG: 'Unidade com mais de 40 caracteres',
  BLOCK_NAME_TOO_LONG: 'Bloco com mais de 60 caracteres', EXTERNAL_ID_TOO_LONG: 'Código externo com mais de 80 caracteres',
  IMPORT_ROW_COLUMNS_INVALID: 'Quantidade de colunas diferente do cabeçalho', IMPORT_ROW_QUOTES_INVALID: 'Aspas sem fechamento',
  IMPORT_ROW_CONTROL_CHARACTER: 'Caractere de controle no texto',
};

/**
 * Importação de blocos e unidades (CP-016): o arquivo vira uma prévia com resultado por linha; nada é criado até a
 * aprovação. O mesmo arquivo reabre o mesmo lote. Pessoas e vínculos não são importados: seguem por convite.
 */
export function ImportSection() {
  const scope = useManagement();
  const queries = useQueryClient();
  const batches = useQuery({ queryKey: managementKey(scope, 'imports'), queryFn: ({ signal }) => imports.batches(scope.tenantId, signal) });
  const [selected, setSelected] = useState<string | null>(null);
  const [reopened, setReopened] = useState(false);
  const upload = useAction();
  const [file, setFile] = useState<File | null>(null);
  const [fileProblem, setFileProblem] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);
  if (useAccessGuard(batches.error)) return null;

  function choose(event: ChangeEvent<HTMLInputElement>) {
    const chosen = event.target.files?.[0] ?? null;
    upload.clear(); setFileProblem(null);
    if (chosen && chosen.size > IMPORT_MAX_BYTES) { setFile(null); setFileProblem('O arquivo passa de 256 KB. Divida em lotes menores.'); return; }
    setFile(chosen);
  }
  async function preview(event: FormEvent) {
    event.preventDefault();
    if (!file) return;
    let content: string;
    try { content = await file.text(); }
    catch { setFileProblem('Não foi possível ler o arquivo neste navegador.'); return; }
    await upload.run(() => imports.open(scope.tenantId, { fileName: file.name, content }), (result) => ({
      text: result.created ? 'Prévia criada. Nada foi cadastrado ainda: confira as linhas e decida.'
        : 'Este arquivo já tinha um lote. Mostrando o lote existente; nenhuma linha nova foi gravada.',
      tone: result.created ? 'success' : 'info' }), async (result) => {
      queries.setQueryData(managementKey(scope, 'imports', result.detail.batch.batchId), result.detail);
      setSelected(result.detail.batch.batchId); setReopened(!result.created);
      setFile(null); if (input.current) input.current.value = '';
      await queries.invalidateQueries({ queryKey: managementKey(scope, 'imports'), exact: true });
    });
  }
  function download() {
    const url = URL.createObjectURL(new Blob([TEMPLATE], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url; link.download = 'modelo-unidades.csv';
    document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }

  return <section className="management-section" aria-labelledby="imports-title">
    <div className="section-heading"><div><span className="section-index">CADASTRO</span><h2 id="imports-title">Importação de unidades</h2></div></div>
    <div className="management-columns">
      <form className="panel management-form stack-form" onSubmit={(event) => void preview(event)} aria-label="Nova importação">
        <h3>Enviar planilha</h3>
        <p className="muted">CSV com cabeçalho <code>bloco;unidade;codigo_externo</code> (só <code>unidade</code> é obrigatória), até 2.000 linhas.
          Moradores e vínculos não são importados: continuam por convite revisado.</p>
        <label htmlFor="import-file">Arquivo CSV</label>
        <input id="import-file" ref={input} type="file" accept=".csv,text/csv" onChange={choose} disabled={upload.state.pending} />
        {fileProblem ? <Notice tone="error">{fileProblem}</Notice> : null}
        <div className="button-row">
          <button className="button" type="submit" disabled={!file || upload.state.pending}>{upload.state.pending ? 'Gerando prévia…' : 'Gerar prévia'}</button>
          <button className="button button-secondary" type="button" onClick={download}>Baixar modelo</button>
        </div>
        <ActionFeedback state={upload.state} />
      </form>
      <div className="panel">
        <h3>Lotes recentes</h3>
        {batches.isPending ? <Loading label="Consultando lotes…" /> : batches.isError ? <ErrorState error={batches.error} retry={() => void batches.refetch()} />
          : batches.data.length === 0 ? <Empty title="Nenhuma importação">Envie uma planilha para ver a prévia aqui.</Empty>
            : <ul className="management-list" aria-label="Lotes de importação">{batches.data.map((batch) => <li key={batch.batchId} className="management-row">
              <div><strong>{batch.source}</strong><span className="muted">{formatWhen(batch.createdAt, scope.timeZone)} · {batch.totalRows} linhas</span>
                <span className={`status-pill ${batch.state === 'APPROVED' ? 'status-ready' : batch.state === 'PREVIEW' ? 'status-pending' : 'status-neutral'}`}>{stateLabels[batch.state]}</span></div>
              <button className="text-button" type="button" aria-label={`Abrir lote ${batch.source}`} aria-pressed={selected === batch.batchId}
                onClick={() => { setSelected(batch.batchId); setReopened(false); upload.clear(); }}>Abrir</button>
            </li>)}</ul>}
      </div>
    </div>
    {selected ? <BatchView key={selected} batchId={selected} reopened={reopened} close={() => setSelected(null)} /> : null}
  </section>;
}

function BatchView({ batchId, reopened, close }: { batchId: string; reopened: boolean; close(): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const detail = useQuery({ queryKey: managementKey(scope, 'imports', batchId), queryFn: ({ signal }) => imports.detail(scope.tenantId, batchId, signal) });
  const action = useAction();
  const [acceptPartial, setAcceptPartial] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  if (useAccessGuard(detail.error)) return null;
  if (detail.isPending) return <div className="panel"><Loading label="Abrindo o lote…" /></div>;
  if (detail.isError && !detail.data) return <ErrorState error={detail.error} retry={() => void detail.refetch()} />;
  if (!detail.data) return null;
  const { batch, rows } = detail.data;
  const partial = batch.invalidRows + batch.duplicateRows > 0;
  const toCreate = rows.filter((row) => row.outcome === 'VALID' && !row.alreadyExists).length;

  async function refreshAfter(result: ImportDetail) {
    queries.setQueryData(managementKey(scope, 'imports', batchId), result);
    await Promise.all([queries.invalidateQueries({ queryKey: managementKey(scope, 'imports'), exact: true }),
      queries.invalidateQueries({ queryKey: managementKey(scope, 'units') }),
      queries.invalidateQueries({ queryKey: managementKey(scope, 'blocks') })]);
  }
  async function approve() {
    await action.run(() => imports.approve(scope.tenantId, batchId, { expectedVersion: batch.version, acceptPartial }), (result) => {
      const b = result.detail.batch;
      if (result.applied === false) return { text: 'Este lote já estava aprovado; nada novo foi criado.', tone: 'info' };
      return { text: `Lote aprovado: ${b.createdUnits ?? 0} unidade(s) criada(s), ${b.existingUnits ?? 0} já existia(m)` +
        `${b.createdBlocks ? `, ${b.createdBlocks} bloco(s) criado(s)` : ''}.`, tone: 'success' };
    }, (result) => refreshAfter(result.detail));
  }
  async function discard() {
    const ok = await action.run(() => imports.discard(scope.tenantId, batchId, { expectedVersion: batch.version }), (result) => ({
      text: result.applied === false ? 'Este lote já estava descartado.' : 'Lote descartado. Nada foi cadastrado.', tone: 'info' }),
    (result) => refreshAfter(result.detail));
    if (ok) setConfirmDiscard(false);
  }

  return <section className="panel import-batch" aria-labelledby="import-batch-title">
    <div className="section-heading"><div><span className="section-index">LOTE · {stateLabels[batch.state].toUpperCase()}</span>
      <h3 id="import-batch-title">{batch.source}</h3></div>
      <button className="button button-secondary button-compact" type="button" onClick={close}>Fechar</button></div>
    {reopened ? <Notice>Este arquivo já tinha sido enviado. O lote abaixo é o mesmo; nenhuma linha nova foi gravada.</Notice> : null}
    {detail.isError ? <Notice tone="warning">Não foi possível atualizar o lote; o que aparece pode estar desatualizado.</Notice> : null}
    <dl className="import-summary">
      <div><dt>Linhas</dt><dd>{batch.totalRows}</dd></div>
      <div><dt>Válidas</dt><dd>{batch.validRows}</dd></div>
      <div><dt>Com erro</dt><dd>{batch.invalidRows}</dd></div>
      <div><dt>Repetidas</dt><dd>{batch.duplicateRows}</dd></div>
      {batch.state === 'APPROVED' ? <><div><dt>Criadas</dt><dd>{batch.createdUnits}</dd></div><div><dt>Já existiam</dt><dd>{batch.existingUnits}</dd></div></>
        : batch.state === 'PREVIEW' ? <div><dt>Serão criadas</dt><dd>{toCreate}</dd></div> : null}
    </dl>
    <p className="muted">Arquivo SHA-256 <code className="hash">{batch.fileHash.slice(0, 16)}…</code> · enviado em {formatWhen(batch.createdAt, scope.timeZone)}
      {batch.decidedAt ? ` · decidido em ${formatWhen(batch.decidedAt, scope.timeZone)}` : ''}{batch.partial ? ' · aprovado como parcial' : ''}</p>
    <ol className="import-rows" aria-label="Linhas do lote">{rows.map((row) => <li key={row.line} className={`import-row import-${row.outcome.toLowerCase()}`}>
      <span className="import-line">Linha {row.line}</span>
      <span className="import-target">{row.block ? `${row.block} · ` : ''}{row.unit ?? '—'}{row.externalId ? <span className="muted"> ({row.externalId})</span> : null}</span>
      <span className="import-outcome">{outcome(row)}</span>
    </li>)}</ol>
    {batch.state === 'PREVIEW' ? <div className="import-actions">
      {partial ? <label className="checkbox-label"><input type="checkbox" checked={acceptPartial} onChange={(event) => setAcceptPartial(event.target.checked)} disabled={action.state.pending} />
        <span>Aprovar só as {batch.validRows} linha(s) válida(s); as {batch.invalidRows + batch.duplicateRows} com erro ou repetidas ficam de fora.</span></label> : null}
      {batch.validRows === 0 ? <Notice tone="warning">Nenhuma linha válida. Corrija o arquivo e envie de novo.</Notice> : null}
      {confirmDiscard ? <div className="button-row" role="group" aria-label="Confirmar descarte">
        <span>Descartar este lote? Nada será cadastrado e o histórico continua visível.</span>
        <button className="button button-danger" type="button" onClick={() => void discard()} disabled={action.state.pending}>Confirmar descarte</button>
        <button className="button button-secondary" type="button" onClick={() => setConfirmDiscard(false)} disabled={action.state.pending}>Voltar</button>
      </div> : <div className="button-row">
        <button className="button" type="button" onClick={() => void approve()}
          disabled={action.state.pending || batch.validRows === 0 || (partial && !acceptPartial)}>{action.state.pending ? 'Aprovando…' : 'Aprovar lote'}</button>
        <button className="button button-secondary" type="button" onClick={() => { action.clear(); setConfirmDiscard(true); }} disabled={action.state.pending}>Descartar lote</button>
      </div>}
    </div> : null}
    <ActionFeedback state={action.state} />
  </section>;
}

function outcome(row: ImportRow): string {
  if (row.outcome === 'INVALID') return `Erro: ${rowErrors[row.errorCode ?? ''] ?? 'linha recusada'}`;
  if (row.outcome === 'DUPLICATE') return 'Repetida no arquivo (fica de fora)';
  if (row.applied === 'CREATED') return 'Criada';
  if (row.applied === 'EXISTING') return 'Já existia';
  return row.alreadyExists ? 'Já existe (não será criada)' : 'Nova unidade';
}
