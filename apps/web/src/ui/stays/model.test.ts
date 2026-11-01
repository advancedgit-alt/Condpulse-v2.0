// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { dayCloseDate, dayCloseRange, justificationReason, stayStatus, staySummary } from './model';

describe('permanências na tela (CPX-017 a CPX-019)', () => {
  it('vencidas em destaque: N1/N2 em vermelho, vencida sem nível em atenção, justificada marcada', () => {
    expect(stayStatus({ state: 'OPEN', level: 'NONE', justified: false })).toEqual({ label: 'No prazo', tone: 'status-ready' });
    expect(stayStatus({ state: 'OPEN', level: 'DUE', justified: false }).tone).toBe('status-pending');
    expect(stayStatus({ state: 'OPEN', level: 'L1', justified: false })).toEqual({ label: 'Alerta N1', tone: 'status-overdue' });
    expect(stayStatus({ state: 'OPEN', level: 'L2', justified: true })).toEqual({ label: 'Alerta N2 · justificada', tone: 'status-overdue' });
    expect(stayStatus({ state: 'CLOSED', level: 'NONE', justified: false }).tone).toBe('status-neutral');
    expect(stayStatus({ state: 'VOIDED', level: 'NONE', justified: false }).label).toBe('Entrada corrigida');
  });
  it('resumo conta abertas e vencidas', () => {
    expect(staySummary([])).toBe('0 permanências abertas');
    expect(staySummary([{ overdue: true }])).toBe('1 permanência aberta · 1 vencida');
    expect(staySummary([{ overdue: true }, { overdue: false }, { overdue: true }])).toBe('3 permanências abertas · 2 vencidas');
  });
  it('justificativa de 3 a 500 caracteres, normalizada', () => {
    expect(justificationReason('  Aguardando   o morador ')).toBe('Aguardando o morador');
    expect(justificationReason('ok')).toEqual({ problem: 'Informe o motivo, com 3 a 500 caracteres.' });
  });
  it('fechamento do dia: hoje e até 31 dias atrás', () => {
    expect(dayCloseRange('2030-03-01')).toEqual({ min: '2030-01-29', max: '2030-03-01' });
    expect(dayCloseDate('2030-02-15', '2030-03-01')).toBe('2030-02-15');
    expect(dayCloseDate('2030-03-02', '2030-03-01')).toBeNull();
    expect(dayCloseDate('2030-01-28', '2030-03-01')).toBeNull();
    expect(dayCloseDate('15/02/2030', '2030-03-01')).toBeNull();
  });
});
