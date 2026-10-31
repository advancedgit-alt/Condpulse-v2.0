import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, list, oneOf, record } from './client';
import { codePoints } from './text';
import type { ISODateTime, UUID } from './types';

/**
 * Implantação física do acesso (AC-024 sobre o backend AC-004, V041/V042). Duas fronteiras:
 * - MASTER de implantação (`/technical/*`): concessão técnica vigente e AAL2; vê áreas, pontos, equipamentos, canais,
 *   vínculos e o aceite completo.
 * - Gestão (`/management/*`, capacidade area.manage): área lógica, pontos só pelo nome e o resumo do aceite com a
 *   assinatura das regras. A gestão nunca recebe equipamento, canal, relé nem vínculo.
 * Nenhuma rota carrega senha, segredo de fabricante ou editor de chamadas; o cliente recusa resposta fora do contrato.
 */
export const technicalScopes = ['installation.read', 'installation.manage', 'installation.bind_channel', 'installation.commission',
  'connector.rotate_secret', 'gateway.manage', 'homologation.request', 'person.review', 'access.request_open', 'audit.export'] as const;
export type TechnicalScope = typeof technicalScopes[number];
export interface TechnicalSession { tenantId: UUID; scopes: TechnicalScope[]; validUntil: ISODateTime }

export const areaKinds = ['COMMON_AREA', 'AMENITY', 'RECEPTION', 'TECHNICAL_AREA', 'RESIDENTIAL_ROUTE', 'EXTERIOR'] as const;
export const areaSubtypes = ['EXTERIOR', 'HALL', 'CORRIDOR', 'GYM', 'POOL', 'GARAGE', 'PARTY_ROOM', 'PLAYGROUND', 'SPORTS_COURT', 'MACHINE_ROOM',
  'STORAGE', 'OTHER'] as const;
export const areaStatuses = ['DRAFT', 'ACTIVE', 'INTERDICTED', 'RETIRED'] as const;
export const pointKinds = ['DOOR', 'TURNSTILE', 'VEHICLE_GATE', 'PEDESTRIAN_GATE', 'ELEVATOR_CONTROL', 'OTHER'] as const;
export const flowDirections = ['FROM_TO', 'TO_FROM', 'BOTH', 'UNKNOWN'] as const;
export const egressClasses = ['FREE_EGRESS', 'MANAGED_EGRESS', 'NOT_EGRESS', 'UNVERIFIED'] as const;
export const pointStatuses = ['DRAFT', 'MAPPED', 'COMMISSIONED', 'SUSPENDED', 'RETIRED'] as const;
export const deviceDirections = ['ENTRY', 'EXIT', 'BOTH', 'UNKNOWN'] as const;
export const deviceStatuses = ['ACTIVE', 'RETIRED'] as const;
export const deviceRoles = ['FACE_TERMINAL', 'CONTROLLER', 'READER', 'EXPANSION_MODULE'] as const;
export const connectionModes = ['LOCAL_GATEWAY', 'CLOUD_SERVICE'] as const;
export const channelKinds = ['ACCESS_ACTUATOR', 'DOOR_CONTACT', 'EXIT_REQUEST', 'PASSAGE_SENSOR', 'ALARM_OUTPUT', 'OTHER'] as const;
export const channelStatuses = ['DISCOVERED', 'VERIFIED', 'DISABLED', 'RETIRED'] as const;
export const readerSenses = ['FROM_TO', 'TO_FROM'] as const;
export const bindingStatuses = ['DRAFT', 'PENDING_COMMISSION', 'ACTIVE', 'SUPERSEDED', 'SUSPENDED'] as const;
export const acceptanceStatuses = ['DRAFT', 'TESTING', 'APPROVED', 'REJECTED', 'SUSPENDED', 'SUPERSEDED'] as const;
/** Transições aceitas pelo banco (V042); encerrar exige motivo de 3 a 300 caracteres. */
export const acceptanceTransitions: Readonly<Record<AcceptanceStatus, readonly AcceptanceStatus[]>> = {
  DRAFT: ['TESTING', 'REJECTED'], TESTING: ['APPROVED', 'REJECTED'], APPROVED: ['SUSPENDED', 'SUPERSEDED'], SUSPENDED: ['SUPERSEDED'],
  REJECTED: [], SUPERSEDED: [],
};
export const reasonRequired: readonly AcceptanceStatus[] = ['REJECTED', 'SUSPENDED', 'SUPERSEDED'];

export type AreaKind = typeof areaKinds[number];
export type AreaSubtype = typeof areaSubtypes[number];
export type AreaStatus = typeof areaStatuses[number];
export type PointKind = typeof pointKinds[number];
export type FlowDirection = typeof flowDirections[number];
export type EgressClass = typeof egressClasses[number];
export type PointStatus = typeof pointStatuses[number];
export type DeviceDirection = typeof deviceDirections[number];
export type DeviceRole = typeof deviceRoles[number];
export type ConnectionMode = typeof connectionModes[number];
export type ChannelKind = typeof channelKinds[number];
export type ChannelStatus = typeof channelStatuses[number];
export type ReaderSense = typeof readerSenses[number];
export type BindingStatus = typeof bindingStatuses[number];
export type AcceptanceStatus = typeof acceptanceStatuses[number];

