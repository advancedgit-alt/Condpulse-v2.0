export type UUID = string;
export type ISODateTime = string;

export interface Membership {
  membershipId: UUID;
  tenantId: UUID;
  tenantName: string;
  timeZone: string;
  unitId: UUID | null;
  role: 'RESIDENT' | 'RESPONSIBLE' | 'DEPENDENT' | 'OWNER' | 'CONCIERGE' | 'MANAGER' | 'SUPPORT';
}

export interface CurrentIdentity {
  accountId: UUID;
  memberships: Membership[];
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
  bookingState: 'POLICY_NOT_CONFIGURED' | 'APPROVAL_UNAVAILABLE' | 'AVAILABLE_FOR_REQUEST';
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
