import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, oneOf, record } from './client';
import type { ISODateTime, UUID } from './types';

/**
 * Catálogo de modelos e homologações do acesso facial na central (V040, AC-002). Só o que a API devolve: perfis
 * documentais com fontes oficiais, dossiês por combinação exata e a trilha de decisões do MASTER. Não há senha, token,
 * chave ou segredo de equipamento nestes contratos; a tela nunca pede nem mostra um.
 */
export const manufacturers = ['CONTROL_ID', 'HIKVISION', 'DAHUA', 'INTELBRAS'] as const;
export type Manufacturer = typeof manufacturers[number];
export const manufacturerLabels: Record<Manufacturer, string> = { CONTROL_ID: 'Control iD', HIKVISION: 'Hikvision', DAHUA: 'Dahua', INTELBRAS: 'Intelbras' };
export const modelCategories = ['FACE_TERMINAL', 'ACCESS_CONTROLLER', 'READER', 'EXPANSION_MODULE'] as const;
export const categoryLabels: Record<typeof modelCategories[number], string> = { FACE_TERMINAL: 'Terminal facial', ACCESS_CONTROLLER: 'Controladora',
  READER: 'Leitor', EXPANSION_MODULE: 'Módulo de expansão' };
export const lifecycles = ['DOCUMENTED_CANDIDATE', 'CURRENT_DECLARED', 'DISCONTINUED_DECLARED', 'SUPPORT_UNKNOWN'] as const;
export const lifecycleLabels: Record<typeof lifecycles[number], string> = { DOCUMENTED_CANDIDATE: 'Candidato documentado', CURRENT_DECLARED: 'Vigente (declarado)',
  DISCONTINUED_DECLARED: 'Descontinuado (declarado)', SUPPORT_UNKNOWN: 'Suporte desconhecido' };
export const profileStatuses = ['CANDIDATE', 'ACTIVE', 'RETIRED'] as const;
export const profileStatusLabels: Record<typeof profileStatuses[number], string> = { CANDIDATE: 'Candidato', ACTIVE: 'Ativo', RETIRED: 'Retirado' };

export const capabilityNames = ['doorContactEvents', 'eventBackfill', 'faceDeletion', 'faceEnrollment', 'localValidity', 'offlineRestriction',
  'onlineDecisionBeforeRelay', 'passageEvents', 'perDoorRights', 'safeRecovery', 'scopedRemoteOpen', 'secureTransport'] as const;
export type CapabilityName = typeof capabilityNames[number];
export const capabilityLabels: Record<CapabilityName, string> = {
  doorContactEvents: 'Eventos de contato da porta', eventBackfill: 'Recuperação de eventos', faceDeletion: 'Exclusão de face', faceEnrollment: 'Cadastro de face',
  localValidity: 'Validade local', offlineRestriction: 'Restrição sem conexão', onlineDecisionBeforeRelay: 'Decisão online antes do relé',
  passageEvents: 'Eventos de passagem', perDoorRights: 'Direitos por porta', safeRecovery: 'Recuperação segura', scopedRemoteOpen: 'Abertura remota com escopo',
  secureTransport: 'Transporte seguro',
};
export const limitNames = ['cards', 'concurrentSessions', 'eventsStored', 'faces', 'groups', 'imagesPerPerson', 'maximumImageBytes', 'plans', 'timeWindows', 'users'] as const;
export type LimitName = typeof limitNames[number];
export const limitLabels: Record<LimitName, string> = { cards: 'Cartões', concurrentSessions: 'Sessões simultâneas', eventsStored: 'Eventos armazenados', faces: 'Faces',
  groups: 'Grupos', imagesPerPerson: 'Imagens por pessoa', maximumImageBytes: 'Tamanho máximo da imagem (bytes)', plans: 'Planos', timeWindows: 'Janelas de horário', users: 'Usuários' };
export const operatingModes = ['DEVICE_LOCAL_ACL', 'EDGE_ONLINE', 'PROVIDER_LOCAL', 'ASSISTED'] as const;
export type OperatingMode = typeof operatingModes[number];
export const operatingModeLabels: Record<OperatingMode, string> = { DEVICE_LOCAL_ACL: 'Lista local no equipamento', EDGE_ONLINE: 'Decisão online no gateway',
  PROVIDER_LOCAL: 'Serviço local do fabricante', ASSISTED: 'Assistido' };
