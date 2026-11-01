import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, installSyntheticApi, signIn, type ApiState } from './fixtures';
import { scanAccessibility } from './a11y';

// Entrada por contexto de parceiro e casca /p/{parceiro}/{painel} (CPX-004/CPX-005). Dados exclusivamente sintéticos e
// interceptados pelo runner; nenhum nome de parceiro real.

const PARTNER = '30000000-0000-4000-8000-0000000000a1';
const MEMBER_ADMIN = '60000000-0000-4000-8000-0000000000a1';
const MEMBER_LEAD = '60000000-0000-4000-8000-0000000000a2';
const OPERATOR = '60000000-0000-4000-8000-0000000000a3';
const tenants = [{ tenantId: TENANT_A, tenantName: 'Residencial Sintético A', timeZone: 'America/Sao_Paulo' }];
const admin = { memberId: MEMBER_ADMIN, partnerId: PARTNER, partnerName: 'Parceiro Sintético', role: 'PARTNER_ADMIN', panel: 'gestao',
  capabilities: ['partner.manage', 'partner.team.manage', 'security.log.read'], tenants };
const lead = { memberId: MEMBER_LEAD, partnerId: PARTNER, partnerName: 'Parceiro Sintético', role: 'SECURITY_LEAD', panel: 'comando',
  capabilities: ['security.blocklist.approve', 'security.command', 'security.log.export', 'security.log.read', 'security.policy.publish', 'security.remote_open.review'], tenants };

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}

interface Options { contexts: unknown[]; partnersOn: boolean; securityLogOn: boolean; deniedOnCursor?: boolean; refuseMutations?: boolean;
  /** Corpos das trocas de contexto recebidas (R4.4) e quantas falham com 503 antes de aceitar. */
  switches?: Record<string, unknown>[]; switchFailures?: number }
async function installPartner(page: Page, api: ApiState, options: Options) {
  const requests: string[] = [];
  await page.route('**/auth/mfa', async (route) => { await json(route, 200, { assuranceLevel: 'AAL2', factors: [] }); });
  await page.route('**/v1/**', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (path === '/v1/me') { await json(route, 200, { accountId: ACCOUNT, memberships: [], tenantModules: [], partnerContexts: options.contexts }); return; }
    if (path === '/v1/platform/session') { await json(route, 403, { code: 'PLATFORM_ACCESS_DENIED', status: 403 }); return; }
    if (path.startsWith('/v1/partners/')) {
      requests.push(path);
      // Flag desligada: a rota não existe no servidor.
      if (!options.partnersOn) { await json(route, 404, { status: 404, error: 'Not Found' }); return; }
      // R4.4: registro da entrada no painel; a mesma chave repetida não grava de novo no servidor.
      if (path.endsWith('/context-switches')) {
        const body = route.request().postDataJSON() as Record<string, unknown>;
        options.switches?.push(body);
        if ((options.switchFailures ?? 0) > 0) {
          options.switchFailures! -= 1;
          await json(route, 503, { status: 503, code: 'CONTEXT_SWITCH_UNAVAILABLE', detail: 'Não foi possível registrar a troca de contexto. Tente novamente.' });
          return;
        }
        await json(route, 200, { status: 'RECORDED', recorded: 1 });
        return;
      }
      // Papel revogado entre a leitura e a ação: o servidor recusa a mutação.
      if (options.refuseMutations && route.request().method() === 'POST') {
        await json(route, 403, { status: 403, code: 'PARTNER_ACCESS_DENIED', detail: 'Esta ação não está disponível para o seu papel no parceiro.' }); return;
      }
      if (path.endsWith('/team')) {
        await json(route, 200, { partnerId: PARTNER, members: [{ id: OPERATOR, partnerId: PARTNER, accountId: '10000000-0000-4000-8000-0000000000b3',
          role: 'REMOTE_OPERATOR', displayName: 'Operador Sintético', status: 'ACTIVE', version: 0, createdAt: '2026-10-30T12:00:00Z', revokedAt: null, revokeReason: null }] });
        return;
      }
    }
    if (path.startsWith(`/v1/tenants/${TENANT_A}/security-log`)) {
      requests.push(path);
      if (!options.securityLogOn) { await json(route, 404, { status: 404, error: 'Not Found' }); return; }
      // Contrato encerrado entre uma página e outra: o servidor passa a recusar o condomínio.
      if (options.refuseMutations && route.request().method() === 'POST') {
        await json(route, 404, { status: 404, code: 'TENANT_ACCESS_DENIED', detail: 'Condomínio não disponível para este acesso.' }); return;
      }
      if (options.deniedOnCursor && url.searchParams.has('beforeSeq')) {
        await json(route, 404, { status: 404, code: 'TENANT_ACCESS_DENIED', detail: 'Condomínio não disponível para este acesso.' }); return;
      }
      await json(route, 200, { events: [{ eventId: '90000000-0000-4000-8000-000000000001', seq: 1, occurredAt: '2026-10-31T12:00:00Z', recordedAt: '2026-10-31T12:00:00Z',
        clockSkewMs: null, eventType: 'ACCESS_ENTRY', severity: 'INFO', source: 'CONCIERGE', partnerId: null, actorAccountId: null, actorRole: 'CONCIERGE',
        subjectType: null, subjectId: null, unitId: null, pointId: null, deviceId: null, reason: null, evidenceRef: null, correlationId: null, traceId: null,
        correctsId: null, details: {}, prevHash: '0'.repeat(64), hash: '1'.padStart(64, '0') }], nextBeforeSeq: options.deniedOnCursor ? 1 : null });
      return;
    }
    await route.fallback();
  });
  return requests;
}

