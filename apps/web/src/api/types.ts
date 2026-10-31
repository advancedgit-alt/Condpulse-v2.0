export type UUID = string;
export type ISODateTime = string;

export interface Membership {
  membershipId: UUID;
  tenantId: UUID;
  tenantName: string;
  timeZone: string;
  unitId: UUID | null;
  role: 'RESIDENT' | 'RESPONSIBLE' | 'DEPENDENT' | 'OWNER' | 'CONCIERGE' | 'MAINTENANCE' | 'MANAGER' | 'SUPPORT';
}

/** Módulos por condomínio (V045). Lista aqui para o parser de /v1/me não depender da API da central. */
export const tenantModuleIds = ['reservations', 'parcels', 'visits', 'announcements', 'requests', 'finance', 'access_credentials', 'facial_access'] as const;
export type TenantModuleId = typeof tenantModuleIds[number];

/** Módulos liberados (flag global ligada e condomínio liberado pela central) de um condomínio dos vínculos. */
export interface TenantModules {
  tenantId: UUID;
  enabled: TenantModuleId[];
}

export interface CurrentIdentity {
  accountId: UUID;
  memberships: Membership[];
  /**
   * Módulos liberados por condomínio, só para o menu: a API confere de novo e responde 403 MODULE_DISABLED. null quando
   * o servidor ainda não informa (versão anterior à V045): nada é escondido e cada tela trata o 403.
   */
  tenantModules: TenantModules[] | null;
}

export interface Session {
  accountId: UUID;
  expiresAt: ISODateTime;
}

export interface Unit {
  id: UUID;
  label: string;
  blockId: UUID | null;
  blockName: string | null;
}

export interface ReservationPolicy {
  schemaVersion: 1;
  weekDays: number[];
  opensAt: string;
  closesAt: string;
  minimumLeadMinutes: number;
  maximumAdvanceDays: number;
  maximumDurationMinutes: number;
  maximumActivePerUnit: number;
  cancellationLeadMinutes: number;
}

export interface Amenity {
  amenityId: UUID;
  name: string;
  capacity: number;
  policyVersion: number;
  feeCents: number;
  bufferMinutes: number;
  approvalRequired: boolean;
  /** AVAILABLE_FOR_APPROVAL_REQUEST cria pedido PENDING_APPROVAL, nunca uma reserva confirmada. */
  bookingState: 'POLICY_NOT_CONFIGURED' | 'AVAILABLE_FOR_APPROVAL_REQUEST' | 'AVAILABLE_FOR_REQUEST';
  policy: ReservationPolicy | null;
}

export interface CreateReservation {
  unitId: UUID;
  amenityId: UUID;
  startsAt: ISODateTime;
  endsAt: ISODateTime;
  acceptedPolicyVersion: number;
}

export interface CancelReservation {
  unitId: UUID;
  expectedVersion: number;
}

export interface Reservation {
  reservationId: UUID;
  unitId: UUID;
  amenityId: UUID;
  status: 'HELD' | 'PENDING_APPROVAL' | 'CONFIRMED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED';
  startsAt: ISODateTime;
  endsAt: ISODateTime;
  occupiedFrom: ISODateTime;
  occupiedUntil: ISODateTime;
  acceptedPolicyVersion: number;
  feeCents: number;
  version: number;
  /** Motivo da decisão, cancelamento ou expiração, visível à unidade. */
  decisionReason: string | null;
}

export interface ReservationPage {
  reservations: Reservation[];
  truncated: boolean;
}

export interface AvailabilityInterval {
  occupiedFrom: ISODateTime;
  occupiedUntil: ISODateTime;
}

export interface AmenityAvailability {
  tenantId: UUID;
  unitId: UUID;
  amenityId: UUID;
  timeZone: string;
  from: ISODateTime;
  until: ISODateTime;
  observedAt: ISODateTime;
  blockedIntervals: AvailabilityInterval[];
}

export interface MutationResult<T> {
  data: T;
  replayed: boolean;
}

export interface MfaFactor {
  factorId: UUID;
  status: 'VERIFIED' | 'UNVERIFIED';
  friendlyName: string | null;
}

export interface MfaStatus {
  assuranceLevel: 'AAL1' | 'AAL2';
  factors: MfaFactor[];
}

/** Exibido uma única vez para cadastro no aplicativo autenticador; nunca persistir. */
export interface TotpEnrollment {
  factorId: UUID;
  otpauthUri: string;
  secret: string;
  qrCodeDataUri: string | null;
}

export interface InvitationAccepted {
  tenantId: UUID;
  membershipId: UUID;
  /** PENDING: convite da gestão (aguarda aprovação); ACTIVE: convite de primeiro gestor emitido pela plataforma. */
  status: 'PENDING' | 'ACTIVE';
}

/** Primeiro acesso por convite: sem sessão (sessionStarted=false) o convidado entra depois pelo login comum. */
export interface InvitationFirstAccess extends InvitationAccepted {
  sessionStarted: boolean;
  accountId: UUID | null;
  expiresAt: ISODateTime | null;
}

/** Tentativa de recuperação criada pelo link; o cookie fica com o servidor e nunca chega ao JavaScript. */
export interface RecoveryVerified {
  mfaRequired: boolean;
  expiresAt: ISODateTime;
}

export interface RecoveryCompleted {
  status: 'PASSWORD_CHANGED';
  otherSessionsEnded: boolean;
}
