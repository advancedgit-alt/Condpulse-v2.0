import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const spec = join(root, 'spec');
const manifestName = 'SHA256SUMS_pacote_original.txt';
const localFiles = new Set(['LEIA-ME.md', manifestName]);
const excluded = new Set([
  'documentacao/CondPulse_PRD_Arquitetura_v1.0.pdf',
  'documentacao/CondPulse_PRD_Arquitetura_v1.0.docx',
  'referencias/Pesquisa_Concorrentes_26-10-2026.pdf',
  'referencias/Proposta_Origem_26-10-2026_SUPERADA.md',
]);

function manifest() {
  const entries = new Map();
  for (const line of readFileSync(join(spec, manifestName), 'utf8').split('\n')) {
    if (!line.trim()) continue;
    const match = /^([0-9a-f]{64}) {2}(.+)$/.exec(line);
    assert.ok(match, `linha inválida no manifesto: ${line}`);
    entries.set(match[2], match[1]);
  }
  return entries;
}

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [relative(spec, path).split('\\').join('/')];
  });
}

test('arquivos do pacote preservam os bytes do ZIP original', () => {
  const expected = manifest();
  const present = walk(spec).filter((file) => !localFiles.has(file));
  assert.ok(present.length >= 60, `pacote incompleto: ${present.length}`);
  for (const file of present) {
    assert.ok(expected.has(file), `arquivo fora do manifesto original: ${file}`);
    const digest = createHash('sha256').update(readFileSync(join(spec, file))).digest('hex');
    assert.equal(digest, expected.get(file), `conteúdo divergente: ${file}`);
  }
  for (const file of expected.keys()) {
    assert.ok(present.includes(file) || excluded.has(file), `arquivo do pacote ausente sem justificativa: ${file}`);
  }
  for (const file of excluded) assert.ok(!existsSync(join(spec, file)), `arquivo excluído presente: ${file}`);
});

test('backlog vivo cobre exatamente os tickets do pacote', () => {
  const reference = JSON.parse(readFileSync(join(spec, 'planejamento/backlog.json'), 'utf8')).map((ticket) => ticket.id);
  const live = JSON.parse(readFileSync(join(root, 'docs/backlog.json'), 'utf8')).tickets.map((ticket) => ticket.id);
  assert.deepEqual([...live].sort(), [...reference].sort());
});

test('cenários de aceite vivos correspondem aos do pacote', () => {
  const reference = JSON.parse(readFileSync(join(spec, 'qualidade/cenarios_aceite.json'), 'utf8')).map((item) => item.id);
  const live = JSON.parse(readFileSync(join(root, 'tests/acceptance-catalog.json'), 'utf8')).cases.map((item) => item.id);
  assert.deepEqual([...live].sort(), [...reference].sort());
});

test('imagens citadas pelo PRD em Markdown existem no repositório', () => {
  const prd = readFileSync(join(spec, 'documentacao/CondPulse_PRD_Arquitetura_v1.0.md'), 'utf8');
  const images = [...prd.matchAll(/!\[[^\]]*\]\(([^)]+)\)/g)].map((match) => match[1]);
  assert.equal(images.length, 15);
  for (const image of images) assert.ok(existsSync(resolve(spec, 'documentacao', image)), image);
});