test('conta só com o papel de gestor do parceiro vai direto à equipe; abas pelas capacidades', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installPartner(page, api, { contexts: [admin], partnersOn: true, securityLogOn: true });
  await signIn(page);
  await expect(page).toHaveURL(new RegExp(`/p/${PARTNER}/gestao/equipe$`));
  await expect(page.getByRole('heading', { name: 'Painel do Parceiro' })).toBeVisible();
  await expect(page.getByText('Operador Sintético')).toBeVisible();
  const nav = page.getByRole('navigation', { name: 'Navegação principal' });
  for (const label of ['Equipe', 'Escala', 'Carteira', 'Log de Segurança']) await expect(nav.getByRole('link', { name: label })).toBeVisible();
  await scanAccessibility(page, 'parceiro/equipe');
});

test('dois papéis no mesmo parceiro mostram o seletor; comando lista o que vem e consulta o log sob pedido', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const requests = await installPartner(page, api, { contexts: [admin, lead], partnersOn: true, securityLogOn: true });
  await signIn(page);
  await expect(page.getByRole('heading', { name: 'Escolha o acesso.' })).toBeVisible();
  await page.getByRole('link', { name: /Parceiro Sintético – Comando de Segurança/ }).click();
  await expect(page).toHaveURL(new RegExp(`/p/${PARTNER}/comando/log$`));
  const nav = page.getByRole('navigation', { name: 'Navegação principal' });
  for (const label of ['Visão da carteira', 'Revisão de aberturas', 'Políticas de segurança', 'Lista de bloqueio']) {
    await expect(nav.getByText(label)).toBeVisible();
    await expect(nav.getByRole('link', { name: label })).toHaveCount(0);
  }
  expect(requests.filter((path) => path.includes('security-log'))).toEqual([]);
  await page.getByRole('button', { name: 'Consultar log' }).click();
  await expect(page.getByText('#1 · ACCESS_ENTRY')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Exportar com manifesto' })).toBeVisible();
  // Reenviar com os mesmos filtros refaz a busca (achado P2 do Codex).
  const before = requests.filter((path) => path.endsWith('/security-log')).length;
  await page.getByRole('button', { name: 'Consultar log' }).click();
  await expect.poll(() => requests.filter((path) => path.endsWith('/security-log')).length).toBe(before + 1);
  await scanAccessibility(page, 'parceiro/comando/log');
});

test('flags desligadas no servidor: mensagem clara, sem quebrar a tela', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installPartner(page, api, { contexts: [admin], partnersOn: false, securityLogOn: false });
  await signIn(page);
  await expect(page.getByRole('heading', { name: 'Gestão do parceiro indisponível neste ambiente' })).toBeVisible();
  await page.getByRole('navigation', { name: 'Navegação principal' }).getByRole('link', { name: 'Log de Segurança' }).click();
  await page.getByRole('button', { name: 'Consultar log' }).click();
  await expect(page.getByRole('heading', { name: 'Log de Segurança indisponível neste ambiente' })).toBeVisible();
});