export interface AccessArea {
  areaId: UUID; code: string; name: string; areaKind: AreaKind; blockId: UUID | null; parentAreaId: UUID | null; amenityId: UUID | null;
  timeZone: string; essentialRoute: boolean; physicalBoundaryVerified: boolean; physicalSubtype: AreaSubtype | null; status: AreaStatus;
  updatedAt: ISODateTime; version: number;
}
export interface PointSummary {
  pointId: UUID; code: string; name: string; fromAreaId: UUID; toAreaId: UUID; pointKind: PointKind; flowDirection: FlowDirection; status: PointStatus;
}
export interface AccessPoint extends PointSummary {
  egressClass: EgressClass; accessibleRouteRef: string | null; emergencyDesignRef: string | null; version: number;
}
export interface AccessDevice {
  deviceId: UUID; connectorId: UUID; externalId: string; provisionedInstanceId: UUID | null; label: string; direction: DeviceDirection;
  status: typeof deviceStatuses[number]; hardwareRevision: string | null; homologationId: UUID | null; deviceRole: DeviceRole | null;
  connectionMode: ConnectionMode | null; version: number;
}
export interface DeviceChannel {
  channelId: UUID; deviceId: UUID; nativeNamespace: string; externalChannel: string; channelKind: ChannelKind; status: ChannelStatus; version: number;
}
export interface PointBinding {
  bindingId: UUID; pointId: UUID; bindingRevision: number; actuatorChannelId: UUID; controllerDeviceId: UUID; entryReaderDeviceId: UUID | null;
  exitReaderDeviceId: UUID | null; doorContactChannelId: UUID | null; exitRequestChannelId: UUID | null; passageSensorChannelId: UUID | null;
  homologationId: UUID; installationAcceptanceId: UUID | null; directions: { entryReader: ReaderSense | null; exitReader: ReaderSense | null };
  status: BindingStatus; validFrom: ISODateTime | null; validUntil: ISODateTime | null; wiringEvidenceRef: string; configurationHash: string | null;
  commissionedFingerprint: string | null; version: number;
}
export interface InstallationAcceptance {
  acceptanceId: UUID; installationRevision: number; status: AcceptanceStatus; plantRef: string; plantHash: string; topologyManifestRef: string;
  topologyHash: string; capabilityEnvelopeHash: string; testSuiteVersion: string; testReportRef: string; criticalTestsPending: number;
  criticalTestsFailed: number; emergencyTestRef: string; rollbackPlanRef: string; revocationCheckpointRef: string | null;
  technicalApproverAccountId: UUID | null; technicalSignedAt: ISODateTime | null; rulesApproverAccountId: UUID | null; rulesSignedAt: ISODateTime | null;
  effectiveAt: ISODateTime | null; reviewDueAt: ISODateTime | null; statusReason: string | null; version: number;
}
/** Visão da gestão: sem planta, topologia, relatório nem conta técnica. */
export interface AcceptanceSummary {
  acceptanceId: UUID; installationRevision: number; status: AcceptanceStatus; criticalTestsPending: number; criticalTestsFailed: number;
  technicalSignedAt: ISODateTime | null; rulesSignedAt: ISODateTime | null; rulesApproverAccountId: UUID | null; effectiveAt: ISODateTime | null;
  reviewDueAt: ISODateTime | null; version: number;
}

/** Mesmas expressões do servidor (MapService/InstallService). */
export const CODE = /^[A-Z0-9][A-Z0-9_-]{0,63}$/;
export const REF = /^[A-Za-z0-9._:/-]{3,120}$/;
export const HASH = /^[0-9a-f]{64}$/;
export const EXTERNAL = /^[A-Za-z0-9._:-]{1,120}$/;
export const NAMESPACE = /^[a-z][a-z0-9_.-]{0,63}$/;
export const CHANNEL = /^[A-Za-z0-9._:-]{1,64}$/;
export const SUITE = /^[A-Za-z0-9._-]{1,64}$/;

const text = (value: unknown, minimum: number, maximum: number): value is string =>
  typeof value === 'string' && codePoints(value) >= minimum && codePoints(value) <= maximum;
const nullable = <T>(value: unknown, check: (item: unknown) => item is T): boolean => value === null || check(value);
const optionalUUID = (value: unknown) => nullable(value, isUUID);
const optionalDate = (value: unknown) => nullable(value, isDate);
const matches = (pattern: RegExp) => (value: unknown): value is string => isString(value) && pattern.test(value);
const exact = (value: Record<string, unknown>, keys: readonly string[]) => Object.keys(value).every((key) => keys.includes(key));

