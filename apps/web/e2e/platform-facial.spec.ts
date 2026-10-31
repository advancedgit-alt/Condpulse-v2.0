import { test, expect, type Route } from '@playwright/test';
import { ACCOUNT, installSyntheticApi, signIn } from './fixtures';
import { scanAccessibility } from './a11y';

// Acesso facial na central (V040): catálogo, dossiê e decisões. Dados exclusivamente sintéticos, interceptados pelo runner.

const MODEL = '30000000-0000-4000-8000-0000000000f1';
const EVIDENCE = '60000000-0000-4000-8000-0000000000f1';
const HASH = 'b'.repeat(64);
const CAPS = ['doorContactEvents', 'eventBackfill', 'faceDeletion', 'faceEnrollment', 'localValidity', 'offlineRestriction', 'onlineDecisionBeforeRelay',
  'passageEvents', 'perDoorRights', 'safeRecovery', 'scopedRemoteOpen', 'secureTransport'];
const next: Record<string, string> = { 'CANDIDATO_DOCUMENTADO:START_TRIAL': 'EM_ENSAIO', 'EM_ENSAIO:APPROVE_LAB': 'APROVADO_BANCADA' };

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
const profile = { id: MODEL, catalogCode: 'CI-M01', manufacturer: 'CONTROL_ID', sku: 'iDFace Sintético', hardwareRevision: '', region: 'BR', category: 'FACE_TERMINAL',
  sourceCategory: 'TERMINAL_FACIAL', lifecycle: 'DOCUMENTED_CANDIDATE', status: 'CANDIDATE', revision: 1,
  sources: [{ id: 'S1', title: 'Ficha sintética do fabricante', url: 'https://fabricante.example.invalid/ficha', consultedOn: '2026-10-30' },
    { id: 'S2', title: 'Fonte sem link seguro', url: 'javascript:alert(1)', consultedOn: '2026-10-30' }],
  declaredCapabilities: { interfaces: ['REST', 'Ethernet'], ingressProtection: 'IP65' }, declaredLimits: { facesPublished: 3000 },
  notes: 'Número declarado não é medição.', consultedOn: '2026-10-30', documentHash: HASH };

