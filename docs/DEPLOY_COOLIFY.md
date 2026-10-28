# Pacote de implantação CondPulse no Coolify

Este pacote prepara uma release revisável. Não provisiona host, domínio, registro,
cofre ou credenciais; não representa publicação nem homologação. O alvo é um host
CondPulse exclusivo em São Paulo. Nenhum recurso ou segredo da Advanced é utilizado.

## Arquivos e separação

| Arquivo | Finalidade | Identidade de banco |
| --- | --- | --- |
| infra/compose.app.yaml | Web e API, sem build no host | Login runtime associado somente a cp_api |
| infra/compose.migrate.yaml | Job manual separado da aplicação | Identidade DDL revisada |
| infra/app.env.example | Inventário de variáveis da aplicação | Placeholders inertes |
| infra/migrate.env.example | Inventário exclusivo do job | Placeholders inertes |

As duas imagens da aplicação são promovidas por referência `@sha256:`. O migrador usa
o mesmo digest da API da release. Não se usa `latest`, tag como identidade de release,
`build:` no host ou montagem do Docker socket. O worker não é iniciado: o consumidor
durável ainda não foi implementado.

A API escuta na rede `backend`, exclusiva da stack, com DNS `api` e porta interna8080.
Essa rede permite saída para Auth e PostgreSQL gerenciados; não publica portas no host.
A web participa também da rede externa do proxy. Somente o serviço web recebe domínio.
O migrador usa outro projeto Compose, possui sua própria rede com saída e não participa
da rede do proxy. O nome lógico `backend` não torna a rede externa ou compartilhada:
o nome físico inclui o projeto Compose de cada execução.

## Preparação do ambiente

1. Confirmar host dedicado, região São Paulo, capacidade e orçamento aprovado. Configurar
   firewall, acesso administrativo individual, cofre e monitoramento do host.
2. Definir projeto/ambiente CondPulse, domínio HTTPS e rede dedicada do proxy com prefixo
   `condpulse-` ou `condpulse_`, configurando o proxy desse host para participar dela.
   A rede externa precisa existir e o proxy deve estar efetivamente conectado:
   `external: true` apenas referencia a rede, não a cria nem conecta o proxy.
   Não reutilizar uma rede genérica ou da Advanced. Homologação e
   produção usam projetos Auth/banco/buckets/credenciais separados.
3. Construir as imagens fora do host usando os Dockerfiles revisados, validar a release
   no CI, publicar no registro escolhido e registrar commit, plataforma e ambos digests.
   Credenciais de pull ficam somente no gerenciador/cofre, sem aparecer no Git.
4. Provisionar um login runtime com prefixo cp_runtime_ no grupo cp_api, sem DDL, SUPERUSER ou BYPASSRLS. Separar
   a identidade DDL e seu acesso. Atender o ownership definido na ADR-0003.
5. Obter a CA PostgreSQL do próprio ambiente por canal confiável e conferir fingerprint.
   Disponibilizar arquivo regular legível pelo UID10001 da API/migrador no caminho absoluto
   CP_DB_CA_FILE. O bind é somente leitura e não cria caminho ausente automaticamente.
6. Configurar a conexão JDBC direta ou pooler de sessão após o ensaio de rede. Exigir
   hostname, `sslmode=verify-full` e `sslrootcert=/run/secrets/db-ca.crt`. Senha/usuário
   ficam em variáveis próprias, sem inserção na URL. Não adotar pooler transacional
   antes de comprovar compatibilidade de contexto, transações e prepared statements.

O cofre fornece os valores às variáveis do recurso Coolify ou a arquivos temporários
de acesso restrito no host. Arquivos reais ficam fora do repositório e são removidos
após o uso conforme a política do ambiente. Não colar senhas no chat, imprimir
variáveis, usar `set -x`, executar o `.env` com `source` ou salvar o Compose expandido.
Os exemplos contêm marcadores inválidos intencionais, não credenciais utilizáveis.

## Validação antes da operação

Usar Docker Compose5.2.0, a versão revisada neste pacote (`docker compose version --short`).
O CI instala o binário oficial Linux por versão e confere seu SHA256. Não substituir
pela versão embutida no runner. A representação JSON de `bind.create_host_path=false`
mudou entre versões: o preflight exige o false explícito e rejeita campo ausente,
pois versões antigas/novas podem omitir valores com semânticas diferentes. Atualizações
de Compose precisam repetir os testes antes de mudar esta versão de referência.

Executar a partir da raiz, em shell com falha de pipeline habilitada. Os caminhos abaixo
são locais ao operador/cofre; não são arquivos entregues pelo projeto.

```sh
set -o pipefail
docker compose --project-name condpulse-app --env-file /run/condpulse/app.env -f infra/compose.app.yaml config --format json | node infra/validate-release.mjs --app
docker compose --project-name condpulse-migrate --env-file /run/condpulse/migrate.env -f infra/compose.migrate.yaml config --format json | node infra/validate-release.mjs --migrate
```

A configuração expandida contém segredos e passa somente pelo pipe ao validador. Não
usar `tee`, upload de artefato ou saída do `config` em logs. O validador verifica
estrutura/configuração e não testa rede, CA, privilégios no banco nem homologação.
Conferir também que as identidades runtime/DDL são diferentes e que o digest API
é idêntico nas duas configurações. Confirmar o mesmo project-ref e banco de destino
na aplicação e no migrador: as URLs podem diferir entre conexão direta e pooler,
mas ambas precisam alcançar o mesmo banco do mesmo projeto/ambiente CondPulse.
A validação isolada não compara ambientes nem comprova esse destino por rede.

