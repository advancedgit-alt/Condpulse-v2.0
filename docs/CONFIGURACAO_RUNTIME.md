# Configuração manual da credencial runtime

O pacote prepara uma operação segregada para o PostgreSQL e o projeto Vercel
CondPulse. O código e seus testes não comprovam que um provisionamento remoto
foi executado. Não cria contas Auth, condomínio, unidade, vínculo ou área; não
executa onboarding, migrations, implantação ou redeploy.

O alvo é fixo: Supabase `rpnrqfrfwywglvyykgrg`, pooler de sessão em São Paulo, e
Vercel projeto `prj_IeUa4Zjg1IwaPqJQwv525H14I837`, time
`team_6fzA029jxhDb1LpnrnphWGQ0`. Não recebe projeto, time, URL ou usuário
administrativo selecionável por input. Nenhum recurso da Advanced participa.

## Cofre e autorização

O workflow `.github/workflows/runtime-configuration.yml` utiliza o Environment
existente `condpulse-database-operations`, os mesmos controles de repositório,
branch e SHA do executor de banco e a mesma concurrency sem cancelamento. Isso
serializa este fluxo com baseline/migrate, mas não impede alterações por uma
ferramenta externa que não participe do controle.

O responsável fornece os valores somente como Environment Secrets:

| Secret | Uso |
| --- | --- |
| `CP_DATABASE_OPERATIONS_PASSWORD` | Senha já existente da identidade administrativa do banco CondPulse; não é redefinida |
| `CP_VERCEL_TOKEN` | PAT Vercel do responsável, autorizado para a equipe que contém o projeto CondPulse |

O PAT pode possuir acesso a outros projetos da equipe. O guard de destino limita
o comportamento deste executor; não transforma o PAT em uma credencial limitada
ao projeto. Não reutilizar credenciais da Advanced nem colocar estes valores em
inputs, repository/organization secrets, Vercel, Git, chat, logs ou artefatos.
Conferir a política efetiva do Environment antes de preencher os secrets.

Build Maven, extração do JAR, hashes de artefato/fontes, testes inertes e compilação
do helper ocorrem antes do passo que recebe os secrets. O launcher passa apenas
uma allowlist de variáveis ao Java. Senhas e PAT não entram em argv ou arquivos;
respostas e erros do helper devem continuar sanitizados.

## Inspeção e aplicação

1. Integrar o código revisado com CI aprovado e obter seu SHA completo. O workflow
   manual precisa estar na branch padrão para aparecer em Actions. A branch
   admitida é `ccr-5e725112-3x8hyg`; outra referência ou SHA divergente aborta.
2. Definir um UUID canônico explícito para `generation`. Ele identifica o login
   novo `cp_runtime_` seguido dos 32 caracteres hexadecimais desse UUID.
3. Executar Actions → CondPulse runtime configuration (manual), com
   `operation=inspect`, `generation` e `expected_commit`. Deixar `runtime_ack`
   vazio. `inspect` é o padrão e não autoriza escrita.
4. Conferir o resultado sanitizado e a fingerprint. Revisar estado de migrations,
   principal, destino e configurações antes de aprovar a aplicação. A fingerprint
   não é uma senha e não substitui a revisão da operação.
5. Para aplicar, coordenar a janela exclusiva e executar novo dispatch com o
   mesmo UUID e SHA, `operation=apply`, `maintenance_confirmed=true` e ACK exato:
   `rpnrqfrfwywglvyykgrg:runtime:UUID:SHA:FINGERPRINT64`. Copiar a fingerprint da
   inspeção; o workflow não gera o reconhecimento automaticamente.
6. Registrar run, SHA, SHA256 do JAR, fingerprint pública da CA e resultado. Uma
   aplicação bem-sucedida ainda não é um teste de login ou aceite da API.

O helper Java isolado `br.com.condpulse.bootstrap.RuntimeConfiguration` é
compilado das fontes revisadas em `infra/scripts/runtime-configuration`. Não
inicia Spring. Recebe somente o destino JDBC fixo, as credenciais operacionais e
os controles da operação. Não pode ser escolhido outro comando Java por input.

O runtime recebe sua própria identidade `cp_runtime_*`, com associação apenas a
`cp_api`, sem SUPERUSER/BYPASSRLS/DDL ou participação em `cp_onboarding`/`cp_worker`.
Os grants de aplicação permanecem controlados pelas migrations. Não conceder
`GRANT ALL` nem copiar privilégios de uma identidade administrativa.

Esta entrega configura somente a conexão runtime e mantém BFF/reservas desligados.
Não gera ou rotaciona chaves AES/HMAC, não recebe chave Auth Admin e não altera
credenciais de onboarding. A senha nova só deve ser entregue ao projeto Vercel
fixo, no ambiente apropriado; nunca ao bundle web ou a previews indiscriminados.
Não há readback de valores secretos para logs, confirmação em chat ou artefatos.