function session(value: unknown, tenantId: string): TechnicalSession {
  if (!record(value) || !isUUID(value.tenantId) || value.tenantId.toLowerCase() !== tenantId.toLowerCase() || !Array.isArray(value.scopes)
      || value.scopes.length > technicalScopes.length || !value.scopes.every((scope) => oneOf(scope, technicalScopes)) || !isDate(value.validUntil))
    throw invalidResponse();
  return { tenantId: value.tenantId, scopes: [...value.scopes] as TechnicalScope[], validUntil: value.validUntil };
}

export function accessArea(value: unknown, uncertain = false): AccessArea {
  if (!record(value) || !isUUID(value.areaId) || !matches(CODE)(value.code) || !text(value.name, 1, 160) || !oneOf(value.areaKind, areaKinds)
      || !optionalUUID(value.blockId) || !optionalUUID(value.parentAreaId) || !optionalUUID(value.amenityId) || !text(value.timeZone, 1, 64)
      || typeof value.essentialRoute !== 'boolean' || typeof value.physicalBoundaryVerified !== 'boolean'
      || !(value.physicalSubtype === null || oneOf(value.physicalSubtype, areaSubtypes)) || !oneOf(value.status, areaStatuses)
      || !isDate(value.updatedAt) || !isInteger(value.version) || (value.areaKind === 'AMENITY') !== (value.amenityId !== null))
    throw invalidResponse(uncertain);
  return { areaId: value.areaId, code: value.code, name: value.name, areaKind: value.areaKind, blockId: value.blockId as UUID | null,
    parentAreaId: value.parentAreaId as UUID | null, amenityId: value.amenityId as UUID | null, timeZone: value.timeZone,
    essentialRoute: value.essentialRoute, physicalBoundaryVerified: value.physicalBoundaryVerified,
    physicalSubtype: value.physicalSubtype as AreaSubtype | null, status: value.status, updatedAt: value.updatedAt, version: value.version };
}
const summaryKeys = ['pointId', 'code', 'name', 'fromAreaId', 'toAreaId', 'pointKind', 'flowDirection', 'status'] as const;
export function pointSummary(value: unknown): PointSummary {
  // A gestão recebe só o nome e as áreas: campo de projeto na resposta é quebra de contrato.
  if (!record(value) || !exact(value, summaryKeys) || !isUUID(value.pointId) || !matches(CODE)(value.code) || !text(value.name, 1, 160)
      || !isUUID(value.fromAreaId) || !isUUID(value.toAreaId) || value.fromAreaId === value.toAreaId || !oneOf(value.pointKind, pointKinds)
      || !oneOf(value.flowDirection, flowDirections) || !oneOf(value.status, pointStatuses)) throw invalidResponse();
  return { pointId: value.pointId, code: value.code, name: value.name, fromAreaId: value.fromAreaId, toAreaId: value.toAreaId,
    pointKind: value.pointKind, flowDirection: value.flowDirection, status: value.status };
}
export function accessPoint(value: unknown, uncertain = false): AccessPoint {
  if (!record(value) || !isUUID(value.pointId) || !matches(CODE)(value.code) || !text(value.name, 1, 160) || !isUUID(value.fromAreaId)
      || !isUUID(value.toAreaId) || value.fromAreaId === value.toAreaId || !oneOf(value.pointKind, pointKinds) || !oneOf(value.flowDirection, flowDirections)
      || !oneOf(value.egressClass, egressClasses) || !nullable(value.accessibleRouteRef, matches(REF)) || !nullable(value.emergencyDesignRef, matches(REF))
      || !oneOf(value.status, pointStatuses) || !isInteger(value.version)) throw invalidResponse(uncertain);
  return { pointId: value.pointId, code: value.code, name: value.name, fromAreaId: value.fromAreaId, toAreaId: value.toAreaId,
    pointKind: value.pointKind, flowDirection: value.flowDirection, egressClass: value.egressClass,
    accessibleRouteRef: value.accessibleRouteRef as string | null, emergencyDesignRef: value.emergencyDesignRef as string | null,
    status: value.status, version: value.version };
}
export function accessDevice(value: unknown, uncertain = false): AccessDevice {
  if (!record(value) || !isUUID(value.deviceId) || !isUUID(value.connectorId) || !matches(EXTERNAL)(value.externalId)
      || !optionalUUID(value.provisionedInstanceId) || !text(value.label, 1, 120) || !oneOf(value.direction, deviceDirections)
      || !oneOf(value.status, deviceStatuses) || !nullable(value.hardwareRevision, (item): item is string => text(item, 1, 64))
      || !optionalUUID(value.homologationId) || !(value.deviceRole === null || oneOf(value.deviceRole, deviceRoles))
      || !(value.connectionMode === null || oneOf(value.connectionMode, connectionModes)) || !isInteger(value.version))
    throw invalidResponse(uncertain);
  return { deviceId: value.deviceId, connectorId: value.connectorId, externalId: value.externalId,
    provisionedInstanceId: value.provisionedInstanceId as UUID | null, label: value.label, direction: value.direction, status: value.status,
    hardwareRevision: value.hardwareRevision as string | null, homologationId: value.homologationId as UUID | null,
    deviceRole: value.deviceRole as DeviceRole | null, connectionMode: value.connectionMode as ConnectionMode | null, version: value.version };
}
export function deviceChannel(value: unknown, uncertain = false): DeviceChannel {
  if (!record(value) || !isUUID(value.channelId) || !isUUID(value.deviceId) || !matches(NAMESPACE)(value.nativeNamespace)
      || !matches(CHANNEL)(value.externalChannel) || !oneOf(value.channelKind, channelKinds) || !oneOf(value.status, channelStatuses)
      || !isInteger(value.version)) throw invalidResponse(uncertain);
  return { channelId: value.channelId, deviceId: value.deviceId, nativeNamespace: value.nativeNamespace, externalChannel: value.externalChannel,
    channelKind: value.channelKind, status: value.status, version: value.version };
}
export function pointBinding(value: unknown, uncertain = false): PointBinding {
  const directions = record(value) ? value.directions : undefined;
  if (!record(value) || !isUUID(value.bindingId) || !isUUID(value.pointId) || !isInteger(value.bindingRevision) || value.bindingRevision < 1
      || !isUUID(value.actuatorChannelId) || !isUUID(value.controllerDeviceId) || !optionalUUID(value.entryReaderDeviceId)
      || !optionalUUID(value.exitReaderDeviceId) || !optionalUUID(value.doorContactChannelId) || !optionalUUID(value.exitRequestChannelId)
      || !optionalUUID(value.passageSensorChannelId) || !isUUID(value.homologationId) || !optionalUUID(value.installationAcceptanceId)
      || !record(directions) || !(directions.entryReader === null || oneOf(directions.entryReader, readerSenses))
      || !(directions.exitReader === null || oneOf(directions.exitReader, readerSenses)) || !oneOf(value.status, bindingStatuses)
      || !optionalDate(value.validFrom) || !optionalDate(value.validUntil) || !matches(REF)(value.wiringEvidenceRef)
      || !nullable(value.configurationHash, matches(HASH)) || !nullable(value.commissionedFingerprint, matches(HASH)) || !isInteger(value.version))
    throw invalidResponse(uncertain);
  // Cada leitor tem sentido e vice-versa; fora de rascunho/substituído há aceite; ativo tem início e não tem fim.
  if ((directions.entryReader === null) !== (value.entryReaderDeviceId === null) || (directions.exitReader === null) !== (value.exitReaderDeviceId === null)
      || (!['DRAFT', 'SUPERSEDED'].includes(value.status) && value.installationAcceptanceId === null)
      || (value.status === 'ACTIVE' && (value.validFrom === null || value.validUntil !== null))) throw invalidResponse(uncertain);
  return { bindingId: value.bindingId, pointId: value.pointId, bindingRevision: value.bindingRevision, actuatorChannelId: value.actuatorChannelId,
    controllerDeviceId: value.controllerDeviceId, entryReaderDeviceId: value.entryReaderDeviceId as UUID | null,
    exitReaderDeviceId: value.exitReaderDeviceId as UUID | null, doorContactChannelId: value.doorContactChannelId as UUID | null,
    exitRequestChannelId: value.exitRequestChannelId as UUID | null, passageSensorChannelId: value.passageSensorChannelId as UUID | null,
    homologationId: value.homologationId, installationAcceptanceId: value.installationAcceptanceId as UUID | null,
    directions: { entryReader: directions.entryReader as ReaderSense | null, exitReader: directions.exitReader as ReaderSense | null },
    status: value.status, validFrom: value.validFrom as string | null, validUntil: value.validUntil as string | null,
    wiringEvidenceRef: value.wiringEvidenceRef, configurationHash: value.configurationHash as string | null,
    commissionedFingerprint: value.commissionedFingerprint as string | null, version: value.version };
}
const counts = (value: Record<string, unknown>) => isInteger(value.criticalTestsPending) && isInteger(value.criticalTestsFailed);
/** Assinatura e conta andam juntas; aprovado tem as duas assinaturas, testes críticos zerados e início de vigência. */
function signaturesCoherent(value: Record<string, unknown>, technicalAccount: boolean): boolean {
  if (technicalAccount && (value.technicalSignedAt === null) !== (value.technicalApproverAccountId === null)) return false;
  if ((value.rulesSignedAt === null) !== (value.rulesApproverAccountId === null)) return false;
  if (value.rulesSignedAt !== null && value.technicalSignedAt === null) return false;
  if (value.status === 'APPROVED' && (value.technicalSignedAt === null || value.rulesSignedAt === null || value.criticalTestsPending !== 0
    || value.criticalTestsFailed !== 0 || value.effectiveAt === null)) return false;
  return true;
}
export function installationAcceptance(value: unknown, uncertain = false): InstallationAcceptance {
  if (!record(value) || !isUUID(value.acceptanceId) || !isInteger(value.installationRevision) || value.installationRevision < 1
      || !oneOf(value.status, acceptanceStatuses) || !matches(REF)(value.plantRef) || !matches(HASH)(value.plantHash)
      || !matches(REF)(value.topologyManifestRef) || !matches(HASH)(value.topologyHash) || !matches(HASH)(value.capabilityEnvelopeHash)
      || !matches(SUITE)(value.testSuiteVersion) || !matches(REF)(value.testReportRef) || !counts(value) || !matches(REF)(value.emergencyTestRef)
      || !matches(REF)(value.rollbackPlanRef) || !nullable(value.revocationCheckpointRef, matches(REF)) || !optionalUUID(value.technicalApproverAccountId)
      || !optionalDate(value.technicalSignedAt) || !optionalUUID(value.rulesApproverAccountId) || !optionalDate(value.rulesSignedAt)
      || !optionalDate(value.effectiveAt) || !optionalDate(value.reviewDueAt) || !nullable(value.statusReason, (item): item is string => text(item, 3, 300))
      || !isInteger(value.version) || !signaturesCoherent(value, true)) throw invalidResponse(uncertain);
  return { acceptanceId: value.acceptanceId, installationRevision: value.installationRevision, status: value.status, plantRef: value.plantRef,
    plantHash: value.plantHash, topologyManifestRef: value.topologyManifestRef, topologyHash: value.topologyHash,
    capabilityEnvelopeHash: value.capabilityEnvelopeHash, testSuiteVersion: value.testSuiteVersion, testReportRef: value.testReportRef,
    criticalTestsPending: value.criticalTestsPending as number, criticalTestsFailed: value.criticalTestsFailed as number,
    emergencyTestRef: value.emergencyTestRef, rollbackPlanRef: value.rollbackPlanRef, revocationCheckpointRef: value.revocationCheckpointRef as string | null,
    technicalApproverAccountId: value.technicalApproverAccountId as UUID | null, technicalSignedAt: value.technicalSignedAt as string | null,
    rulesApproverAccountId: value.rulesApproverAccountId as UUID | null, rulesSignedAt: value.rulesSignedAt as string | null,
    effectiveAt: value.effectiveAt as string | null, reviewDueAt: value.reviewDueAt as string | null, statusReason: value.statusReason as string | null,
    version: value.version };
}
const summaryAcceptanceKeys = ['acceptanceId', 'installationRevision', 'status', 'criticalTestsPending', 'criticalTestsFailed', 'technicalSignedAt',
  'rulesSignedAt', 'rulesApproverAccountId', 'effectiveAt', 'reviewDueAt', 'version'] as const;
