import { describe, expect, it } from 'vitest';
import { addDays, dateInZone, queryRange, toInstant } from './dateTime';

describe('datas no fuso do condomínio', () => {
  it('converte São Paulo explicitamente sem usar o fuso do navegador', () => {
    expect(toInstant('2030-01-10T10:00', 'America/Sao_Paulo')).toBe('2030-01-10T13:00:00.000Z');
    expect(dateInZone('America/Sao_Paulo', new Date('2026-10-28T01:30:00Z'))).toBe('2026-10-27');
  });
  it('considera offsets com fração de hora', () => {
    expect(toInstant('2030-01-10T10:00', 'Asia/Kathmandu')).toBe('2030-01-10T04:15:00.000Z');
  });
  it('preserva um dia bissexto válido e rejeita normalização silenciosa', () => {
    expect(toInstant('2028-02-29T10:00', 'UTC')).toBe('2028-02-29T10:00:00.000Z');
    for (const value of ['2026-02-29T10:00', '2030-13-01T10:00', '0000-01-01T10:00', '2030-01-01T24:00', '2030-01-01T10:61']) {
      expect(() => toInstant(value, 'UTC')).toThrow();
    }
  });
  it('rejeita horário inexistente no avanço do horário local', () => {
    expect(() => toInstant('2026-03-08T02:30', 'America/New_York')).toThrow(/não existe/);
  });
  it('rejeita horário repetido e ambíguo na volta do horário local', () => {
    expect(() => toInstant('2026-11-01T01:30', 'America/New_York')).toThrow(/ambíguo/);
  });
  it('rejeita também mudanças locais de trinta minutos', () => {
    expect(() => toInstant('2026-10-04T02:15', 'Australia/Lord_Howe')).toThrow(/não existe/);
    expect(() => toInstant('2026-04-05T01:45', 'Australia/Lord_Howe')).toThrow(/ambíguo/);
  });
  it('consulta trinta dias locais mesmo atravessando uma mudança de offset', () => {
    expect(queryRange('2026-03-01', 'America/New_York')).toEqual({
      from: '2026-03-01T05:00:00.000Z', until: '2026-03-31T04:00:00.000Z',
    });
    expect(addDays('2028-02-28', 2)).toBe('2028-03-01');
  });
  it('não aceita um fuso desconhecido como se fosse o fuso do dispositivo', () => {
    expect(() => toInstant('2030-01-10T10:00', 'Invalid/Zone')).toThrow();
  });
});