test('endereço de painel sem papel ativo mostra aviso e caminho de volta', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installPartner(page, api, { contexts: [admin], partnersOn: true, securityLogOn: true });
  await signIn(page);
  await page.goto(`/p/${PARTNER}/console`);
  await expect(page.getByRole('heading', { name: 'Painel não disponível' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Voltar para meus acessos' })).toBeVisible();
});

test('condomínio recusado na página seguinte tira os eventos já carregados da tela', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installPartner(page, api, { contexts: [lead], partnersOn: true, securityLogOn: true, deniedOnCursor: true });
  await signIn(page);
  await expect(page).toHaveURL(new RegExp(`/p/${PARTNER}/comando/log$`));
  await page.getByRole('button', { name: 'Consultar log' }).click();
  await expect(page.getByText('#1 · ACCESS_ENTRY')).toBeVisible();
  await page.getByRole('button', { name: 'Carregar eventos anteriores' }).click();
  await expect(page.getByRole('heading', { name: 'Acesso não disponível' })).toBeVisible();
  await expect(page.getByText('#1 · ACCESS_ENTRY')).toHaveCount(0);
});

test('recusa numa exportação tira o log da tela; recusa numa ação de equipe derruba o painel', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installPartner(page, api, { contexts: [admin, lead], partnersOn: true, securityLogOn: true, refuseMutations: true });
  await signIn(page);
  await page.goto(`/p/${PARTNER}/comando/log`);
  await page.getByRole('button', { name: 'Consultar log' }).click();
  await expect(page.getByText('#1 · ACCESS_ENTRY')).toBeVisible();
  await page.getByLabel('Motivo da exportação').fill('Pedido formal do condomínio');
  await page.getByRole('button', { name: 'Exportar', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Acesso não disponível' })).toBeVisible();
  await expect(page.getByText('#1 · ACCESS_ENTRY')).toHaveCount(0);

  await page.goto(`/p/${PARTNER}/gestao/equipe`);
  await expect(page.getByText('Operador Sintético')).toBeVisible();
  await page.getByRole('button', { name: 'Revogar papel de Operador Sintético' }).click();
  await page.getByLabel('Motivo da revogação').fill('Saiu da equipe');
  await page.getByRole('button', { name: 'Confirmar revogação' }).click();
  await expect(page.getByText('Os dados exibidos foram removidos.')).toBeVisible();
  await expect(page.getByText('Operador Sintético')).toHaveCount(0);
});

test('R4.4: cada troca de painel é registrada com a origem; voltar ao mesmo painel não registra de novo', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const switches: Record<string, unknown>[] = [];
  const requests = await installPartner(page, api, { contexts: [admin, lead], partnersOn: true, securityLogOn: true, switches });
  await signIn(page);
  await page.getByRole('link', { name: /Parceiro Sintético – Comando de Segurança/ }).click();
  await expect(page.getByRole('heading', { name: 'Comando de Segurança' })).toBeVisible();
  expect(switches).toHaveLength(1);
  expect(switches[0]).toMatchObject({ role: 'SECURITY_LEAD', from: { kind: 'ENTRY' } });
  await page.getByRole('link', { name: 'CondPulse, meus acessos' }).click();
  await page.getByRole('link', { name: /Parceiro Sintético – Painel do Parceiro/ }).click();
  await expect(page.getByText('Operador Sintético')).toBeVisible();
  expect(switches).toHaveLength(2);
  expect(switches[1]).toMatchObject({ role: 'PARTNER_ADMIN', from: { kind: 'PARTNER', partnerId: PARTNER, role: 'SECURITY_LEAD' } });
  expect(switches[1].switchId).not.toBe(switches[0].switchId);
  // O registro vem antes da primeira consulta do painel.
  expect(requests.indexOf(`/v1/partners/${PARTNER}/context-switches`)).toBeLessThan(requests.indexOf(`/v1/partners/${PARTNER}/team`));
  await page.getByRole('link', { name: 'CondPulse, meus acessos' }).click();
  await page.getByRole('link', { name: /Parceiro Sintético – Painel do Parceiro/ }).click();
  await expect(page.getByText('Operador Sintético')).toBeVisible();
  expect(switches, 'mesmo painel sem outro contexto no meio não é troca').toHaveLength(2);
  // Sair e entrar de novo na mesma janela (sem recarregar): a sessão nova começa com origem ENTRY e chave nova.
  await page.getByRole('button', { name: 'Sair' }).click();
  await page.getByLabel('E-mail', { exact: true }).fill('morador-sintetico@example.invalid');
  await page.getByLabel('Senha', { exact: true }).fill('SYNTHETIC-TEST-PASSWORD');
  await page.getByLabel('Senha', { exact: true }).press('Enter');
  await page.getByRole('link', { name: /Parceiro Sintético – Painel do Parceiro/ }).click();
  await expect(page.getByText('Operador Sintético')).toBeVisible();
  expect(switches, 'novo login registra a entrada no mesmo painel').toHaveLength(3);
  expect(switches[2]).toMatchObject({ role: 'PARTNER_ADMIN', from: { kind: 'ENTRY' } });
  expect(switches[2].switchId).not.toBe(switches[1].switchId);
});

test('R4.4: falha ao registrar a troca não abre o painel; nova tentativa usa a mesma chave', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const switches: Record<string, unknown>[] = [];
  const requests = await installPartner(page, api, { contexts: [admin], partnersOn: true, securityLogOn: true, switches, switchFailures: 1 });
  await signIn(page);
  await expect(page.getByText('O registro da entrada no painel está temporariamente indisponível.')).toBeVisible();
  expect(requests.filter((path) => path.endsWith('/team'))).toEqual([]);
  await expect(page.getByText('Operador Sintético')).toHaveCount(0);
  await page.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(page.getByText('Operador Sintético')).toBeVisible();
  expect(switches).toHaveLength(2);
  expect(switches[1].switchId).toBe(switches[0].switchId);
});
