# Ativação de rotinas na HML — 01/11/2026

Autorização da rodada: correções, testes completos e dados sintéticos preservados. Sem alteração de recursos Advanced ou de produção operacional.

## Base conferida

- PR #173 integrado: f7928e0605883be45181ef528c43e1a7976f0a86; CI do head a9d6798 (41322696996), cinco jobs success e revisões de código/segurança concluídas.
- Login do painel Supabase concluído pelo responsável. Backup físico de 01/11/2026 07:52:12 UTC (04:52:12 America/Sao_Paulo) visível em scheduled backups do projeto rpnrqfrfwywglvyykgrg. É anterior à carga de teste da tarde e não inclui bytes do Storage; restore/RPO não estão homologados.
- V055 aplicada e validada pelo workflow 41329884896 no SHA f7928e0: migrationsExecuted=1. SELECT posterior confirmou success, cp.stays e cp.stay_rules com ENABLE/FORCE RLS, reserva e pedido da carga preservados.
- Demo: inspect 41329994162 e apply 41330326771, profile=demo. Changed=[CP_DEMO_ENABLED], valor true lido de volta, secrets={} (não gera/altera segredos). Essa é a configuração da Vercel; copiar para a Fly e publicar ainda exige recibos próprios.

## Mudança do worker

Fila de espera (CP-040) e alertas de permanência (V055/CPX-016) passam a true em fly.worker.toml, somente na HML. O outbox permanece true. Pool1 e intervalos60000ms mantidos; reconciliação de hardware continua false. Nenhum segredo ou login muda por esta alteração do manifesto.

A V055 já existe antes desse deploy; o agendador não deve consultar tabela ausente. A fila é funcionalidade já exibida no portal, mas o worker ainda não fazia as ofertas. Os níveis de permanência dependem das regras/padrões registrados em PERMANENCIAS.md; valores técnicos ainda precisam de aceite do piloto.

## Verificação

`node --test tests/fly-worker.test.mjs`: 14 passaram localmente, incluindo contrato do manifesto e launcher. O CI e a revisão deste PR precisam terminar antes do merge. Depois, publicar API/worker no SHA correspondente, comprovar estabilidade e observar tarefas reais. Não declarar as rotinas executadas apenas porque as flags estão no código.

Atualizar ESTADO_ATUAL.md e registrar os runs finais ao concluir a publicação. Preservar os dados de teste até pedido posterior de limpeza.