export function acceptanceSummary(value: unknown, uncertain = false): AcceptanceSummary {
  // Resumo da gestão: planta, topologia, relatório ou conta técnica na resposta é quebra de contrato.
  if (!record(value) || !exact(value, summaryAcceptanceKeys) || !isUUID(value.acceptanceId) || !isInteger(value.installationRevision)
      || value.installationRevision < 1 || !oneOf(value.status, acceptanceStatuses) || !counts(value) || !optionalDate(value.technicalSignedAt)
      || !optionalDate(value.rulesSignedAt) || !optionalUUID(value.rulesApproverAccountId) || !optionalDate(value.effectiveAt)
      || !optionalDate(value.reviewDueAt) || !isInteger(value.version) || !signaturesCoherent(value, false)) throw invalidResponse(uncertain);
  return { acceptanceId: value.acceptanceId, installationRevision: value.installationRevision, status: value.status,
    criticalTestsPending: value.criticalTestsPending as number, criticalTestsFailed: value.criticalTestsFailed as number,
    technicalSignedAt: value.technicalSignedAt as string | null, rulesSignedAt: value.rulesSignedAt as string | null,
    rulesApproverAccountId: value.rulesApproverAccountId as UUID | null, effectiveAt: value.effectiveAt as string | null,
    reviewDueAt: value.reviewDueAt as string | null, version: value.version };
}
function page<T>(field: string, decode: (item: unknown) => T) {
  return (value: unknown): T[] => {
    if (!record(value) || !exact(value, [field]) || !Array.isArray(value[field]) || (value[field] as unknown[]).length > 2000) throw invalidResponse();
    return list(value[field], decode);
  };
}
/** Resposta de mutação precisa ser do mesmo registro enviado. */
function same<T>(decode: (value: unknown, uncertain: boolean) => T, field: keyof T, id: string) {
  return (value: unknown): T => {
    const result = decode(value, true);
    if (String(result[field]).toLowerCase() !== id.toLowerCase()) throw invalidResponse(true);
    return result;
  };
}

