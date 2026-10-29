import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { management, type AmenityInput, type ManagedAmenity, type ManagedUnit } from '../../api/management';
import { Empty, ErrorState, Loading, Notice } from '../primitives';
import { money } from '../dateTime';
import { ActionFeedback, handleAccessError, managementKey, useAction, useManagement } from './shared';

const weekDayNames = ['Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb', 'Dom'];

interface AmenityDraft {
  name: string; capacity: string; approvalRequired: boolean; fee: string; buffer: string; active: boolean;
  weekDays: number[]; opensAt: string; closesAt: string; minimumLead: string; maximumAdvance: string;
  maximumDuration: string; maximumActive: string; cancellationLead: string; reason: string;
}
const emptyDraft: AmenityDraft = {
  name: '', capacity: '', approvalRequired: true, fee: '0,00', buffer: '0', active: true, weekDays: [1, 2, 3, 4, 5, 6, 7],
  opensAt: '08:00', closesAt: '22:00', minimumLead: '0', maximumAdvance: '30', maximumDuration: '240', maximumActive: '1',
  cancellationLead: '0', reason: '',
};
function draftOf(area: ManagedAmenity): AmenityDraft {
  const policy = area.policy;
  return { name: area.name, capacity: String(area.capacity), approvalRequired: area.approvalRequired,
    fee: (area.feeCents / 100).toFixed(2).replace('.', ','), buffer: String(area.bufferMinutes), active: area.active,
    weekDays: policy?.weekDays ?? [], opensAt: policy?.opensAt ?? '', closesAt: policy?.closesAt ?? '',
    minimumLead: policy ? String(policy.minimumLeadMinutes) : '', maximumAdvance: policy ? String(policy.maximumAdvanceDays) : '',
    maximumDuration: policy ? String(policy.maximumDurationMinutes) : '', maximumActive: policy ? String(policy.maximumActivePerUnit) : '',
    cancellationLead: policy ? String(policy.cancellationLeadMinutes) : '', reason: '' };
}
/** Converte "1.234,56" em centavos sem arredondamento de ponto flutuante. */
export function cents(value: string): number | null {
  const normalized = value.trim().replace(/\./g, '').replace(',', '.');
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(normalized)) return null;
  const [units = '0', fraction = ''] = normalized.split('.');
  return Number(units) * 100 + Number(fraction.padEnd(2, '0'));
}
const whole = (value: string): number | null => (/^\d{1,9}$/.test(value.trim()) ? Number(value.trim()) : null);

/** Validação local só orienta; o servidor aplica os mesmos limites e decide. */
export function amenityInput(draft: AmenityDraft, expectedVersion: number | null): AmenityInput | string {
  const name = draft.name.trim().replace(/\s+/g, ' ');
  if (!name || name.length > 120) return 'Informe o nome da área com até 120 caracteres.';
  const capacity = whole(draft.capacity); if (capacity === null || capacity < 1 || capacity > 10000) return 'A capacidade deve estar entre 1 e 10000 pessoas.';
  const feeCents = cents(draft.fee); if (feeCents === null || feeCents > 10_000_000) return 'Informe a taxa em reais, até R$ 100.000,00.';
  const bufferMinutes = whole(draft.buffer); if (bufferMinutes === null || bufferMinutes > 1440) return 'A limpeza deve ter entre 0 e 1440 minutos.';
  if (draft.weekDays.length === 0) return 'Selecione ao menos um dia da semana.';
  if (!/^\d{2}:\d{2}$/.test(draft.opensAt) || !/^\d{2}:\d{2}$/.test(draft.closesAt) || draft.opensAt >= draft.closesAt)
    return 'Informe abertura e fechamento no mesmo dia, com o fechamento depois da abertura.';
  const numbers = [whole(draft.minimumLead), whole(draft.maximumAdvance), whole(draft.maximumDuration), whole(draft.maximumActive), whole(draft.cancellationLead)];
  if (numbers.some((value) => value === null)) return 'Preencha antecedência, janela, duração, limite por unidade e prazo de cancelamento com números inteiros.';
  const [minimumLeadMinutes, maximumAdvanceDays, maximumDurationMinutes, maximumActivePerUnit, cancellationLeadMinutes] = numbers as number[];
  const reason = draft.reason.trim();
  return { name, capacity, approvalRequired: draft.approvalRequired, feeCents, bufferMinutes, active: expectedVersion === null ? true : draft.active,
    policy: { schemaVersion: 1, weekDays: [...draft.weekDays].sort((a, b) => a - b), opensAt: draft.opensAt, closesAt: draft.closesAt,
      minimumLeadMinutes: minimumLeadMinutes!, maximumAdvanceDays: maximumAdvanceDays!, maximumDurationMinutes: maximumDurationMinutes!,
      maximumActivePerUnit: maximumActivePerUnit!, cancellationLeadMinutes: cancellationLeadMinutes! },
    expectedVersion, reason: reason ? reason : null };
}

