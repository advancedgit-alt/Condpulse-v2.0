const datePattern = /^\d{4}-\d{2}-\d{2}$/;
const wallPattern = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/;

function utc(parts: number[]): number {
  const value = new Date(0);
  value.setUTCFullYear(parts[0]!, parts[1]! - 1, parts[2]!);
  value.setUTCHours(parts[3] ?? 0, parts[4] ?? 0, 0, 0);
  return value.getTime();
}

function wallParts(value: Date, timeZone: string): number[] {
  const formatted = new Intl.DateTimeFormat('en-GB', {
    timeZone, calendar: 'gregory', numberingSystem: 'latn', year: 'numeric', month: '2-digit',
    day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(value);
  return ['year', 'month', 'day', 'hour', 'minute'].map(name =>
    Number(formatted.find(part => part.type === name)?.value));
}

/** Não usa o fuso do navegador e rejeita horário local ambíguo ou inexistente. */
export function toInstant(wallTime: string, timeZone: string): string {
  const match = wallPattern.exec(wallTime);
  if (!match) throw new Error('Informe data e hora completas, com precisão de minutos.');
  const expected = match.slice(1).map(Number);
  const neutral = utc(expected);
  const check = new Date(neutral);
  if (expected[0]! < 1 || expected[0]! > 9999 || expected[1]! < 1 || expected[1]! > 12 ||
      expected[3]! > 23 || expected[4]! > 59 || check.getUTCFullYear() !== expected[0] ||
      check.getUTCMonth() + 1 !== expected[1] || check.getUTCDate() !== expected[2]) {
    throw new Error('A data ou o horário informado é inválido.');
  }
  const matches = new Set<number>();
  for (const hours of [-48, -24, -12, 0, 12, 24, 48]) {
    const probe = neutral + hours * 3_600_000;
    const offset = utc(wallParts(new Date(probe), timeZone)) - probe;
    const candidate = neutral - offset;
    if (wallParts(new Date(candidate), timeZone).every((part, index) => part === expected[index])) {
      matches.add(candidate);
    }
  }
  if (matches.size === 0) throw new Error('Este horário não existe no fuso do condomínio. Escolha outro horário.');
  if (matches.size !== 1) throw new Error('Este horário é ambíguo devido à mudança de fuso. Escolha outro horário.');
  return new Date([...matches][0]!).toISOString();
}

export function dateInZone(timeZone: string, instant = new Date()): string {
  const [year, month, day] = wallParts(instant, timeZone);
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

export function addDays(date: string, days: number): string {
  if (!datePattern.test(date)) throw new Error('Informe uma data válida.');
  const value = new Date(utc(date.split('-').map(Number)));
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

export function queryRange(date: string, timeZone: string): { from: string; until: string } {
  return { from: toInstant(`${date}T00:00`, timeZone), until: toInstant(`${addDays(date, 30)}T00:00`, timeZone) };
}

export function formatWhen(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat('pt-BR', {
    timeZone, day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(new Date(instant));
}

export function money(cents: number): string {
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(cents / 100);
}