test('central consulta o catálogo, registra dossiê candidato e decide pela versão lida', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const homologations: Record<string, unknown>[] = [];
  const decisions: Record<string, unknown[]> = {};
  const bodies: { path: string; body: Record<string, unknown> }[] = [];
  await page.route('**/v1/**', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path === '/v1/me') { await json(route, 200, { accountId: ACCOUNT, memberships: [], tenantModules: [] }); return; }
    if (path === '/v1/platform/session') {
      await json(route, 200, { accountId: ACCOUNT, role: 'PLATFORM_ADMIN', mfaRequired: false,
        capabilities: ['access_catalog.manage', 'homologation.approve', 'platform.audit.read', 'tenants.read'] });
      return;
    }
    if (path === '/v1/platform/tenants') { await json(route, 200, { tenants: [], truncated: false }); return; }
    if (path === '/v1/platform/access-models') { await json(route, 200, { profiles: url.searchParams.get('manufacturer') === 'DAHUA' ? [] : [profile] }); return; }
    if (path === `/v1/platform/access-models/${MODEL}`) { await json(route, 200, profile); return; }
    if (path === '/v1/platform/access-homologations' && request.method() === 'GET') { await json(route, 200, { homologations }); return; }
    if (path === '/v1/platform/access-homologations' && request.method() === 'POST') {
      const body = request.postDataJSON() as Record<string, unknown>;
      bodies.push({ path, body });
      const capabilities = Object.fromEntries(Object.entries(body.capabilities as Record<string, object>).map(([name, value]) => [name, { ...value, approved: null }]));
      const created = { id: body.homologationId, revision: 1, modelProfileId: body.modelProfileId, firmwareVersion: body.firmwareVersion, adapterName: body.adapterName,
        adapterVersion: body.adapterVersion, apiContractVersion: body.apiContractVersion, operatingMode: body.operatingMode, topology: body.topology, capabilities,
        limits: body.limits, limitations: body.limitations, evidenceRefs: body.evidenceRefs, evidenceHash: HASH, status: 'CANDIDATO_DOCUMENTADO', approvedCapabilities: [],
        restrictions: [], approvedAt: null, reviewDueAt: null, current: false, version: 0 };
      homologations.unshift(created); decisions[String(body.homologationId)] = [];
      await json(route, 201, { homologation: created, applied: true });
      return;
    }
    const detail = /^\/v1\/platform\/access-homologations\/([^/]+)(\/decisions)?$/.exec(path);
    if (detail) {
      const item = homologations.find((entry) => entry.id === detail[1])!;
      if (detail[2] && request.method() === 'POST') {
        const body = request.postDataJSON() as Record<string, unknown>;
        bodies.push({ path, body });
        if (body.expectedVersion !== item.version) { await json(route, 409, { code: 'HOMOLOGATION_STALE', status: 409 }); return; }
        const to = next[`${item.status}:${body.decision}`]!;
        decisions[String(item.id)]!.push({ decisionId: body.decisionId, decision: body.decision, fromStatus: item.status, toStatus: to, reason: body.reason,
          evidenceRefs: body.evidenceRefs, approvedCapabilities: body.approvedCapabilityNames, decidedBy: ACCOUNT, decidedAt: '2030-01-02T12:00:00.000Z' });
        Object.assign(item, { status: to, version: Number(item.version) + 1 });
        if (String(body.decision).startsWith('APPROVE_')) Object.assign(item, { approvedCapabilities: body.approvedCapabilityNames, restrictions: body.restrictions,
          approvedAt: '2030-01-02T12:00:00.000Z', current: true });
      }
      await json(route, 200, { homologation: item, decisions: decisions[String(item.id)] });
      return;
    }
    await route.fallback();
  });
  await signIn(page);
  await expect(page).toHaveURL(/\/central\/condominios$/);
  const nav = page.getByRole('navigation', { name: 'Navegação principal' });
  await nav.getByRole('link', { name: 'Acesso facial' }).click();
  await expect(page).toHaveURL(/\/central\/facial$/);
  const catalog = page.getByRole('list', { name: 'Modelos do catálogo' });
  await expect(catalog.getByText('CI-M01 · Control iD iDFace Sintético')).toBeVisible();
  await page.getByLabel('Fabricante').selectOption('DAHUA');
  await expect(page.getByText('Nenhum modelo')).toBeVisible();
  await page.getByLabel('Fabricante').selectOption('');
  await catalog.getByRole('link', { name: 'Ver perfil CI-M01' }).click();
  const sources = page.getByRole('list', { name: 'Fontes oficiais' });
  await expect(sources.getByRole('link', { name: 'Ficha sintética do fabricante' })).toHaveAttribute('href', 'https://fabricante.example.invalid/ficha');
  // Endereço não https aparece como texto, nunca como link.
  await expect(sources.getByRole('link')).toHaveCount(1);
  await expect(page.getByText('Número declarado não é medição.')).toBeVisible();
  await scanAccessibility(page, 'central/facial perfil');

  await page.getByRole('link', { name: 'Ver homologações' }).click();
  await expect(page.getByText('Nenhuma homologação')).toBeVisible();
  await page.getByRole('button', { name: 'Novo dossiê' }).click();
  const form = page.getByRole('form', { name: 'Novo dossiê de homologação' });
  await expect(form.getByRole('button', { name: 'Registrar dossiê candidato' })).toBeDisabled();
  await form.getByLabel('Firmware exato').fill('1.2.3-sint');
  await form.getByLabel('Versão do contrato da API').fill('v1');
  await form.getByLabel('Adaptador', { exact: true }).fill('controlid-rest');
  await form.getByLabel('Versão do adaptador').fill('0.1.0');
  await form.getByLabel('Modo de operação').selectOption('EDGE_ONLINE');
  await form.getByLabel('Topologia').selectOption('LOCAL_GATEWAY');
  await form.getByLabel('Cadastro de face: documentada').selectOption('yes');
  await form.getByLabel('Cadastro de face: observada').selectOption('yes');
  await form.getByLabel('Faces').fill('3000');
  await form.getByLabel(/^Motivo/).fill('Dossiê inicial do ensaio sintético');
  await scanAccessibility(page, 'central/facial novo dossiê');
  await form.getByRole('button', { name: 'Registrar dossiê candidato' }).click();
  await expect(page.getByText(/Dossiê candidato registrado \(revisão 1\)\. Nada foi aprovado/)).toBeVisible();
  const created = bodies[0]!.body;
  expect(created).toMatchObject({ modelProfileId: MODEL, firmwareVersion: '1.2.3-sint', adapterName: 'controlid-rest', operatingMode: 'EDGE_ONLINE',
    topology: 'LOCAL_GATEWAY', evidenceRefs: [], limitations: [], reason: 'Dossiê inicial do ensaio sintético' });
  expect(created.capabilities).toMatchObject({ faceEnrollment: { documented: true, observed: true }, secureTransport: { documented: null, observed: null } });
  expect(created.limits).toMatchObject({ faces: 3000, users: null });

  await page.getByRole('list', { name: 'Homologações' }).getByRole('link', { name: /Abrir dossiê 1\.2\.3-sint de CI-M01/ }).click();
  await expect(page.getByText('Candidato documentado').first()).toBeVisible();
  await page.getByRole('button', { name: 'Registrar decisão' }).click();
  const decision = page.getByRole('form', { name: 'Registrar decisão da homologação' });
  await expect(decision.getByLabel('Decisão').locator('option')).toHaveText(['Escolha…', 'Iniciar ensaio', 'Reprovar']);
  await decision.getByLabel('Decisão').selectOption('START_TRIAL');
  await decision.getByLabel(/^Motivo/).fill('Bancada sintética montada');
  await decision.getByRole('button', { name: 'Confirmar decisão' }).click();
  await expect(page.getByText('Decisão registrada: Candidato documentado → Em ensaio.')).toBeVisible();

  await page.getByRole('button', { name: 'Registrar decisão' }).click();
  await decision.getByLabel('Decisão').selectOption('APPROVE_LAB');
  await decision.getByLabel(/^Motivo/).fill('Ensaio de bancada concluído');
  await expect(decision.getByRole('button', { name: 'Confirmar decisão' })).toBeDisabled();
  await expect(decision.getByText('Aprovação exige ao menos uma referência de evidência de execução própria.')).toBeVisible();
  await decision.getByLabel(/^Evidências/).fill(EVIDENCE);
  // Só a capacidade observada no dossiê pode ser aprovada.
  await expect(decision.getByRole('checkbox')).toHaveCount(1);
  await decision.getByRole('checkbox', { name: 'Cadastro de face' }).check();
  await decision.getByLabel(/^Restrições/).fill('Somente portas internas');
  await decision.getByRole('button', { name: 'Confirmar decisão' }).click();
  await expect(page.getByText('Decisão registrada: Em ensaio → Aprovado em bancada.')).toBeVisible();
  await expect(page.getByText('Vigente', { exact: true })).toBeVisible();
  const trail = page.getByRole('list', { name: 'Decisões da homologação' });
  await expect(trail.getByRole('listitem')).toHaveCount(2);
  expect(bodies.slice(1).map((item) => item.body)).toMatchObject([
    { decision: 'START_TRIAL', expectedVersion: 0, evidenceRefs: [], approvedCapabilityNames: [], restrictions: [], reviewDueAt: null },
    { decision: 'APPROVE_LAB', expectedVersion: 1, evidenceRefs: [EVIDENCE], approvedCapabilityNames: ['faceEnrollment'], restrictions: ['Somente portas internas'] },
  ]);
  await page.getByRole('link', { name: 'Gateways' }).click();
  await expect(page.getByText('Gateways: em breve')).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