export interface CreateAreaBody {
  areaId: UUID; code: string; name: string; areaKind: AreaKind; blockId: UUID | null; parentAreaId: UUID | null; amenityId: UUID | null;
  timeZone: string | null; physicalSubtype: AreaSubtype | null; status: 'DRAFT' | 'ACTIVE';
}
export interface UpdateAreaBody {
  name: string; areaKind: AreaKind; blockId: UUID | null; parentAreaId: UUID | null; amenityId: UUID | null; timeZone: string | null;
  physicalSubtype: AreaSubtype | null; status: AreaStatus; expectedVersion: number;
}
export interface AreaPhysicalBody { essentialRoute: boolean; physicalBoundaryVerified: boolean; expectedVersion: number }
export interface PointFields {
  name: string; fromAreaId: UUID; toAreaId: UUID; pointKind: PointKind; flowDirection: FlowDirection; egressClass: EgressClass;
  accessibleRouteRef: string | null; emergencyDesignRef: string | null;
}
export interface CreatePointBody extends PointFields { pointId: UUID; code: string; status: 'DRAFT' | 'MAPPED' }
export interface UpdatePointBody extends PointFields { status: PointStatus; expectedVersion: number }
export interface DeviceFields {
  label: string; direction: DeviceDirection; hardwareRevision: string | null; homologationId: UUID | null; deviceRole: DeviceRole | null;
  connectionMode: ConnectionMode | null;
}
export interface CreateDeviceBody extends DeviceFields { deviceId: UUID; connectorId: UUID; externalId: string }
export interface UpdateDeviceBody extends DeviceFields { status: typeof deviceStatuses[number]; expectedVersion: number }
export interface CreateChannelBody {
  channelId: UUID; deviceId: UUID; nativeNamespace: string; externalChannel: string; channelKind: ChannelKind; status: 'DISCOVERED' | 'VERIFIED';
}
export interface UpdateChannelBody { channelKind: ChannelKind; status: ChannelStatus; expectedVersion: number }
export interface CreateBindingBody {
  bindingId: UUID; pointId: UUID; actuatorChannelId: UUID; controllerDeviceId: UUID; entryReaderDeviceId: UUID | null; exitReaderDeviceId: UUID | null;
  doorContactChannelId: UUID | null; exitRequestChannelId: UUID | null; passageSensorChannelId: UUID | null; homologationId: UUID;
  directions: { entryReader: ReaderSense | null; exitReader: ReaderSense | null }; wiringEvidenceRef: string;
}
export interface CreateAcceptanceBody {
  acceptanceId: UUID; plantRef: string; plantHash: string; topologyManifestRef: string; topologyHash: string; capabilityEnvelopeHash: string;
  testSuiteVersion: string; testReportRef: string; criticalTestsPending: number; criticalTestsFailed: number; emergencyTestRef: string;
  rollbackPlanRef: string; revocationCheckpointRef: string | null; reviewDueAt: ISODateTime | null;
}
export interface AcceptanceResultsBody {
  criticalTestsPending: number; criticalTestsFailed: number; testReportRef: string; revocationCheckpointRef: string | null; expectedVersion: number;
}

