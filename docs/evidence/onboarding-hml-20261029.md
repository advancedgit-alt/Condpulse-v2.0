# Evidência do onboarding de homologação — 29/10/2026

Projeto Supabase: `rpnrqfrfwywglvyykgrg` (homologação). Aprovação explícita do responsável, nesta data, para
criar o "Condomínio Teste CondPulse (homologação)" com a unidade 101, o salão de festas e a conta aprovada como
gestão e morador. A identificação da conta fica fora do Git.

## Execução

- Source revisado: `519d895c455523021c67a399a5355055f9113475` (head da branch principal no momento).
- SHA256 dos bytes do manifesto privado: `19d663b718816fbe682bf6bcf0a74491da34c4ba920719255e838dd5341ed681`,
  o mesmo da inspeção anterior [40877233560](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/40877233560).
- [Inspeção 40917252737](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/40917252737): gate e operação
  com sucesso. O wrapper só termina com sucesso na inspeção com `eligible=true`, códigos vazios, contagens válidas e
  `writeOutcome=NOT_ATTEMPTED`.
- [Apply 40917560570](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/40917560570), com
  `maintenance_confirmed=true` e ACK `rpnrqfrfwywglvyykgrg:519d895…:19d663b7…:932b9741…`: gate e operação com
  sucesso às 17:04:13Z. O wrapper só termina com sucesso no apply com `applied=true`, `writeOutcome=COMMITTED`,
  códigos vazios e fingerprint igual ao do ACK.
- Antes do apply: nenhuma outra execução em andamento ou na fila; a base não tinha condomínio, unidade, vínculo,
  área, papel de plataforma, pedido nem evento no outbox.

## Leitura direta do banco (17:04Z)

- `platform.tenants`: um condomínio, "Condomínio Teste CondPulse (homologação)", `America/Sao_Paulo`, ativo,
  criado às 17:04:06Z.
- `cp.units`: unidade `101`, ativa, sem bloco.
- `cp.memberships`: dois vínculos ativos da mesma conta aprovada — `MANAGER` (sem unidade) e `RESIDENT` (unidade
  101) —, ambos de 29/10/2026 00h a 28/11/2026 00h (America/Sao_Paulo).
- `cp.amenities`: "Salão de festas", capacidade 20, sem aprovação, sem taxa, intervalo de 30 min, ativa; política
  das 08h às 22h todos os dias, até 240 min, antecedência de 60 min a 30 dias, uma reserva ativa por unidade e
  cancelamento até 60 min antes.
- `platform.onboarding_runs`: um recibo do login `cp_onboarding_hml` com contagens 1 conta, 1 unidade, 2 vínculos e
  1 área. O fingerprint recalculado a partir do recibo (projeto, login e hash canônico do manifesto) é
  `932b97417096e4a91e274d02e3255d161f8d81488bdc2cdd995d18da79401f39`, igual ao da inspeção aprovada.
- `platform.platform_roles`: nenhuma linha. Nenhum papel de plataforma foi concedido.

## Limites

O cadastro não comprova login, MFA da gestão, jornadas autenticadas, capacidade ou aceite de produto. O log de
execução do Actions não foi lido diretamente (o download de logs não está disponível neste ambiente); o resultado
vem da regra de saída do wrapper e da leitura do banco acima.

Nenhum segredo ou dado pessoal está registrado neste arquivo.
