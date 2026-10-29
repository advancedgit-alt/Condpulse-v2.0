import { test, expect, type Route } from '@playwright/test';
import { installSyntheticApi } from './fixtures';

test.use({ timezoneId: 'UTC' });
const TOKEN = 'Q'.repeat(21) + '_-' + 'w'.repeat(20);
const VIEW = '**/auth/permit-links/view';
const permit = { tenantName: 'Condomínio Sintético A', visitorFirstName: 'Visita', purpose: 'Aniversário', validFrom: '2030-01-01T15:00:00Z',
  validUntil: '2030-01-01T18:00:00Z', timeZone: 'America/Sao_Paulo', state: 'ACTIVE' };
async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: status < 400 ? 'application/json' : 'application/problem+json', headers: { 'Cache-Control': 'no-store' },
    body: JSON.stringify(body) });
}

test('quem recebe o link vê o essencial sem conta; o token sai da barra e só vai no corpo', async ({ page }) => {
  await installSyntheticApi(page);
  const calls: { url: string; body: unknown; csrf: string | undefined }[] = [];
  await page.route(VIEW, async (route) => {
    calls.push({ url: route.request().url(), body: route.request().postDataJSON(), csrf: route.request().headers()['x-csrf-token'] });
    await json(route, 200, permit);
  });
  const requested: string[] = [];
  page.on('request', (request) => requested.push(request.url()));
  await page.goto(`/visita#${TOKEN}`);
  await expect(page.getByRole('heading', { name: 'Olá, Visita.' })).toBeVisible();
  await expect(page.getByText('Condomínio Sintético A')).toBeVisible();
  await expect(page.getByText('Aniversário')).toBeVisible();
  await expect(page.getByText('De 01 de jan. de 2030, 12:00 até 01 de jan. de 2030, 15:00')).toBeVisible();
  await expect(page.getByText('Em vigor agora')).toBeVisible();
  await expect(page.getByText('não substitui a conferência da portaria', { exact: false })).toBeVisible();
  expect(new URL(page.url()).hash).toBe('');
  expect(calls).toHaveLength(1);
  expect(calls[0]!.body).toEqual({ token: TOKEN });
  expect(calls[0]!.csrf).toBeTruthy();
  expect(requested.some((url) => url.includes(TOKEN))).toBe(false);
  // "Ir para o conteúdo" muda o fragmento da página, mas não é outro link: o convite continua na tela.
  await page.keyboard.press('Tab');
  await page.getByRole('link', { name: 'Ir para o conteúdo' }).click();
  await expect(page.getByRole('heading', { name: 'Olá, Visita.' })).toBeVisible();
  expect(calls).toHaveLength(1);
  // Recarregar a aba continua mostrando o convite (token guardado só nesta aba), sem voltar à barra de endereço.
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Olá, Visita.' })).toBeVisible();
  expect(new URL(page.url()).hash).toBe('');
});

test('link revogado, vencido ou desconhecido mostra a mesma mensagem neutra, sem botão de repetir', async ({ page }) => {
  await installSyntheticApi(page);
  await page.route(VIEW, (route) => json(route, 404, { code: 'PERMIT_LINK_UNAVAILABLE' }));
  await page.goto(`/visita#${TOKEN}`);
  await expect(page.getByRole('heading', { name: 'Este convite não está mais disponível.' })).toBeVisible();
  await expect(page.getByText('Peça um novo link a quem convidou.', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tentar novamente' })).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Link de visita indisponível.' })).toBeVisible();
});

test('falha temporária oferece nova tentativa que funciona', async ({ page }) => {
  await installSyntheticApi(page);
  let attempts = 0;
  await page.route(VIEW, async (route) => {
    attempts += 1;
    if (attempts === 1) await json(route, 503, { code: 'PERMIT_LINK_DEPENDENCY_UNAVAILABLE' });
    else await json(route, 200, { ...permit, state: 'SCHEDULED' });
  });
  await page.goto(`/visita#${TOKEN}`);
  await expect(page.getByRole('heading', { name: 'Não foi possível abrir o convite.' })).toBeVisible();
  await page.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(page.getByText('Agendada: ainda não começou')).toBeVisible();
  expect(attempts).toBe(2);
});

test('sem token ou com token malformado não chama o servidor', async ({ page }) => {
  await installSyntheticApi(page);
  let calls = 0;
  await page.route(VIEW, async (route) => { calls += 1; await json(route, 200, permit); });
  await page.goto('/visita');
  await expect(page.getByRole('heading', { name: 'Link de visita indisponível.' })).toBeVisible();
  await page.goto('/visita#curto');
  await expect(page.getByRole('heading', { name: 'Link de visita indisponível.' })).toBeVisible();
  expect(new URL(page.url()).hash).toBe('');
  expect(calls).toBe(0);
});

test('resposta tardia da tentativa manual não sobrescreve outro link aberto depois', async ({ page }) => {
  await installSyntheticApi(page);
  const OTHER = 'R'.repeat(21) + '_-' + 'v'.repeat(20);
  let release!: () => void;
  const hold = new Promise<void>((resolve) => { release = resolve; });
  let attempts = 0;
  await page.route(VIEW, async (route) => {
    attempts += 1;
    const token = (route.request().postDataJSON() as { token: string }).token;
    if (token === TOKEN && attempts === 1) { await json(route, 503, { code: 'PERMIT_LINK_DEPENDENCY_UNAVAILABLE' }); return; }
    if (token === TOKEN) { await hold; await json(route, 200, { ...permit, visitorFirstName: 'Antiga' }); return; }
    await json(route, 200, { ...permit, visitorFirstName: 'Nova' });
  });
  await page.goto(`/visita#${TOKEN}`);
  await page.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect.poll(() => attempts).toBe(2);
  await page.evaluate((next) => { window.location.hash = next; }, OTHER);
  await expect(page.getByRole('heading', { name: 'Olá, Nova.' })).toBeVisible();
  release();
  await page.waitForTimeout(500);
  await expect(page.getByRole('heading', { name: 'Olá, Nova.' })).toBeVisible();
});