function AmenityForm({ area, onClose }: { area: ManagedAmenity | null; onClose(): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [draft, setDraft] = useState<AmenityDraft>(() => (area ? draftOf(area) : emptyDraft));
  const [validation, setValidation] = useState('');
  const set = <K extends keyof AmenityDraft>(key: K, value: AmenityDraft[K]) => setDraft((current) => ({ ...current, [key]: value }));
  const prefix = area ? `edit-${area.amenityId}` : 'new-area';
  async function submit(event: FormEvent) {
    event.preventDefault();
    const input = amenityInput(draft, area ? area.version : null);
    if (typeof input === 'string') { setValidation(input); return; }
    setValidation('');
    const ok = await action.run(() => (area ? management.updateAmenity(scope.tenantId, area.amenityId, input) : management.createAmenity(scope.tenantId, input)),
      area ? 'Área atualizada.' : 'Área criada.', async () => { await queries.invalidateQueries({ queryKey: managementKey(scope, 'amenities') }); });
    if (ok) onClose();
  }
  const field = (key: keyof AmenityDraft, label: string, help?: string) => <div><label htmlFor={`${prefix}-${key}`}>{label}</label>
    <input id={`${prefix}-${key}`} inputMode="numeric" required value={draft[key] as string} onChange={(event) => set(key, event.target.value as never)} disabled={action.state.pending} />
    {help ? <small className="field-help">{help}</small> : null}</div>;
  return <form className="panel management-form stack-form" onSubmit={(event) => void submit(event)} aria-label={area ? `Editar ${area.name}` : 'Nova área'}>
    <h3>{area ? `Editar ${area.name}` : 'Nova área'}</h3>
    {area ? <Notice>Mudar capacidade, aprovação, taxa, limpeza ou regras cria a versão {area.policyVersion + 1} do regulamento. Reservas existentes mantêm a versão aceita.</Notice> : null}
    <label htmlFor={`${prefix}-name`}>Nome</label>
    <input id={`${prefix}-name`} required maxLength={120} value={draft.name} onChange={(event) => set('name', event.target.value)} disabled={action.state.pending} />
    <div className="two-fields">{field('capacity', 'Capacidade (pessoas)')}{field('buffer', 'Limpeza após o uso (min)')}</div>
    <div className="two-fields"><div><label htmlFor={`${prefix}-fee`}>Taxa informativa (R$)</label>
      <input id={`${prefix}-fee`} inputMode="decimal" required value={draft.fee} onChange={(event) => set('fee', event.target.value)} disabled={action.state.pending} />
      <small className="field-help">Somente informativa: nenhum pagamento é feito pelo sistema.</small></div>
      <div className="checkbox-stack"><label className="checkbox-label"><input type="checkbox" checked={draft.approvalRequired} onChange={(event) => set('approvalRequired', event.target.checked)} disabled={action.state.pending} /><span>Pedidos exigem aprovação da gestão</span></label>
        {area ? <label className="checkbox-label"><input type="checkbox" checked={draft.active} onChange={(event) => set('active', event.target.checked)} disabled={action.state.pending} /><span>Área ativa para novos pedidos</span></label> : null}</div></div>
    <fieldset className="weekday-fieldset" disabled={action.state.pending}><legend>Dias permitidos</legend>
      {weekDayNames.map((label, index) => <label key={label} className="weekday-option"><input type="checkbox" checked={draft.weekDays.includes(index + 1)}
        onChange={(event) => set('weekDays', event.target.checked ? [...draft.weekDays, index + 1] : draft.weekDays.filter((day) => day !== index + 1))} /><span>{label}</span></label>)}
    </fieldset>
    <div className="two-fields"><div><label htmlFor={`${prefix}-opens`}>Abertura</label><input id={`${prefix}-opens`} type="time" required value={draft.opensAt} onChange={(event) => set('opensAt', event.target.value)} disabled={action.state.pending} /></div>
      <div><label htmlFor={`${prefix}-closes`}>Fechamento</label><input id={`${prefix}-closes`} type="time" required value={draft.closesAt} onChange={(event) => set('closesAt', event.target.value)} disabled={action.state.pending} /></div></div>
    <div className="two-fields">{field('minimumLead', 'Antecedência mínima (min)')}{field('maximumAdvance', 'Janela máxima (dias)', 'De 1 a 366 dias.')}</div>
    <div className="two-fields">{field('maximumDuration', 'Duração máxima (min)', 'Até 1440 minutos.')}{field('maximumActive', 'Reservas ativas por unidade', 'Conta pedidos pendentes e confirmadas em todas as áreas.')}</div>
    <div className="two-fields">{field('cancellationLead', 'Prazo de cancelamento (min antes do início)')}<div /></div>
    {area ? <><label htmlFor={`${prefix}-reason`}>Motivo da alteração (opcional)</label>
      <textarea id={`${prefix}-reason`} maxLength={500} rows={2} value={draft.reason} onChange={(event) => set('reason', event.target.value)} disabled={action.state.pending} /></> : null}
    {validation ? <Notice tone="error">{validation}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={action.state.pending}>{action.state.pending ? 'Salvando…' : area ? 'Salvar alterações' : 'Criar área'}</button>
      <button className="button button-secondary" type="button" onClick={onClose} disabled={action.state.pending}>Cancelar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}

export function AmenitySection() {
  const scope = useManagement();
  const areas = useQuery({ queryKey: managementKey(scope, 'amenities'), queryFn: ({ signal }) => management.amenities(scope.tenantId, signal) });
  const [editing, setEditing] = useState<string | null>(null);
  if (areas.isError && handleAccessError(areas.error, scope)) return null;
  return <section className="management-section" aria-labelledby="areas-title">
    <div className="section-heading"><div><span className="section-index">ÁREAS E REGRAS</span><h2 id="areas-title">Áreas do condomínio</h2></div>
      <button className="button button-secondary button-compact" type="button" onClick={() => setEditing('new')} disabled={editing !== null}>Nova área</button></div>
    {editing === 'new' ? <AmenityForm area={null} onClose={() => setEditing(null)} /> : null}
    {areas.isPending ? <div className="panel"><Loading label="Consultando áreas…" /></div> : areas.isError ? <ErrorState error={areas.error} retry={() => void areas.refetch()} />
      : areas.data.length === 0 ? <div className="panel"><Empty title="Nenhuma área cadastrada">Cadastre a primeira área com regras explícitas.</Empty></div>
        : <div className="management-grid">{areas.data.map((area) => editing === area.amenityId
          ? <AmenityForm key={area.amenityId} area={area} onClose={() => setEditing(null)} />
          : <article key={area.amenityId} className="amenity-card">
            <div className="amenity-top"><span className={`status-pill ${area.active ? 'status-ready' : 'status-neutral'}`}>{area.active ? 'Ativa' : 'Inativa'}</span>
              <span className={`status-pill ${area.approvalRequired ? 'status-pending' : 'status-neutral'}`}>{area.approvalRequired ? 'Com aprovação' : 'Sem aprovação'}</span></div>
            <h3>{area.name}</h3>
            <p className="amenity-capacity">Capacidade: {area.capacity} pessoas · regulamento v{area.policyVersion}</p>
            <div className="amenity-details"><span>{area.policy ? `${area.policy.opensAt} às ${area.policy.closesAt}` : 'Regras pendentes'}</span><span>{money(area.feeCents)}</span></div>
            <p className="amenity-note">{area.policy ? `Limpeza ${area.bufferMinutes} min · até ${area.policy.maximumDurationMinutes} min por uso · ${area.policy.maximumActivePerUnit} ativa(s) por unidade` : 'Configure as regras para liberar pedidos.'}</p>
            <button className="button button-secondary button-wide" type="button" disabled={editing !== null} onClick={() => setEditing(area.amenityId)} aria-label={`Editar ${area.name}`}>Editar</button>
          </article>)}</div>}
  </section>;
}

function UnitEditor({ unit, blocks, onClose }: { unit: ManagedUnit; blocks: { blockId: string; name: string }[]; onClose(): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [label, setLabel] = useState(unit.label);
  const [blockId, setBlockId] = useState(unit.blockId ?? '');
  const [status, setStatus] = useState(unit.status);
  const [reason, setReason] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    const ok = await action.run(() => management.updateUnit(scope.tenantId, unit.unitId, { label: label.trim(), blockId: blockId || null,
      status, expectedVersion: unit.version, reason: reason.trim() ? reason.trim() : null }), 'Unidade atualizada.',
    async () => { await queries.invalidateQueries({ queryKey: managementKey(scope, 'units') }); });
    if (ok) onClose();
  }
  return <form className="reason-form unit-editor" onSubmit={(event) => void submit(event)} aria-label={`Editar unidade ${unit.label}`}>
    <div className="two-fields"><div><label htmlFor={`unit-label-${unit.unitId}`}>Identificação</label>
      <input id={`unit-label-${unit.unitId}`} required maxLength={40} value={label} onChange={(event) => setLabel(event.target.value)} disabled={action.state.pending} /></div>
      <div><label htmlFor={`unit-block-${unit.unitId}`}>Bloco</label><select id={`unit-block-${unit.unitId}`} value={blockId} onChange={(event) => setBlockId(event.target.value)} disabled={action.state.pending}>
        <option value="">Sem bloco</option>{blocks.map((block) => <option key={block.blockId} value={block.blockId}>{block.name}</option>)}</select></div></div>
    <label htmlFor={`unit-status-${unit.unitId}`}>Situação</label>
    <select id={`unit-status-${unit.unitId}`} value={status} onChange={(event) => setStatus(event.target.value as ManagedUnit['status'])} disabled={action.state.pending}>
      <option value="ACTIVE">Ativa</option><option value="INACTIVE">Inativa</option></select>
    {status === 'INACTIVE' && unit.status === 'ACTIVE' ? <Notice tone="warning">Inativar bloqueia na hora o acesso dos moradores desta unidade.</Notice> : null}
    <label htmlFor={`unit-reason-${unit.unitId}`}>Motivo (opcional)</label>
    <textarea id={`unit-reason-${unit.unitId}`} maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={action.state.pending} />
    <div className="button-row"><button className="button" type="submit" disabled={action.state.pending || !label.trim()}>{action.state.pending ? 'Salvando…' : 'Salvar unidade'}</button>
      <button className="button button-secondary" type="button" onClick={onClose} disabled={action.state.pending}>Cancelar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}

export function UnitSection() {
  const scope = useManagement();
  const queries = useQueryClient();
  const blocks = useQuery({ queryKey: managementKey(scope, 'blocks'), queryFn: ({ signal }) => management.blocks(scope.tenantId, signal) });
  const units = useQuery({ queryKey: managementKey(scope, 'units'), queryFn: ({ signal }) => management.units(scope.tenantId, signal) });
  const blockAction = useAction();
  const unitAction = useAction();
  const [blockName, setBlockName] = useState('');
  const [unitLabel, setUnitLabel] = useState('');
  const [unitBlock, setUnitBlock] = useState('');
  const [editing, setEditing] = useState<string | null>(null);
  const failure = [blocks.error, units.error].find(Boolean);
  if (failure && handleAccessError(failure, scope)) return null;
  async function createBlock(event: FormEvent) {
    event.preventDefault();
    const ok = await blockAction.run(() => management.createBlock(scope.tenantId, blockName.trim()), 'Bloco criado.',
      async () => { await queries.invalidateQueries({ queryKey: managementKey(scope, 'blocks') }); });
    if (ok) setBlockName('');
  }
  async function createUnit(event: FormEvent) {
    event.preventDefault();
    const ok = await unitAction.run(() => management.createUnit(scope.tenantId, { label: unitLabel.trim(), blockId: unitBlock || null }), 'Unidade criada.',
      async () => { await queries.invalidateQueries({ queryKey: managementKey(scope, 'units') }); });
    if (ok) setUnitLabel('');
  }
  return <section className="management-section" aria-labelledby="units-title">
    <div className="section-heading"><div><span className="section-index">CADASTRO</span><h2 id="units-title">Blocos e unidades</h2></div></div>
    <div className="management-columns">
      <form className="panel management-form stack-form" onSubmit={(event) => void createBlock(event)} aria-label="Novo bloco">
        <h3>Novo bloco</h3>
        <label htmlFor="block-name">Nome do bloco</label>
        <input id="block-name" required maxLength={60} value={blockName} onChange={(event) => setBlockName(event.target.value)} disabled={blockAction.state.pending} />
        <button className="button" type="submit" disabled={blockAction.state.pending || !blockName.trim()}>Criar bloco</button>
        <ActionFeedback state={blockAction.state} />
      </form>
      <form className="panel management-form stack-form" onSubmit={(event) => void createUnit(event)} aria-label="Nova unidade">
        <h3>Nova unidade</h3>
        <label htmlFor="unit-label">Identificação</label>
        <input id="unit-label" required maxLength={40} value={unitLabel} onChange={(event) => setUnitLabel(event.target.value)} disabled={unitAction.state.pending} />
        <label htmlFor="unit-block">Bloco</label>
        <select id="unit-block" value={unitBlock} onChange={(event) => setUnitBlock(event.target.value)} disabled={unitAction.state.pending}>
          <option value="">Sem bloco</option>{(blocks.data ?? []).map((block) => <option key={block.blockId} value={block.blockId}>{block.name}</option>)}</select>
        <button className="button" type="submit" disabled={unitAction.state.pending || !unitLabel.trim()}>Criar unidade</button>
        <ActionFeedback state={unitAction.state} />
      </form>
    </div>
    {units.isPending ? <div className="panel"><Loading label="Consultando unidades…" /></div> : units.isError ? <ErrorState error={units.error} retry={() => void units.refetch()} />
      : units.data.units.length === 0 ? <div className="panel"><Empty title="Nenhuma unidade cadastrada">Crie as unidades antes de convidar moradores.</Empty></div>
        : <ul className="management-list" aria-label="Unidades">{units.data.units.map((unit) => <li key={unit.unitId} className="management-row">
          <div><strong>{unit.blockName ? `${unit.blockName} · ` : ''}{unit.label}</strong><span className={`status-pill ${unit.status === 'ACTIVE' ? 'status-ready' : 'status-neutral'}`}>{unit.status === 'ACTIVE' ? 'Ativa' : 'Inativa'}</span></div>
          {editing === unit.unitId ? <UnitEditor unit={unit} blocks={blocks.data ?? []} onClose={() => setEditing(null)} />
            : <button className="text-button" type="button" disabled={editing !== null} onClick={() => setEditing(unit.unitId)} aria-label={`Editar unidade ${unit.label}`}>Editar</button>}
        </li>)}</ul>}
    {units.data?.truncated ? <Notice tone="warning">A lista mostra as primeiras unidades. Use a busca do cadastro quando disponível.</Notice> : null}
  </section>;
}
