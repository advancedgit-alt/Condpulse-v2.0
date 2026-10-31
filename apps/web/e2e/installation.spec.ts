import { test, expect, type Page, type Route } from '@playwright/test';
import { scanAccessibility } from './a11y';
import { AMENITY_A, TENANT_A, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';

// Implantação física do acesso (AC-024): jornada do MASTER de implantação e assinatura das regras pela gestão.
// Dados sintéticos; nenhuma rota de equipamento real é exercitada.
test.use({ timezoneId: 'UTC' });

const AREA_OUT = '71000000-0000-4000-8000-000000000001';
const AREA_HALL = '71000000-0000-4000-8000-000000000002';
const POINT = '72000000-0000-4000-8000-000000000001';
const CONNECTOR = '43000000-0000-4000-8000-000000000001';
const CONTROLLER = '73000000-0000-4000-8000-000000000001';
const READER = '73000000-0000-4000-8000-000000000002';
const RELAY = '74000000-0000-4000-8000-000000000001';
const DOOR = '74000000-0000-4000-8000-000000000002';
const HOMOLOGATION = '75000000-0000-4000-8000-000000000001';
const ACCEPTANCE = '76000000-0000-4000-8000-000000000001';
const HASH = 'b'.repeat(64);
const ALL_SCOPES = ['installation.read', 'installation.manage', 'installation.bind_channel', 'installation.commission'];

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-0000000000aa' });
}
const area = (areaId: string, code: string, name: string, extra: Record<string, unknown> = {}) => ({ areaId, code, name, areaKind: 'COMMON_AREA', blockId: null,
  parentAreaId: null, amenityId: null, timeZone: 'America/Sao_Paulo', essentialRoute: false, physicalBoundaryVerified: false, physicalSubtype: null,
  status: 'ACTIVE', updatedAt: '2026-10-31T12:00:00Z', version: 0, ...extra });
const acceptance = (extra: Record<string, unknown> = {}) => ({ acceptanceId: ACCEPTANCE, installationRevision: 1, status: 'TESTING', plantRef: 'obra/planta-v1',
  plantHash: HASH, topologyManifestRef: 'obra/rede-v1', topologyHash: HASH, capabilityEnvelopeHash: HASH, testSuiteVersion: 'suite-1', testReportRef: 'obra/ensaio-1',
  criticalTestsPending: 0, criticalTestsFailed: 0, emergencyTestRef: 'obra/emergencia', rollbackPlanRef: 'obra/reversao', revocationCheckpointRef: null,
  technicalApproverAccountId: null, technicalSignedAt: null, rulesApproverAccountId: null, rulesSignedAt: null, effectiveAt: null, reviewDueAt: null,
  statusReason: null, version: 1, ...extra });