export const topologies = ['LOCAL_GATEWAY', 'CLOUD_SERVICE'] as const;
export type Topology = typeof topologies[number];
export const topologyLabels: Record<Topology, string> = { LOCAL_GATEWAY: 'Gateway local', CLOUD_SERVICE: 'Serviço em nuvem' };
export const homologationStatuses = ['CANDIDATO_DOCUMENTADO', 'EM_ENSAIO', 'APROVADO_BANCADA', 'APROVADO_PILOTO', 'HOMOLOGADO', 'REPROVADO', 'SUSPENSO', 'EXPIRADO'] as const;
export type HomologationStatus = typeof homologationStatuses[number];
export const homologationStatusLabels: Record<HomologationStatus, string> = { CANDIDATO_DOCUMENTADO: 'Candidato documentado', EM_ENSAIO: 'Em ensaio',
  APROVADO_BANCADA: 'Aprovado em bancada', APROVADO_PILOTO: 'Aprovado em piloto', HOMOLOGADO: 'Homologado', REPROVADO: 'Reprovado', SUSPENSO: 'Suspenso', EXPIRADO: 'Expirado' };
export const decisions = ['START_TRIAL', 'APPROVE_LAB', 'APPROVE_PILOT', 'APPROVE_PRODUCTION', 'REJECT', 'SUSPEND'] as const;
export type Decision = typeof decisions[number];
export const decisionLabels: Record<Decision, string> = { START_TRIAL: 'Iniciar ensaio', APPROVE_LAB: 'Aprovar em bancada', APPROVE_PILOT: 'Aprovar em piloto',
  APPROVE_PRODUCTION: 'Homologar para produção', REJECT: 'Reprovar', SUSPEND: 'Suspender' };

/** Mesma tabela de transições do banco (platform.homologation_next, V040); o servidor confere de novo. */
const transitions: Record<Decision, readonly HomologationStatus[]> = {
  START_TRIAL: ['CANDIDATO_DOCUMENTADO'], APPROVE_LAB: ['EM_ENSAIO'], APPROVE_PILOT: ['APROVADO_BANCADA'], APPROVE_PRODUCTION: ['APROVADO_PILOTO'],
  REJECT: ['CANDIDATO_DOCUMENTADO', 'EM_ENSAIO', 'APROVADO_BANCADA', 'APROVADO_PILOTO', 'SUSPENSO'], SUSPEND: ['APROVADO_BANCADA', 'APROVADO_PILOTO', 'HOMOLOGADO'],
};
export function allowedDecisions(status: HomologationStatus): Decision[] { return decisions.filter((item) => transitions[item].includes(status)); }
export const isApproval = (decision: Decision) => decision.startsWith('APPROVE_');

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
export interface ModelSource { id: string | null; title: string | null; url: string | null; consultedOn: string | null }
export interface AccessModelProfile {
  id: UUID; catalogCode: string; manufacturer: Manufacturer; sku: string; hardwareRevision: string; region: string; category: typeof modelCategories[number];
  sourceCategory: string; lifecycle: typeof lifecycles[number]; status: typeof profileStatuses[number]; revision: number; sources: ModelSource[];
  declaredCapabilities: Record<string, JsonValue>; declaredLimits: Record<string, JsonValue>; notes: string; consultedOn: string; documentHash: string;
}
export interface CapabilityObservation { documented: boolean | null; observed: boolean | null }
export interface AccessHomologation {
  id: UUID; revision: number; modelProfileId: UUID; firmwareVersion: string; adapterName: string; adapterVersion: string; apiContractVersion: string;
  operatingMode: OperatingMode; topology: Topology; capabilities: Partial<Record<CapabilityName, CapabilityObservation>>; limits: Partial<Record<LimitName, number | null>>;
  limitations: string[]; evidenceRefs: UUID[]; evidenceHash: string; status: HomologationStatus; approvedCapabilities: CapabilityName[]; restrictions: string[];
  approvedAt: ISODateTime | null; reviewDueAt: ISODateTime | null; current: boolean; version: number;
}
export interface HomologationDecisionRecord {
  decisionId: UUID; decision: string; fromStatus: HomologationStatus; toStatus: HomologationStatus; reason: string; evidenceRefs: UUID[];
  approvedCapabilities: string[]; decidedBy: UUID; decidedAt: ISODateTime;
}
export interface HomologationDetail { homologation: AccessHomologation; decisions: HomologationDecisionRecord[] }
export interface HomologationCreated { homologation: AccessHomologation; applied: boolean }
export interface CreateHomologationBody {
  homologationId: UUID; modelProfileId: UUID; firmwareVersion: string; adapterName: string; adapterVersion: string; apiContractVersion: string;
  operatingMode: OperatingMode; topology: Topology; capabilities: Record<CapabilityName, CapabilityObservation>; limits: Record<LimitName, number | null>;
  evidenceRefs: UUID[]; limitations: string[]; reason: string;
}
export interface DecideBody {
  decisionId: UUID; decision: Decision; reason: string; evidenceRefs: UUID[]; approvedCapabilityNames: CapabilityName[]; reviewDueAt: ISODateTime | null;
  restrictions: string[]; expectedVersion: number;
}