O helper usa a API oficial `api.vercel.com`, com TLS verificado, sem redirects e
com limites de tempo e corpo. A coleção de variáveis é consultada com
`decrypt=false`. Para as sete configurações públicas legadas criadas na UI
(`PORT`, pool, issuer, JWKS, origem e duas flags), um valor `encrypted` não é
tratado como texto legível por suposição: somente seus IDs conhecidos podem ser
consultados individualmente no endpoint oficial de valor decifrado. A resposta
deve confirmar nome, ID, tipo não secreto, Production, revisão e `decrypted=true`.
Senha, chaves BFF e nomes desconhecidos nunca entram nessa allowlist. As flags
existentes precisam ser comprovadamente `false` antes de criar a identidade.

As dez configurações geridas por este fluxo são estritamente públicas e gravadas
como `plain/Config`, incluindo usuário, URL JDBC sem senha e CA pública. Somente
`DATABASE_PASSWORD` é `sensitive/Secret`. Todas ficam apenas em Production. O
helper confere os valores públicos e os metadados da senha após o POST, sem ler a
senha de volta. Variáveis de projeto podem ser disponibilizadas também ao build;
não há promessa de isolamento por Service. O build revisado não deve imprimir,
incorporar ou expor variáveis servidoras no bundle ou na imagem.

## TLS, falha e limites

A CA pública vem do mesmo endpoint HTTPS fixo revisado para o executor de banco.
O download tem timeout e tamanho limitado; o certificado deve ser uma CA válida,
com assinatura verificada e pelo menos um dia restante. Seu fingerprint público
é registrado, mas não está pinado a uma versão anterior. O arquivo é instalado
somente leitura em `/run/secrets/db-ca.crt` e removido ao terminar.

A atualização PostgreSQL e a chamada Vercel não formam uma única transação.
Timeout, encerramento ou falha perto de uma escrita podem resultar em estado
parcial ou desconhecido. Suspender a promoção e reconciliar role, geração e
metadados do projeto antes de decidir uma nova operação. Não deduzir ausência de
efeito por código de saída, repetir `apply` cegamente ou gerar novo UUID para
ocultar o resultado anterior.

| Estado | Conduta |
| --- | --- |
| `ABSENT` | A geração pode ser aplicada apenas com ACK da inspeção atual e namespace livre |
| `STAGED` | Senha provisória expira em dez minutos; interromper promoção e inspecionar a mesma geração |
| `DISABLED` | O executor confirmou `NOLOGIN` e `PASSWORD NULL`; obter nova fingerprint e aplicar novamente a mesma geração/SHA |
| `READY` | Login, privilégios e configuração foram conferidos; replay não troca senha, não chama POST e não cria outra role |
| `UNKNOWN` | A desativação não pôde ser confirmada; reconciliar antes de qualquer nova escrita |

O comentário da role e das variáveis vincula projeto, UUID e SHA. Um nome
preexistente sem esse marcador, outra geração ou metadados divergentes abortam;
não são adotados. Recuperação exige a mesma geração e revisão, novo `inspect`
e ACK atual. Escrita parcial na Vercel pode deixar variáveis criadas: elas só
podem ser substituídas se pertencerem a essa operação. Não se promete rollback
atômico entre os provedores. Uma alteração de fonte durante a recuperação requer
procedimento específico revisado; trocar o SHA não contorna o marcador.

`READY` remove a expiração provisória da senha. Isso não implementa rotação
periódica. `VALID UNTIL` expira a autenticação por senha; `NOLOGIN` bloqueia novos
logins e não encerra conexões já abertas. A conexão de prova é fechada antes do
POST e nenhum deployment é criado neste fluxo.

Não se alteram ou removem logins antigos automaticamente. Não se redefine a senha
administrativa, não se habilita BFF, não se rotacionam chaves e não se dispara um
redeploy. Qualquer etapa posterior depende de ação e validação próprias. A
atualização de variáveis na Vercel exige um novo deployment para produzir efeito;
este executor não comprova leitura JDBC, readiness, Auth, capacidade ou produção.

Testes: `node --test tests/runtime-configuration-policy.test.mjs tests/runtime-configuration.test.mjs`.
Eles importam o guard e constroem invocações com valores sintéticos; não consultam
GitHub, Supabase ou Vercel. Os testes Java/JDBC/HTTP do helper possuem evidência
separada e não devem ser descritos como execução remota deste procedimento.

Com JAR e JDK25, os testes compilam as mesmas fontes e executam o caminho fechado
do CLI e o protocolo HTTP em loopback. O harness JDBC adicional exige explicitamente
PostgreSQL descartável em loopback, fixture `condpulse_ci` e credencial sintética
de CI. Cria três bancos próprios, usa baseline oficial e migrations revisadas,
prova SCRAM/RLS/replay/recuperação e remove somente seus recursos. Não representa
teste TLS/pooler remoto; o guard de TLS também deve recusar a conexão local sem TLS.

Fontes primárias do contrato:

- https://vercel.com/docs/rest-api/projects/create-one-or-more-environment-variables
- https://vercel.com/docs/rest-api/projects/retrieve-the-environment-variables-of-a-project-by-id-or-name
- https://vercel.com/docs/rest-api/projects/retrieve-the-decrypted-value-of-an-environment-variable-of-a-project-by-id
- https://vercel.com/docs/environment-variables/sensitive-environment-variables
- https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/util/PasswordUtil.java
- https://www.postgresql.org/docs/17/sql-createrole.html