export function createAccessInstallApi(client: CondPulseApi = api) {
  const technical = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}/technical`;
  const managed = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}/management`;
  const options = (signal?: AbortSignal) => signal ? { signal } : {};
  const put = (body: unknown) => ({ method: 'PUT' as const, body });
  const post = (body: unknown) => ({ method: 'POST' as const, body });
  return {
    // ---- MASTER de implantação
    session: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${technical(tenantId)}/session`, (value) => session(value, tenantId), options(signal)),
    technicalAreas: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${technical(tenantId)}/access-areas`, page('areas', (item) => accessArea(item)), options(signal)),
    setAreaPhysical: async (tenantId: string, areaId: string, body: AreaPhysicalBody) =>
      client.call(`${technical(tenantId)}/access-areas/${identifier(areaId)}/physical`, same(accessArea, 'areaId', areaId), put(body)),
    points: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${technical(tenantId)}/access-points`, page('points', (item) => accessPoint(item)), options(signal)),
    createPoint: async (tenantId: string, body: CreatePointBody) =>
      client.call(`${technical(tenantId)}/access-points`, same(accessPoint, 'pointId', identifier(body.pointId)), post(body)),
    updatePoint: async (tenantId: string, pointId: string, body: UpdatePointBody) =>
      client.call(`${technical(tenantId)}/access-points/${identifier(pointId)}`, same(accessPoint, 'pointId', pointId), put(body)),
    devices: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${technical(tenantId)}/access-devices`, page('devices', (item) => accessDevice(item)), options(signal)),
    createDevice: async (tenantId: string, body: CreateDeviceBody) =>
      client.call(`${technical(tenantId)}/access-devices`, same(accessDevice, 'deviceId', identifier(body.deviceId)), post(body)),
    updateDevice: async (tenantId: string, deviceId: string, body: UpdateDeviceBody) =>
      client.call(`${technical(tenantId)}/access-devices/${identifier(deviceId)}`, same(accessDevice, 'deviceId', deviceId), put(body)),
    channels: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${technical(tenantId)}/device-channels`, page('channels', (item) => deviceChannel(item)), options(signal)),
    createChannel: async (tenantId: string, body: CreateChannelBody) =>
      client.call(`${technical(tenantId)}/device-channels`, same(deviceChannel, 'channelId', identifier(body.channelId)), post(body)),
    updateChannel: async (tenantId: string, channelId: string, body: UpdateChannelBody) =>
      client.call(`${technical(tenantId)}/device-channels/${identifier(channelId)}`, same(deviceChannel, 'channelId', channelId), put(body)),
    bindings: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${technical(tenantId)}/point-bindings`, page('bindings', (item) => pointBinding(item)), options(signal)),
    createBinding: async (tenantId: string, body: CreateBindingBody) =>
      client.call(`${technical(tenantId)}/point-bindings`, same(pointBinding, 'bindingId', identifier(body.bindingId)), post(body)),
    submitBinding: async (tenantId: string, bindingId: string, body: { installationAcceptanceId: UUID; expectedVersion: number }) =>
      client.call(`${technical(tenantId)}/point-bindings/${identifier(bindingId)}/submit`, same(pointBinding, 'bindingId', bindingId),
        post({ installationAcceptanceId: identifier(body.installationAcceptanceId), expectedVersion: body.expectedVersion })),
    discardBinding: async (tenantId: string, bindingId: string, body: { expectedVersion: number }) =>
      client.call(`${technical(tenantId)}/point-bindings/${identifier(bindingId)}/discard`, same(pointBinding, 'bindingId', bindingId), post(body)),
    acceptances: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${technical(tenantId)}/installation-acceptances`, page('acceptances', (item) => installationAcceptance(item)), options(signal)),
    createAcceptance: async (tenantId: string, body: CreateAcceptanceBody) =>
      client.call(`${technical(tenantId)}/installation-acceptances`, same(installationAcceptance, 'acceptanceId', identifier(body.acceptanceId)), post(body)),
    recordResults: async (tenantId: string, acceptanceId: string, body: AcceptanceResultsBody) =>
      client.call(`${technical(tenantId)}/installation-acceptances/${identifier(acceptanceId)}/results`,
        same(installationAcceptance, 'acceptanceId', acceptanceId), put(body)),
    signTechnical: async (tenantId: string, acceptanceId: string, body: { expectedVersion: number }) =>
      client.call(`${technical(tenantId)}/installation-acceptances/${identifier(acceptanceId)}/technical-signature`, (value) => {
        const signed = same(installationAcceptance, 'acceptanceId', acceptanceId)(value);
        if (signed.technicalSignedAt === null) throw invalidResponse(true);
        return signed;
      }, post(body)),
    transition: async (tenantId: string, acceptanceId: string, body: { status: AcceptanceStatus; reason: string | null; expectedVersion: number }) =>
      client.call(`${technical(tenantId)}/installation-acceptances/${identifier(acceptanceId)}/status`, (value) => {
        const changed = same(installationAcceptance, 'acceptanceId', acceptanceId)(value);
        if (changed.status !== body.status) throw invalidResponse(true);
        return changed;
      }, post(body)),
    // ---- gestão (area.manage): sem equipamento, canal, relé nem vínculo
    managementAreas: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${managed(tenantId)}/access-areas`, page('areas', (item) => accessArea(item)), options(signal)),
    createArea: async (tenantId: string, body: CreateAreaBody) =>
      client.call(`${managed(tenantId)}/access-areas`, same(accessArea, 'areaId', identifier(body.areaId)), post(body)),
    updateArea: async (tenantId: string, areaId: string, body: UpdateAreaBody) =>
      client.call(`${managed(tenantId)}/access-areas/${identifier(areaId)}`, same(accessArea, 'areaId', areaId), put(body)),
    managementPoints: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${managed(tenantId)}/access-points`, page('points', pointSummary), options(signal)),
    managementAcceptances: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${managed(tenantId)}/installation-acceptances`, page('acceptances', (item) => acceptanceSummary(item)), options(signal)),
    signRules: async (tenantId: string, acceptanceId: string, body: { expectedVersion: number }) =>
      client.call(`${managed(tenantId)}/installation-acceptances/${identifier(acceptanceId)}/rules-signature`, (value) => {
        const signed = same(acceptanceSummary, 'acceptanceId', acceptanceId)(value);
        if (signed.rulesSignedAt === null) throw invalidResponse(true);
        return signed;
      }, post(body)),
  };
}
export const accessInstall = createAccessInstallApi();

