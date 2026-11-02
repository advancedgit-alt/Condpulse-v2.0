import { expect, test } from '@playwright/test';
import { TENANT_A, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';

test('gestão configura horário local e desliga alertas sem prometer bloqueio físico', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const kinds = ['VISITOR', 'SERVICE_PROVIDER', 'DELIVERY', 'CONSTRUCTION', 'EVENT_GUEST'];
  let rules = kinds.map((kind) => ({ kind, maxMinutes: 480, l1AfterMinutes: 15, l2AfterMinutes: 60,
    source: 'PLATFORM_DEFAULT', version: null as number | null, enabled: true, startsLocal: null as string | null, endsLocal: null as string | null }));
  const sent: unknown[] = [];
  await page.route(`**/v1/tenants/${TENANT_A}/stay-rules**`, async (route) => {
    if (route.request().method() === 'PUT') {
      const body = route.request().postDataJSON(); sent.push(body);
      rules = rules.map((rule) => rule.kind === 'SERVICE_PROVIDER' ? { ...rule, ...body, source: 'TENANT_RULE',
        version: (rule.version ?? -1) + 1 } : rule);
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ rules }) });
  });
  await signIn(page); await page.goto(`/c/${TENANT_A}/gestao/permanencias`);
  await expect(page.getByRole('heading', { name: 'Horários e alertas' })).toBeVisible();
  const form = page.getByRole('form', { name: 'Regra de permanência: Prestador', exact: true });
  await form.getByRole('button', { name: 'Preencher sugestão 08h–17h, +30 e +60 minutos' }).click();
  await expect(form.getByLabel('Entrada a partir de')).toHaveValue('08:00');
  await expect(form.getByLabel('Saída até')).toHaveValue('17:00');
  await form.getByLabel('Segunda escalada após o prazo (minutos)').fill('30');
  await expect(form.getByRole('button', { name: 'Salvar regra: Prestador', exact: true })).toBeDisabled();
  await form.getByLabel('Segunda escalada após o prazo (minutos)').fill('60');
  await form.getByRole('button', { name: 'Salvar regra: Prestador', exact: true }).click();
  await expect.poll(() => sent.length).toBe(1);
  await form.getByLabel('Controle de permanência ativo').uncheck();
  await form.getByRole('button', { name: 'Salvar regra: Prestador', exact: true }).click();
  await expect.poll(() => sent.length).toBe(2);
  expect(sent[0]).toEqual({ maxMinutes: 540, l1AfterMinutes: 30, l2AfterMinutes: 60, version: null,
    enabled: true, scheduleEnabled: true, startsLocal: '08:00', endsLocal: '17:00' });
  expect(sent[1]).toEqual({ maxMinutes: 540, l1AfterMinutes: 30, l2AfterMinutes: 60, version: 0,
    enabled: false, scheduleEnabled: true, startsLocal: '08:00', endsLocal: '17:00' });
  await expect(form.getByLabel('Controle de permanência ativo')).not.toBeChecked();
  await expect(page.getByText(/Entrada e saída continuam registradas com o controle desligado/)).toBeVisible();
});
