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

interface Options { contexts: unknown[]; partnersOn: boolean; securityLogOn: boolean }
async function installPartner(page: Page, api: ApiState, options: Options) {
  const requests: string[] = [];
  await page.route('**/auth/mfa', async (route) => { await json(route, 200, { assuranceLevel: 'AAL2', factors: [] }); });
  await page.route('**/v1/**', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    const path = new URL(route.request().url()).pathname;
    if (path === '/v1/me') { await json(route, 200, { accountId: ACCOUNT, memberships: [], tenantModules: [], partnerContexts: options.contexts }); return; }
    if (path === '/v1/platform/session') { await json(route, 403, { code: 'PLATFORM_ACCESS_DENIED', status: 403 }); return; }
    if (path.startsWith('/v1/partners/')) {
      requests.push(path);
      // Flag desligada: a rota não existe no servidor.
      if (!options.partnersOn) { await json(route, 404, { status: 404, error: 'Not Found' }); return; }
      if (path.endsWith('/team')) {
        await json(route, 200, { partnerId: PARTNER, members: [{ id: OPERATOR, partnerId: PARTNER, accountId: '10000000-0000-4000-8000-0000000000b3',
          role: 'REMOTE_OPERATOR', displayName: 'Operador Sintético', status: 'ACTIVE', version: 0, createdAt: '2026-10-30T12:00:00Z', revokedAt: null, revokeReason: null }] });
        return;
      }
    }
    if (path.startsWith(`/v1/tenants/${TENANT_A}/security-log`)) {
      requests.push(path);
      if (!options.securityLogOn) { await json(route, 404, { status: 404, error: 'Not Found' }); return; }
      await json(route, 200, { events: [{ eventId: '90000000-0000-4000-8000-000000000001', seq: 1, occurredAt: '2026-10-31T12:00:00Z', recordedAt: '2026-10-31T12:00:00Z',
        clockSkewMs: null, eventType: 'ACCESS_ENTRY', severity: 'INFO', source: 'CONCIERGE', partnerId: null, actorAccountId: null, actorRole: 'CONCIERGE',
        subjectType: null, subjectId: null, unitId: null, pointId: null, deviceId: null, reason: null, evidenceRef: null, correlationId: null, traceId: null,
        correctsId: null, details: {}, prevHash: '0'.repeat(64), hash: '1'.padStart(64, '0') }], nextBeforeSeq: null });
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