export const areaKindLabels: Record<AreaKind, string> = {
  COMMON_AREA: 'Área comum', AMENITY: 'Área reservável', RECEPTION: 'Recepção', TECHNICAL_AREA: 'Área técnica',
  RESIDENTIAL_ROUTE: 'Rota residencial', EXTERIOR: 'Exterior',
};
export const areaSubtypeLabels: Record<AreaSubtype, string> = {
  EXTERIOR: 'Exterior', HALL: 'Hall', CORRIDOR: 'Corredor', GYM: 'Academia', POOL: 'Piscina', GARAGE: 'Garagem', PARTY_ROOM: 'Salão de festas',
  PLAYGROUND: 'Playground', SPORTS_COURT: 'Quadra', MACHINE_ROOM: 'Casa de máquinas', STORAGE: 'Depósito', OTHER: 'Outro',
};
export const areaStatusLabels: Record<AreaStatus, string> = { DRAFT: 'Rascunho', ACTIVE: 'Ativa', INTERDICTED: 'Interditada', RETIRED: 'Desativada' };
export const pointKindLabels: Record<PointKind, string> = {
  DOOR: 'Porta', TURNSTILE: 'Catraca', VEHICLE_GATE: 'Portão de veículos', PEDESTRIAN_GATE: 'Portão de pedestres',
  ELEVATOR_CONTROL: 'Controle de elevador', OTHER: 'Outro',
};
export const flowLabels: Record<FlowDirection, string> = {
  FROM_TO: 'Só origem → destino', TO_FROM: 'Só destino → origem', BOTH: 'Nos dois sentidos', UNKNOWN: 'Sentido não verificado',
};
export const egressLabels: Record<EgressClass, string> = {
  FREE_EGRESS: 'Saída livre', MANAGED_EGRESS: 'Saída controlada', NOT_EGRESS: 'Não é rota de saída', UNVERIFIED: 'Saída não verificada',
};
export const pointStatusLabels: Record<PointStatus, string> = {
  DRAFT: 'Rascunho', MAPPED: 'Mapeado', COMMISSIONED: 'Comissionado', SUSPENDED: 'Suspenso', RETIRED: 'Desativado',
};
export const deviceDirectionLabels: Record<DeviceDirection, string> = { ENTRY: 'Entrada', EXIT: 'Saída', BOTH: 'Entrada e saída', UNKNOWN: 'Não informado' };
export const deviceRoleLabels: Record<DeviceRole, string> = {
  FACE_TERMINAL: 'Terminal facial', CONTROLLER: 'Controladora', READER: 'Leitor', EXPANSION_MODULE: 'Módulo de expansão',
};
export const connectionModeLabels: Record<ConnectionMode, string> = { LOCAL_GATEWAY: 'Gateway local', CLOUD_SERVICE: 'Serviço de nuvem' };
export const channelKindLabels: Record<ChannelKind, string> = {
  ACCESS_ACTUATOR: 'Atuador (relé de abertura)', DOOR_CONTACT: 'Sensor de porta', EXIT_REQUEST: 'Botão de saída',
  PASSAGE_SENSOR: 'Sensor de passagem', ALARM_OUTPUT: 'Saída de alarme', OTHER: 'Outro',
};
export const channelStatusLabels: Record<ChannelStatus, string> = { DISCOVERED: 'Descoberto', VERIFIED: 'Verificado', DISABLED: 'Desligado', RETIRED: 'Desativado' };
export const senseLabels: Record<ReaderSense, string> = { FROM_TO: 'Origem → destino', TO_FROM: 'Destino → origem' };
export const bindingStatusLabels: Record<BindingStatus, string> = {
  DRAFT: 'Rascunho', PENDING_COMMISSION: 'Aguardando comissionamento', ACTIVE: 'Em vigor', SUPERSEDED: 'Substituído', SUSPENDED: 'Suspenso',
};
export const acceptanceStatusLabels: Record<AcceptanceStatus, string> = {
  DRAFT: 'Rascunho', TESTING: 'Em ensaio', APPROVED: 'Aprovado', REJECTED: 'Reprovado', SUSPENDED: 'Suspenso', SUPERSEDED: 'Substituído',
};
export const scopeLabels: Record<TechnicalScope, string> = {
  'installation.read': 'ler a instalação', 'installation.manage': 'cadastrar áreas físicas, pontos, equipamentos e canais',
  'installation.bind_channel': 'vincular canais aos pontos', 'installation.commission': 'comissionar e assinar o aceite técnico',
  'connector.rotate_secret': 'rotacionar segredo do conector', 'gateway.manage': 'gerir o gateway', 'homologation.request': 'pedir homologação',
  'person.review': 'revisar pessoas', 'access.request_open': 'abertura em ensaio', 'audit.export': 'exportar auditoria',
};
