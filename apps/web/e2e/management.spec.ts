import { test, expect } from '@playwright/test';
import { TENANT_A, installSyntheticApi, signIn } from './fixtures';
import { FACTOR, INVITATION_TOKEN, installManagementApi } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

test('gestor AAL1 só vê a exigência do segundo fator e entra na gestão depois de verificar', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  const state = await installManagementApi(page);
  await signIn(page);
  await page.getByRole('link', { name: /Abrir gestão · Condomínio Sintético A/ }).click();
  await expect(page.getByRole('heading', { name: 'Confirme sua identidade para usar a gestão.' })).toBeVisible();
  await expect(page.getByText('Pedidos aguardando decisão')).toHaveCount(0);
  expect(state.managementRequests).toHaveLength(0);
  await page.getByRole('button', { name: 'Cadastrar autenticador' }).click();
  await expect(page.getByText('JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP')).toBeVisible();
  await expect(page.getByRole('img', { name: /Código QR/ })).toBeVisible();
  await page.getByLabel('Código de 6 dígitos').fill('000000');
  await page.getByRole('button', { name: 'Verificar código' }).click();
  await expect(page.getByText('Código não aceito. Confira o aplicativo autenticador e digite o código atual.')).toBeVisible();
  await page.getByLabel('Código de 6 dígitos').fill('123456');
  await page.getByRole('button', { name: 'Verificar código' }).click();
  await expect(page.getByRole('heading', { name: 'Pedidos aguardando decisão' })).toBeVisible();
  expect(state.verifyBodies).toEqual([{ factorId: FACTOR, code: '000000' }, { factorId: FACTOR, code: '123456' }]);
  expect(state.enrollRequests).toBe(1);
  await expect(page.getByText('JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP')).toHaveCount(0);
});

test('mesma decisão já gravada por outro gestor não aparece como sucesso desta ação', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  const state = await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  state.decisionAlreadyApplied = true;
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/pendencias`);
  const item = page.locator('.management-item').filter({ hasText: 'Área Sintética A' });
  await item.getByRole('button', { name: 'Rejeitar' }).click();
  await item.getByLabel('Motivo da rejeição').fill('Motivo deste gestor');
  await item.getByRole('button', { name: 'Confirmar rejeição' }).click();
  await expect(page.getByText('Este pedido já estava', { exact: false })).toBeVisible();
  await expect(page.getByText('o seu motivo não foi registrado', { exact: false })).toBeVisible();
  await expect(page.getByText('Pedido rejeitado. O período foi liberado.')).toHaveCount(0);
});

test('rejeição de pedido exige motivo e a decisão sai uma única vez', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  const state = await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/pendencias`);
  const item = page.locator('.management-item').filter({ hasText: 'Área Sintética A' });
  await expect(item.getByText('Aguardando decisão')).toBeVisible();
  await expect(item.getByText(/Morador Sintético/)).toBeVisible();
  await item.getByRole('button', { name: 'Rejeitar' }).click();
  const confirm = item.getByRole('button', { name: 'Confirmar rejeição' });
  await expect(confirm).toBeDisabled();
  await item.getByLabel('Motivo da rejeição').fill('Área em manutenção');
  await confirm.click();
  await expect(page.getByText('Pedido rejeitado. O período foi liberado.')).toBeVisible();
  expect(state.decisions).toEqual([{ decision: 'REJECT', expectedVersion: 0, reason: 'Área em manutenção' }]);
  await expect(page.getByText('Nenhum pedido de reserva aguardando decisão.')).toBeVisible();
});

test('vínculo pendente é aprovado pela gestão', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  const state = await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/pendencias`);
  await page.getByRole('button', { name: 'Aprovar vínculo de Pessoa Convidada Sintética' }).click();
  await page.getByRole('button', { name: 'Confirmar aprovação' }).click();
  await expect(page.getByText('Pessoa Convidada Sintética: vínculo aprovado.')).toBeVisible();
  expect(state.membershipDecisions).toEqual([{ decision: 'APPROVE', expectedVersion: 0, reason: null }]);
});

test('link do convite aparece uma vez e o convidado aceita sem expor o token na barra', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  const state = await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/pessoas`);
  await page.getByLabel('Unidade', { exact: true }).selectOption({ label: '101 — Sintética A' });
  await page.getByLabel('Nome', { exact: true }).fill('Pessoa Convidada Sintética');
  await page.getByLabel('E-mail da conta').fill('convidado-sintetico@example.invalid');
  await page.getByRole('button', { name: 'Criar convite' }).click();
  await expect(page.getByText(/Copie o link agora/)).toBeVisible();
  await expect(page.getByText(`/convite#token=${INVITATION_TOKEN}`)).toBeVisible();
  expect(state.invitationBodies).toEqual([{ role: 'RESIDENT', unitId: '20000000-0000-4000-8000-000000000001',
    displayName: 'Pessoa Convidada Sintética', email: 'convidado-sintetico@example.invalid', validDays: 7 }]);
  await page.getByRole('button', { name: 'Ocultar link' }).click();
  await expect(page.getByText(`/convite#token=${INVITATION_TOKEN}`)).toHaveCount(0);

  await page.goto(`/convite#token=${INVITATION_TOKEN}`);
  await expect(page.getByRole('heading', { name: 'Aceitar convite do condomínio?' })).toBeVisible();
  expect(new URL(page.url()).hash).toBe('');
  await page.getByRole('button', { name: 'Aceitar convite' }).click();
  await expect(page.getByRole('heading', { name: 'Pedido de vínculo enviado.' })).toBeVisible();
  expect(state.acceptBodies).toEqual([{ token: INVITATION_TOKEN }]);
});

test('morador sem vínculo de gestão não abre a gestão nem consulta a API', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await signIn(page);
  await expect(page.getByRole('link', { name: /Abrir gestão/ })).toHaveCount(0);
  await page.goto(`/gestao/${TENANT_A}/pendencias`);
  await expect(page.getByText('Gestão indisponível')).toBeVisible();
  expect(state.managementRequests).toHaveLength(0);
});

test('conta só de gestão abre a gestão sem formulário de unidade residencial', async ({ page }) => {
  await installSyntheticApi(page, { managerOnly: true });
  await installManagementApi(page);
  const unitRequests: string[] = [];
  page.on('request', request => { if (new URL(request.url()).pathname.endsWith('/units')) unitRequests.push(request.url()); });
  await signIn(page);
  await expect(page.getByRole('link', { name: /Abrir gestão · Condomínio Sintético A/ })).toBeVisible();
  await expect(page.getByText('Esta conta não tem unidade vinculada como morador. Use o acesso de gestão acima.')).toBeVisible();
  await expect(page.getByLabel('Condomínio', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Nenhum vínculo disponível')).toHaveCount(0);
  expect(unitRequests).toEqual([]);
});
