# Implantação do worker contínuo — preparação (não executada)

O worker (perfil `worker`, mesma imagem da API) processa o outbox (caixa interna, CP-034/CP-035). Hoje **nenhum worker
está implantado** em homologação ou produção; as passadas ficam desligadas por padrão (`CP_WORKER_OUTBOX_ENABLED=false`, `CP_WORKER_WAITLIST_ENABLED=false` e `CP_WORKER_ACCESS_SYNC_ENABLED=false`).
A passada da fila de espera (CP-040, flag própria) entra neste manifesto quando aquele código estiver na base.

## O que já existe no repositório

- `infra/compose.worker.yaml`: serviço único `worker`, sem porta nem HTTP (healthcheck desligado de forma explícita),
  usuário não root, sistema de arquivos somente leitura, 1 GiB/1 CPU, pool 4, CA PostgreSQL montada somente leitura e
  TLS `verify-full`. A passada fica `false` no manifesto; ligar exige alteração revisada.
- `infra/worker.env.example`: placeholders inertes; o arquivo real fica fora do Git.
- `infra/validate-release.mjs --worker`: valida o JSON normalizado do Compose (imagem por digest, login `cp_worker_*`
  separado do runtime da API e do grupo `cp_worker`, flags desligadas, sem porta). Teste: `tests/release-compose.test.mjs`.
- `infra/operations/worker-login.sql`: cria o login exclusivo como `NOLOGIN`, membro somente de `cp_worker`, limite de
  6 conexões; repetição só confere. **Não executado.**
- A role `cp_worker` (V002/V015/V023) só tem os privilégios do worker; a `DatabaseRoleGuard` recusa iniciar com login
  privilegiado.

## Sequência para implantar (cada passo com OK do responsável)

1. **Hospedagem**: o worker é um processo contínuo; a Vercel não roda processo contínuo. Opções: host dedicado com
   Coolify/Compose (ADR-0005 mantém Coolify como alternativa) ou outro provedor de contêiner. Escolha e custo são do
   responsável.
2. **Login**: executar `infra/operations/worker-login.sql` como `postgres` no projeto escolhido; definir a senha com
   `\password` no psql e guardá-la no cofre; `ALTER ROLE <login> LOGIN`. Conferir o orçamento de conexões (ADR-0006).
3. **Configuração**: preencher o arquivo de ambiente fora do Git (imagem aprovada por digest, URL JDBC com
   `verify-full`, login e senha do cofre, caminho da CA). `docker compose -f infra/compose.worker.yaml config --format json
   | node infra/validate-release.mjs --worker` precisa responder `RELEASE_VALID 0`.
4. **Subir com as passadas desligadas** e conferir no log a inicialização sem erro da guarda de role.
5. **Ligar a passada** (alteração revisada do manifesto) `CP_WORKER_OUTBOX_ENABLED`, observando a fila do outbox
   (pendentes, falhas, arrendamentos vencidos) por consulta somente leitura. A passada da fila de espera
   (`CP_WORKER_WAITLIST_ENABLED`, a cada `CP_WORKER_WAITLIST_MILLIS`, padrão 60 s) é ligada em alteração separada, depois
   de conferir que ofertas vencidas avançam a fila (FILA_ESPERA.md). A reconciliação de credenciais
   (`CP_WORKER_ACCESS_SYNC_ENABLED`, a cada `CP_WORKER_ACCESS_SYNC_MILLIS`, padrão 15 s) só faz sentido com o
   adaptador do parceiro físico (CP-044); sem ele, nada é enviado e a pendência continua visível (CREDENCIAIS_ACESSO.md).
6. Registrar runs, horários e evidências em `docs/STATUS.md`.

## Bloqueios

- BLOQUEADO: escolha e custo da hospedagem do worker contínuo — responsável — decisão de infraestrutura.
- BLOQUEADO: execução do `worker-login.sql` e senha no cofre — responsável (ou execução autorizada por escrito) —
  psql administrativo do projeto e cofre.