const HASH = /^[0-9a-f]{64}$/;
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(isString);
const uuids = (value: unknown): value is string[] => Array.isArray(value) && value.every(isUUID);
const nullableBool = (value: unknown): value is boolean | null => value === null || typeof value === 'boolean';
const nullableDate = (value: unknown): value is string | null => value === null || isDate(value);
function json(value: unknown, depth = 0): value is JsonValue {
  if (depth > 8) return false;
  if (value === null || isString(value) || typeof value === 'boolean' || (typeof value === 'number' && Number.isFinite(value))) return true;
  if (Array.isArray(value)) return value.every((item) => json(item, depth + 1));
  return record(value) && Object.values(value).every((item) => json(item, depth + 1));
}
function source(value: unknown): ModelSource {
  if (!record(value)) throw invalidResponse();
  const text = (field: unknown) => (isString(field) ? field : null);
  return { id: text(value.id), title: text(value.title), url: text(value.url), consultedOn: text(value.consultedOn) };
}

export function accessModelProfile(value: unknown): AccessModelProfile {
  if (!record(value) || !isUUID(value.id) || !isString(value.catalogCode) || !oneOf(value.manufacturer, manufacturers) || !isString(value.sku)
      || !isString(value.hardwareRevision) || !isString(value.region) || !oneOf(value.category, modelCategories) || !isString(value.sourceCategory)
      || !oneOf(value.lifecycle, lifecycles) || !oneOf(value.status, profileStatuses) || !isInteger(value.revision) || value.revision < 1
      || !Array.isArray(value.sources) || !record(value.declaredCapabilities) || !json(value.declaredCapabilities) || !record(value.declaredLimits)
      || !json(value.declaredLimits) || !isString(value.notes) || !isDate(value.consultedOn) || !isString(value.documentHash) || !HASH.test(value.documentHash))
    throw invalidResponse();
  return { id: value.id, catalogCode: value.catalogCode, manufacturer: value.manufacturer, sku: value.sku, hardwareRevision: value.hardwareRevision,
    region: value.region, category: value.category, sourceCategory: value.sourceCategory, lifecycle: value.lifecycle, status: value.status,
    revision: value.revision, sources: value.sources.map(source), declaredCapabilities: value.declaredCapabilities as Record<string, JsonValue>,
    declaredLimits: value.declaredLimits as Record<string, JsonValue>, notes: value.notes, consultedOn: value.consultedOn, documentHash: value.documentHash };
}