interface State {
  scopes: string[]; session: 'ok' | 'denied' | 'off'; areas: Record<string, unknown>[]; acceptances: Record<string, unknown>[];
  bindings: Record<string, unknown>[]; bindingBodies: Record<string, unknown>[]; technicalRequests: string[]; managementRequests: string[];
  rulesBodies: unknown[]; areaBodies: Record<string, unknown>[];
}
async function installInstallApi(page: Page, initial: Partial<State> = {}): Promise<State> {
  const state: State = { scopes: ALL_SCOPES, session: 'ok', areas: [area(AREA_OUT, 'EXTERIOR', 'Calçada'), area(AREA_HALL, 'HALL-A', 'Hall A')],
    acceptances: [acceptance()], bindings: [], bindingBodies: [], technicalRequests: [], managementRequests: [], rulesBodies: [], areaBodies: [], ...initial };
  await page.route(`**/v1/tenants/${TENANT_A}/technical/**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(`/v1/tenants/${TENANT_A}/technical`, '');
    state.technicalRequests.push(`${request.method()} ${path}`);
    if (state.session === 'off') { await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND'); return; }
    if (state.session === 'denied') { await problem(route, 404, 'TENANT_ACCESS_DENIED'); return; }
    if (path === '/session') { await json(route, 200, { tenantId: TENANT_A, scopes: state.scopes, validUntil: '2030-01-01T12:00:00Z' }); return; }
    if (path === '/access-areas') { await json(route, 200, { areas: state.areas }); return; }
    if (path === '/access-points') {
      await json(route, 200, { points: [{ pointId: POINT, code: 'P-PORTARIA', name: 'Portaria social', fromAreaId: AREA_OUT, toAreaId: AREA_HALL, pointKind: 'DOOR',
        flowDirection: 'BOTH', egressClass: 'FREE_EGRESS', accessibleRouteRef: null, emergencyDesignRef: null, status: 'MAPPED', version: 0 }] });
      return;
    }
    if (path === '/access-devices') {
      await json(route, 200, { devices: [
        { deviceId: CONTROLLER, connectorId: CONNECTOR, externalId: 'SN-0001', provisionedInstanceId: null, label: 'Controladora portaria', direction: 'BOTH',
          status: 'ACTIVE', hardwareRevision: 'rev-a', homologationId: HOMOLOGATION, deviceRole: 'CONTROLLER', connectionMode: 'LOCAL_GATEWAY', version: 0 },
        { deviceId: READER, connectorId: CONNECTOR, externalId: 'SN-0002', provisionedInstanceId: null, label: 'Leitor facial portaria', direction: 'ENTRY',
          status: 'ACTIVE', hardwareRevision: null, homologationId: HOMOLOGATION, deviceRole: 'FACE_TERMINAL', connectionMode: null, version: 0 }] });
      return;
    }
    if (path === '/device-channels') {
      await json(route, 200, { channels: [
        { channelId: RELAY, deviceId: CONTROLLER, nativeNamespace: 'relay', externalChannel: '1', channelKind: 'ACCESS_ACTUATOR', status: 'VERIFIED', version: 0 },
        { channelId: DOOR, deviceId: CONTROLLER, nativeNamespace: 'door', externalChannel: '1', channelKind: 'DOOR_CONTACT', status: 'VERIFIED', version: 0 }] });
      return;
    }
    if (path === '/point-bindings' && request.method() === 'GET') { await json(route, 200, { bindings: state.bindings }); return; }
    if (path === '/point-bindings' && request.method() === 'POST') {
      const body = request.postDataJSON() as Record<string, unknown>;
      state.bindingBodies.push(body);
      const created = { ...body, bindingRevision: 1, installationAcceptanceId: null, status: 'DRAFT', validFrom: null, validUntil: null,
        configurationHash: null, commissionedFingerprint: null, version: 0 };
      state.bindings.push(created);
      await json(route, 200, created);
      return;
    }
    if (path === '/installation-acceptances') { await json(route, 200, { acceptances: state.acceptances }); return; }
    await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND');
  });
  await page.route(`**/v1/tenants/${TENANT_A}/management/**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(`/v1/tenants/${TENANT_A}/management`, '');
    if (!['/access-areas', '/access-points', '/installation-acceptances'].some((prefix) => path.startsWith(prefix))) { await route.fallback(); return; }
    state.managementRequests.push(`${request.method()} ${path}`);
    if (state.session === 'off') { await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND'); return; }
    if (path === '/access-areas' && request.method() === 'GET') { await json(route, 200, { areas: state.areas }); return; }
    if (path === '/access-areas' && request.method() === 'POST') {
      const body = request.postDataJSON() as Record<string, unknown>;
      state.areaBodies.push(body);
      const created = area(body.areaId as string, body.code as string, body.name as string, { areaKind: body.areaKind, physicalSubtype: body.physicalSubtype,
        parentAreaId: body.parentAreaId, status: body.status });
      state.areas.push(created);
      await json(route, 200, created);
      return;
    }
    if (path === '/access-points') {
      await json(route, 200, { points: [{ pointId: POINT, code: 'P-PORTARIA', name: 'Portaria social', fromAreaId: AREA_OUT, toAreaId: AREA_HALL,
        pointKind: 'DOOR', flowDirection: 'BOTH', status: 'MAPPED' }] });
      return;
    }
    const summary = (item: Record<string, unknown>) => ({ acceptanceId: item.acceptanceId, installationRevision: item.installationRevision, status: item.status,
      criticalTestsPending: item.criticalTestsPending, criticalTestsFailed: item.criticalTestsFailed, technicalSignedAt: item.technicalSignedAt,
      rulesSignedAt: item.rulesSignedAt, rulesApproverAccountId: item.rulesApproverAccountId, effectiveAt: item.effectiveAt, reviewDueAt: item.reviewDueAt, version: item.version });
    if (path === '/installation-acceptances') { await json(route, 200, { acceptances: state.acceptances.map(summary) }); return; }
    if (path === `/installation-acceptances/${ACCEPTANCE}/rules-signature`) {
      state.rulesBodies.push(request.postDataJSON());
      const current = state.acceptances[0]!;
      Object.assign(current, { rulesSignedAt: '2026-10-31T15:00:00Z', rulesApproverAccountId: '10000000-0000-4000-8000-000000000001', version: (current.version as number) + 1 });
      await json(route, 200, summary(current));
      return;
    }
    await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND');
  });
  return state;
}
async function openInstallation(page: Page, initial: Partial<State> = {}, section = 'roteiro') {
  await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installInstallApi(page, initial);
  await signIn(page);
  await page.goto(`/implantacao/${TENANT_A}/${section}`);
  return state;
}

test('roteiro mostra as etapas e o vínculo exige local e contexto conferidos', async ({ page }) => {
  const state = await openInstallation(page);
  await expect(page.getByRole('heading', { name: 'Implantação física do acesso' })).toBeVisible();
  const steps = page.getByRole('region', { name: 'Etapas da implantação' });
  await expect(steps.getByText('1 ponto(s) sem vínculo')).toBeVisible();
  await expect(steps.getByText(/Assinatura técnica: pendente · Assinatura da gestão \(regras\): pendente/)).toBeVisible();
  await scanAccessibility(page, 'implantação/roteiro');
  await page.getByRole('navigation', { name: 'Seções da implantação' }).getByRole('link', { name: 'Vínculos' }).click();
  const section = page.getByRole('region', { name: 'Vínculos físicos dos pontos' });
  await section.getByRole('button', { name: 'Novo vínculo' }).click();
  const form = page.getByRole('form', { name: 'Novo vínculo' });
  await form.getByLabel('Ponto', { exact: true }).selectOption(POINT);
  await form.getByLabel('Controladora', { exact: true }).selectOption(CONTROLLER);
  await expect(form.getByLabel('Combinação homologada', { exact: true })).toHaveValue(HOMOLOGATION);
  await form.getByLabel('Relé de abertura', { exact: true }).selectOption(RELAY);
  await form.getByLabel('Leitor de entrada', { exact: true }).selectOption(READER);
  await form.getByLabel('Sentido do leitor de entrada', { exact: true }).selectOption('FROM_TO');
  // Remover um leitor limpa o sentido dele; escolher de novo exige novo sentido.
  await form.getByLabel('Leitor de saída', { exact: true }).selectOption(READER);
  await form.getByLabel('Sentido do leitor de saída', { exact: true }).selectOption('TO_FROM');
  await form.getByLabel('Leitor de saída', { exact: true }).selectOption('');
  await expect(form.getByLabel('Sentido do leitor de saída', { exact: true })).toHaveValue('');
  await form.getByLabel('Sensor de porta', { exact: true }).selectOption(DOOR);
  await form.getByLabel(/Referência da ligação/).fill('obra/ligacao-portaria');
  const context = form.getByRole('group', { name: 'Local e contexto da ação' });
  await expect(context.getByText('Portaria social (P-PORTARIA) — Calçada (EXTERIOR) → Hall A (HALL-A)')).toBeVisible();
  await expect(form.getByRole('button', { name: 'Gravar vínculo' })).toBeDisabled();
  await context.getByLabel('Conferi no local o ponto físico e o condomínio acima').check();
  await scanAccessibility(page, 'implantação/vínculo');
  await form.getByRole('button', { name: 'Gravar vínculo' }).click();
  await expect(page.getByText('Vínculo gravado em rascunho.')).toBeVisible();
  expect(state.bindingBodies).toHaveLength(1);
  expect(state.bindingBodies[0]).toMatchObject({ pointId: POINT, controllerDeviceId: CONTROLLER, actuatorChannelId: RELAY, entryReaderDeviceId: READER,
    exitReaderDeviceId: null, doorContactChannelId: DOOR, homologationId: HOMOLOGATION, directions: { entryReader: 'FROM_TO', exitReader: null },
    wiringEvidenceRef: 'obra/ligacao-portaria' });
  await expect(section.getByRole('list', { name: 'Vínculos' }).getByText('Rascunho')).toBeVisible();
  expect(state.managementRequests).toEqual([]);
});

test('seções aparecem só com o escopo; aceite mostra os aceites técnico e local separados', async ({ page }) => {
  await openInstallation(page, { scopes: ['installation.read', 'installation.commission'] }, 'aceite');
  const tabs = page.getByRole('navigation', { name: 'Seções da implantação' });
  await expect(tabs.getByRole('link')).toHaveText(['Roteiro', 'Ensaios e aceite']);
  const signatures = page.getByRole('definition').filter({ hasText: 'Aguardando' });
  await expect(page.getByText('Aceite técnico (implantação)')).toBeVisible();
  await expect(page.getByText('Aceite local das regras (gestão)')).toBeVisible();
  await expect(signatures).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Aprovar e comissionar' })).toBeDisabled();
  await page.getByRole('button', { name: 'Assinar aceite técnico' }).click();
  const sign = page.getByRole('form', { name: 'Assinar aceite técnico da revisão 1' });
  await expect(sign.getByRole('button', { name: 'Assinar aceite técnico' })).toBeDisabled();
  await scanAccessibility(page, 'implantação/aceite');
  await page.goto(`/implantacao/${TENANT_A}/equipamentos`);
  await expect(page.getByRole('heading', { name: 'Etapas da implantação' })).toBeVisible();
});

test('concessão revogada com dados em cache: a tela descarta os dados técnicos', async ({ page }) => {
  const state = await openInstallation(page, {}, 'equipamentos');
  const section = page.getByRole('region', { name: 'Inventário e rede' });
  await expect(section.getByText('Controladora portaria')).toBeVisible();
  state.session = 'denied';
  await section.getByRole('button', { name: 'Atualizar' }).click();
  await expect(page.getByText('A concessão técnica deste condomínio não está mais vigente para esta conta. Os dados exibidos foram removidos.')).toBeVisible();
  await expect(page.getByText('Controladora portaria')).toHaveCount(0);
});

test('sem concessão técnica ou com a função desligada a implantação não mostra dados', async ({ page }) => {
  const state = await openInstallation(page, { session: 'denied' });
  await expect(page.getByText('Sem concessão técnica')).toBeVisible();
  state.session = 'off';
  await page.reload();
  await expect(page.getByText('Implantação indisponível')).toBeVisible();
  expect(state.technicalRequests.every((item) => item.endsWith('/session'))).toBe(true);
});

test('gestão cria área de acesso e assina as regras sem ver equipamento, canal ou vínculo', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installInstallApi(page, { acceptances: [acceptance({ technicalSignedAt: '2026-10-31T14:00:00Z',
    technicalApproverAccountId: '10000000-0000-4000-8000-0000000000ff', version: 2 })] });
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/mapa-acesso`);
  const areas = page.getByRole('region', { name: 'Áreas de acesso' });
  await expect(areas.getByText('Hall A')).toBeVisible();
  await areas.getByRole('button', { name: 'Nova área de acesso' }).click();
  const form = page.getByRole('form', { name: 'Nova área de acesso' });
  await form.getByLabel('Código', { exact: true }).fill('garagem-1');
  await form.getByLabel('Nome', { exact: true }).fill('Garagem');
  // Trocar de área reservável para outro tipo descarta a área de reserva escolhida (o seletor some).
  await form.getByLabel('Tipo', { exact: true }).selectOption('AMENITY');
  await form.getByLabel('Área de reserva', { exact: true }).selectOption(AMENITY_A);
  await form.getByLabel('Tipo', { exact: true }).selectOption('COMMON_AREA');
  await expect(form.getByLabel('Área de reserva', { exact: true })).toHaveCount(0);
  await form.getByLabel('Subtipo (opcional)', { exact: true }).selectOption('GARAGE');
  await form.getByRole('button', { name: 'Criar área' }).click();
  await expect(areas.getByText('Área de acesso criada.')).toBeVisible();
  expect(state.areaBodies[0]).toMatchObject({ code: 'GARAGEM-1', name: 'Garagem', areaKind: 'COMMON_AREA', physicalSubtype: 'GARAGE', amenityId: null, timeZone: null, status: 'ACTIVE' });
  const acceptanceRegion = page.getByRole('region', { name: 'Aceite da instalação' });
  await expect(acceptanceRegion.getByText(/Assinado em/)).toHaveCount(1);
  await acceptanceRegion.getByRole('button', { name: 'Assinar regras (aceite local)' }).click();
  const sign = page.getByRole('form', { name: 'Assinar regras da revisão 1' });
  await expect(sign.getByRole('button', { name: 'Assinar regras' })).toBeDisabled();
  await sign.getByLabel('Conferi áreas e pontos desta revisão').check();
  await scanAccessibility(page, 'gestão/mapa de acesso');
  await sign.getByRole('button', { name: 'Assinar regras' }).click();
  await expect(acceptanceRegion.getByText(/Regras assinadas pela gestão/)).toBeVisible();
  expect(state.rulesBodies).toEqual([{ expectedVersion: 2 }]);
  await expect(acceptanceRegion.getByText(/por você/)).toBeVisible();
  expect(state.technicalRequests).toEqual([]);
  await expect(page.getByText('Controladora portaria')).toHaveCount(0);
});
