import { ContractError } from './reservations';

/** Mudança e entrega de móveis no aplicativo (CP-042), validadas como na web antes de chegar à tela. */
export type MoveKind = 'MOVE_IN' | 'MOVE_OUT' | 'FURNITURE_DELIVERY';
export type MoveState = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
export interface MoveRules {
  rulesText: string; weekDays: number[]; startTime: string; endTime: string; minNoticeHours: number; maxDurationMinutes: number; version: number;
}
export interface Move {
  moveId: string; unitId: string; kind: MoveKind; startsAt: string; endsAt: string; providerName: string; vehiclePlate: string | null;
  items: string | null; rulesVersion: number; state: MoveState; decisionNote: string | null; version: number;
}
export interface CreateMove {
  kind: MoveKind; startsAt: string; endsAt: string; providerName: string; vehiclePlate: string | null; items: string | null; acceptedRulesVersion: number;
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const int = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value));
const text = (value: unknown, max: number): value is string => typeof value === 'string' && value.length > 0 && [...value].length <= max;
const time = (value: unknown): value is string => typeof value === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(value);
function check(condition: boolean): asserts condition { if (!condition) throw new ContractError(); }

export function moveRules(value: unknown): MoveRules {
  check(record(value) && text(value.rulesText, 2000) && Array.isArray(value.weekDays) && value.weekDays.length >= 1 && value.weekDays.length <= 7
    && value.weekDays.every((day, index, days) => Number.isInteger(day) && day >= 1 && day <= 7 && (index === 0 || day > days[index - 1]))
    && time(value.startTime) && time(value.endTime) && (value.startTime as string) < (value.endTime as string)
    && int(value.minNoticeHours) && value.minNoticeHours <= 720 && int(value.maxDurationMinutes) && value.maxDurationMinutes >= 30
    && value.maxDurationMinutes <= 720 && int(value.version) && value.version >= 1);
  const v = value as Record<string, unknown>;
  return { rulesText: v.rulesText as string, weekDays: [...(v.weekDays as number[])], startTime: v.startTime as string, endTime: v.endTime as string,
    minNoticeHours: v.minNoticeHours as number, maxDurationMinutes: v.maxDurationMinutes as number, version: v.version as number };
}
export function currentRules(value: unknown): MoveRules | null {
  check(record(value) && 'rules' in value);
  return value.rules === null ? null : moveRules(value.rules);
}
export function move(value: unknown): Move {
  check(record(value) && typeof value.moveId === 'string' && UUID.test(value.moveId) && typeof value.unitId === 'string' && UUID.test(value.unitId)
    && ['MOVE_IN', 'MOVE_OUT', 'FURNITURE_DELIVERY'].includes(value.kind as string) && date(value.startsAt) && date(value.endsAt)
    && Date.parse(value.startsAt as string) < Date.parse(value.endsAt as string) && text(value.providerName, 160)
    && (value.vehiclePlate === null || (typeof value.vehiclePlate === 'string' && /^[A-Z0-9]{5,8}$/.test(value.vehiclePlate)))
    && (value.items === null || text(value.items, 500)) && int(value.rulesVersion) && value.rulesVersion >= 1
    && ['REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED'].includes(value.state as string)
    && (value.decisionNote === null || text(value.decisionNote, 500)) && int(value.version)
    && !(value.state === 'REJECTED' && value.decisionNote === null) && !(value.state === 'REQUESTED' && value.decisionNote !== null));
  const v = value as Record<string, unknown>;
  return { moveId: v.moveId as string, unitId: v.unitId as string, kind: v.kind as MoveKind, startsAt: v.startsAt as string, endsAt: v.endsAt as string,
    providerName: v.providerName as string, vehiclePlate: v.vehiclePlate as string | null, items: v.items as string | null,
    rulesVersion: v.rulesVersion as number, state: v.state as MoveState, decisionNote: v.decisionNote as string | null, version: v.version as number };
}
/** Placa como o servidor grava: só letras maiúsculas e números, 5 a 8 caracteres; vazio é sem placa; '' é inválida. */
export function normalizePlate(value: string): string | null {
  const plate = value.replace(/[\s-]/g, '').toUpperCase();
  if (!plate) return null;
  return /^[A-Z0-9]{5,8}$/.test(plate) ? plate : '';
}