function observation(value: unknown): CapabilityObservation {
  if (!record(value) || !nullableBool(value.documented) || !nullableBool(value.observed)) throw invalidResponse();
  return { documented: value.documented, observed: value.observed };
}
export function accessHomologation(value: unknown, uncertain = false): AccessHomologation {
  if (!record(value) || !isUUID(value.id) || !isInteger(value.revision) || value.revision < 1 || !isUUID(value.modelProfileId) || !isString(value.firmwareVersion)
      || !isString(value.adapterName) || !isString(value.adapterVersion) || !isString(value.apiContractVersion) || !oneOf(value.operatingMode, operatingModes)
      || !oneOf(value.topology, topologies) || !record(value.capabilities) || !record(value.limits) || !strings(value.limitations) || !uuids(value.evidenceRefs)
      || !isString(value.evidenceHash) || !HASH.test(value.evidenceHash) || !oneOf(value.status, homologationStatuses) || !Array.isArray(value.approvedCapabilities)
      || !value.approvedCapabilities.every((item) => oneOf(item, capabilityNames)) || !strings(value.restrictions) || !nullableDate(value.approvedAt)
      || !nullableDate(value.reviewDueAt) || typeof value.current !== 'boolean' || !isInteger(value.version)) throw invalidResponse(uncertain);
  const capabilities: Partial<Record<CapabilityName, CapabilityObservation>> = {};
  for (const [name, item] of Object.entries(value.capabilities)) {
    if (!oneOf(name, capabilityNames)) throw invalidResponse(uncertain);
    capabilities[name] = observation(item);
  }
  const limits: Partial<Record<LimitName, number | null>> = {};
  for (const [name, item] of Object.entries(value.limits)) {
    if (!oneOf(name, limitNames) || !(item === null || isInteger(item))) throw invalidResponse(uncertain);
    limits[name] = item;
  }
  // Vigente só aprovada (bancada, piloto ou produção); capacidade aprovada sempre observada no dossiê.
  if (value.current && !['APROVADO_BANCADA', 'APROVADO_PILOTO', 'HOMOLOGADO'].includes(value.status)) throw invalidResponse(uncertain);
  if ((value.approvedCapabilities as CapabilityName[]).some((name) => capabilities[name]?.observed !== true)) throw invalidResponse(uncertain);
  return { id: value.id, revision: value.revision, modelProfileId: value.modelProfileId, firmwareVersion: value.firmwareVersion, adapterName: value.adapterName,
    adapterVersion: value.adapterVersion, apiContractVersion: value.apiContractVersion, operatingMode: value.operatingMode, topology: value.topology,
    capabilities, limits, limitations: [...value.limitations], evidenceRefs: [...value.evidenceRefs], evidenceHash: value.evidenceHash, status: value.status,
    approvedCapabilities: [...value.approvedCapabilities] as CapabilityName[], restrictions: [...value.restrictions], approvedAt: value.approvedAt as string | null,
    reviewDueAt: value.reviewDueAt as string | null, current: value.current, version: value.version };
}
function decisionRecord(value: unknown, uncertain: boolean): HomologationDecisionRecord {
  if (!record(value) || !isUUID(value.decisionId) || !isString(value.decision) || !oneOf(value.fromStatus, homologationStatuses)
      || !oneOf(value.toStatus, homologationStatuses) || !isString(value.reason) || !uuids(value.evidenceRefs) || !strings(value.approvedCapabilities)
      || !isUUID(value.decidedBy) || !isDate(value.decidedAt)) throw invalidResponse(uncertain);
  return { decisionId: value.decisionId, decision: value.decision, fromStatus: value.fromStatus, toStatus: value.toStatus, reason: value.reason,
    evidenceRefs: [...value.evidenceRefs], approvedCapabilities: [...value.approvedCapabilities], decidedBy: value.decidedBy, decidedAt: value.decidedAt };
}
export function homologationDetail(value: unknown, id: string, uncertain = false): HomologationDetail {
  if (!record(value) || !Array.isArray(value.decisions)) throw invalidResponse(uncertain);
  const homologation = accessHomologation(value.homologation, uncertain);
  if (homologation.id.toLowerCase() !== id.toLowerCase()) throw invalidResponse(uncertain);
  return { homologation, decisions: value.decisions.map((item) => decisionRecord(item, uncertain)) };
}
export function homologationCreated(value: unknown, id: string): HomologationCreated {
  if (!record(value) || typeof value.applied !== 'boolean') throw invalidResponse(true);
  const homologation = accessHomologation(value.homologation, true);
  if (homologation.id.toLowerCase() !== id.toLowerCase()) throw invalidResponse(true);
  return { homologation, applied: value.applied };
}

export function createAccessCatalogApi(client: CondPulseApi = api) {
  return {
    models: async (manufacturer: Manufacturer | null, signal?: AbortSignal) =>
      client.call(`/v1/platform/access-models${manufacturer ? `?${new URLSearchParams({ manufacturer })}` : ''}`, (value) => {
        if (!record(value) || !Array.isArray(value.profiles)) throw invalidResponse();
        return value.profiles.map(accessModelProfile);
      }, signal ? { signal } : {}),
    model: async (modelId: string, signal?: AbortSignal) =>
      client.call(`/v1/platform/access-models/${identifier(modelId)}`, (value) => {
        const profile = accessModelProfile(value);
        if (profile.id.toLowerCase() !== modelId.toLowerCase()) throw invalidResponse();
        return profile;
      }, signal ? { signal } : {}),
    homologations: async (modelId: string | null, signal?: AbortSignal) =>
      client.call(`/v1/platform/access-homologations${modelId ? `?${new URLSearchParams({ modelId: identifier(modelId) })}` : ''}`, (value) => {
        if (!record(value) || !Array.isArray(value.homologations)) throw invalidResponse();
        return value.homologations.map((item) => accessHomologation(item));
      }, signal ? { signal } : {}),
    homologation: async (id: string, signal?: AbortSignal) =>
      client.call(`/v1/platform/access-homologations/${identifier(id)}`, (value) => homologationDetail(value, id), signal ? { signal } : {}),
    /** O identificador nasce com o formulário: repetir o mesmo dossiê inteiro é repetição (applied=false), não duplica. */
    create: async (body: CreateHomologationBody) =>
      client.call('/v1/platform/access-homologations', (value) => homologationCreated(value, body.homologationId), { method: 'POST', body }),
    /** Decisão pela versão lida; o identificador da decisão nasce com o formulário e se mantém na repetição. */
    decide: async (id: string, body: DecideBody) =>
      client.call(`/v1/platform/access-homologations/${identifier(id)}/decisions`, (value) => homologationDetail(value, id, true), { method: 'POST', body }),
  };
}
export const accessCatalog = createAccessCatalogApi();