O validador se aplica ao manifesto de origem renderizado, antes da transformação
do Coolify. Conferir manualmente a definição gerada e as mudanças da plataforma;
labels extras geradas pelo Coolify seriam rejeitadas pela allowlist estrita do
validador, que não serve como atestado automático dessa definição transformada.

## Migração como job manual

O perfil `migrate` valida/aplica Flyway e termina. A API não recebe essas credenciais
nem inicia migrations. O arquivo do migrador não deve ser incluído na aplicação Coolify,
nem acionado a cada reinício ou por merge automático.

No projeto remoto atual, V001/V002 foram aplicadas pelo bootstrap atestado. Antes do
primeiro job, conferir manifesto, histórico nativo, estrutura, RLS, ACL e ownership;
executar o comando oficial Flyway `baseline` na versão002 pela identidade DDL, conforme
ADR-0003. O executor isolado, seu modo de inspeção e a manutenção exclusiva estão em
BASELINE_FLYWAY.md e infra/compose.baseline.yaml. O baseline remoto continua pendente;
o alvo inicial exige o ator postgres do atestado. Não fabricar
`flyway_schema_history`, usar `baseline-on-migrate` ou avançar o marcador para003.

Somente após baseline oficial atestado, registrar aprovação/backup compatível e executar
o job que faz validate/migrate paraV003. Não executar o comando abaixo antes desses gates:

```sh
docker compose --project-name condpulse-migrate --env-file /run/condpulse/migrate.env -f infra/compose.migrate.yaml run --rm --no-deps migrate
```

Registrar código de saída, versões/checksums aplicados e validação posterior, sem segredos.
Falha interrompe a promoção. Rollback da aplicação usa digest anterior compatível; não há
rollback destrutivo automático de schema.

## Aplicação no Coolify

1. Criar recurso de aplicação Git no projeto e ambiente CondPulse, apontando para a
   revisão aprovada deste repositório. Selecionar Docker Compose, diretório-base raiz
   e arquivo `infra/compose.app.yaml`, no modo normal do Coolify. Não selecionar Raw:
   o manifesto não contém as labels de roteamento que esse modo exigiria administrar.
   As imagens já existem no registro por digest.
2. Desligar deploy automático/previews/promover por push nesta etapa. A publicação
   depende dos gates de migração, configuração e homologação.
3. Preencher somente o inventário `app.env.example` pelo cofre/variáveis do recurso.
   Não importar variáveis CP_MIGRATION_* nem a senha DDL. Conferir CA e CP_PROXY_NETWORK.
4. Configurar domínio apenas para `web`, usando `https://DOMINIO:8080` na indicação do
   Coolify: o sufixo aponta à porta interna; o navegador usa HTTPS normal. Não configurar
   domínio para `api`, nem criar mapeamento de porta8080 no host.
5. Manter `Connect to predefined network` desligado. Conferir a definição efetiva
   que o Coolify gerar, incluindo aliases DNS e redes adicionadas. API somente na rede backend;
   web nas redes backend/proxy; nenhum acesso de rede/secreto da Advanced. Se a plataforma
   adicionar a API à rede do proxy, ajustar o recurso antes de publicar. Conferir DNS
   `api`, portas, mounts, usuário, readonly, capabilities, limites e healthchecks.
6. Validar e promover manualmente a configuração aprovada. API exige readiness
   `/actuator/health/readiness`, incluindo banco; web espera API saudável e verifica
   `/healthz`. A resposta da web comprova assets, não login ou estado do banco.
7. Conferir certificado/domínio e proxy na mesma origem: `/auth` e `/v1` chegam à API
   privada. Conferir `/v1/me` pela origem pública e o encaminhamento após a recriação
   da API, incluindo preservação do caminho e da query e resolução do novo IP pelo
   proxy web. Confirmar que a API e o banco não ganharam portas públicas.

Limites iniciais: API2GiB/2CPU/pids256/pool8, web256MiB/0,5CPU/pids128;
migrador2GiB/2CPU/pids256/pool2 enquanto seu job roda. São limites de proteção a ensaiar,
não capacidade garantida. Somar conexões e recursos de outros processos antes de operar.
Os containers usam usuário sem root, filesystem somente leitura, `/tmp` em tmpfs,
capabilities removidas, no-new-privileges, shutdown e logs locais com rotação limitada.
Esses logs não substituem monitoramento nem backup e não devem conter credenciais.

## Gates de liberação e estado atual

CP_BFF_ENABLED e CP_RESERVATIONS_ENABLED estão fixos em false no manifesto. Configurar
variável externa com true não os habilita. Uma alteração revisada posterior deve fornecer
chave de cifra/publicável pelo cofre e habilitar somente após testes reais de Auth,
CSRF/Origin, refresh/logout, expiração, falha de rede, limites de tentativas, revogação,
reserva/cancelamento e navegação móvel. A stack atual é preparação operacional.

Ainda pendentes: host/domínio HTTPS/cofre exclusivos, login runtime e identidade DDL,
baseline002 oficial/V003, ensaio TLS/pooler, Auth real, SMTP, segundo ambiente isolado,
rate limiting, PITR/restore e os demais critérios de lançamento do plano. Não houve deploy,
contratação ou alteração da Advanced por estes arquivos. Quando o ambiente existir,
registrar responsável, commit/digests, migration, resultado, rollback e observação real.

Referências oficiais consultadas para a configuração:

- https://docs.docker.com/reference/compose-file/services/
- https://docs.docker.com/reference/compose-file/networks/
- https://coolify.io/docs/applications/builds/docker-compose
- https://github.com/docker/compose/releases/tag/v5.2.0
- docs/adr/0003-bootstrap-supabase.md
