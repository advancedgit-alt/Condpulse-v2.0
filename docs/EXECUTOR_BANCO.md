# Executor manual do banco CondPulse

Executor revisado e executado remotamente em28/10/2026 no commit ace821b425cb953c0007e574009b0963ada0dddc:
inspect40728907835, baseline40730350359, migrate40730656930 e replay40731398800 passaram. O baseline
oficial002 e V003/V004/V005 estao aplicados e validados; evidencias em STATUS.md.
Nao reseta senha, nao cria login, nao importa dados e nao configura a API. O alvo
unico e o projeto `rpnrqfrfwywglvyykgrg`, Sao Paulo.

O inspect deste CLI atesta exclusivamente o bootstrap legado antes do baseline.
Com o historico oficial presente, nao reutilizar esse inspect para validar o estado
pos-migracao. O guard de migrate valida o historico/checksums e admite replay zero
quando005 ja foi aplicada. A sequencia abaixo registra o procedimento revisado;
nao repetir baseline nem apagar historico para reproduzi-la.

O workflow `.github/workflows/database-operations.yml` oferece tres operacoes
independentes. `inspect` e o padrao e nao escreve. `baseline` chama exclusivamente o
CLI existente `BootstrapBaseline`, com o ACK fornecido pelo operador. `migrate` exige
o marcador oficial BASELINE2 e valida/aplica pelo Flyway somente a faixa reconhecida
no ACK `rpnrqfrfwywglvyykgrg:DE-ATE:SHA_REVISADO`: tudo antes de DE ja aplicado, sem
lacuna, e ATE igual a ultima migration do JAR revisado (nada fica acima do alvo).
Nao existe transicao automatica inspect -> baseline -> migrate.

Faixa generalizada em 29/10/2026 para levar a homologacao da V005 a ultima versao
revisada (primeira execucao: 006-015). O ensaio JDBC descartavel do CI cobre migracao
completa a partir do baseline, repeticao com zero migrations, faixa com lacuna, faixa
que nao chega a ultima migration, historico acima do JAR, checksum divergente e banco
sem baseline.

## Preparar o cofre e a revisao

O GitHub Environment exclusivo `condpulse-database-operations` foi preparado vazio,
com Selected branches: somente `ccr-5e725112-3x8hyg`, uma branch e nenhuma tag.
O responsavel cadastrou posteriormente CP_DATABASE_OPERATIONS_PASSWORD nesse
Environment; o agente nao leu nem copiou seu valor. As operacoes acima usaram esse
segredo somente no passo operacional. O registro vazio descreve a preparacao inicial.
Nao possui reviewers obrigatorios nem tempo de espera. A revisao do codigo em PR,
o commit exato, a branch permitida e os ACKs de cada escrita sao os controles desta
entrega. Politicas organizacionais adicionais so se aplicam quando ja existentes;
este procedimento nao exige convidar outro reviewer ou adicionar aprovacao externa.
Conferir as regras efetivas antes de inserir a senha: o nome no YAML isoladamente
nao comprova a configuracao do Environment.

Adicionar somente o Environment Secret `CP_DATABASE_OPERATIONS_PASSWORD`, com a
senha ja existente da identidade postgres do proprio CondPulse. Nao colocar esse
segredo em repository/organization secrets, Vercel, aplicacao, inputs, chat ou Git.
Nao recuperar/resetar senha nem reutilizar credenciais Advanced para cumprir este
passo. Se o responsavel nao tiver o valor no cofre, a execucao permanece bloqueada.

URL JDBC, usuario e projeto sao fixos no codigo:

- Pooler de sessao IPv4 `aws-1-sa-east-1.pooler.supabase.com:5432/postgres`.
- Usuario de conexao `postgres.rpnrqfrfwywglvyykgrg`; ator real exigido `postgres`.
- TLS `verify-full`, CA `/run/secrets/db-ca.crt`, sem parametros extras.

O ator e ownership originais do bootstrap permanecem `postgres`. Mudar essa
identidade exige outra revisao; este workflow nao transfere ownership. O job nao
provisiona as credenciais runtime ou operacionais da aplicacao.

## Ordem de operacao

1. Integrar a entrega revisada. O workflow manual precisa estar na branch padrao
   para aparecer em Actions. Obter o SHA completo do commit revisado, com CI verde.
2. Selecionar Actions -> CondPulse database operations (manual) -> Run workflow,
   branch `ccr-5e725112-3x8hyg`, operation `inspect`, expected_commit igual ao SHA
   completo dessa execucao. Deixar ambos os ACKs vazios. Revisar o gate do Environment.
3. Conferir o resultado sanitizado do CLI, elegibilidade e fingerprint. A inspecao
   nao prova backup, TLS/Auth da aplicacao ou disponibilidade. Registrar run, commit,
   SHA256 do JAR, fingerprint da CA e resultado no registro operacional aprovado.
4. Para o baseline, coordenar janela exclusiva sem qualquer outro migrador/DDL,
   backup compativel e aprovacao da escrita. Fazer nova execucao `baseline`, no mesmo
   SHA revisado, com maintenance_confirmed=true e baseline_ack exatamente
   `rpnrqfrfwywglvyykgrg:002:FINGERPRINT` retornado pela inspecao. O valor nao e uma
   senha. O CLI reatesta o estado e exige correspondencia; nao gerar ACK automaticamente.
5. Conferir que essa execucao criou apenas o marcador oficial BASELINE2. A inspecao
   pre-bootstrap deixa de ser elegivel apos esse marcador, por desenho. Nao apagar
   historico nem tentar reutilizar inspect como validador pos-migracao.
6. Somente depois do baseline aceito, executar `migrate`, com maintenance_confirmed=true,
   baseline_ack vazio e migration_ack `rpnrqfrfwywglvyykgrg:DE-ATE:SHA_REVISADO`
   (historico: 003-005 em 28/10/2026). O guard exige BASELINE2 unico, sucesso,
   ownership postgres e versoes SQL contiguas a partir de 3, com checksums; antes de DE
   tudo aplicado, dentro da faixa so um prefixo (repeticao ou retomada) e ATE igual a
   ultima migration do JAR. Qualquer erro diferente de RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED
   para as versoes pendentes da faixa bloqueia antes de migrate. A validacao estrita
   continua habilitada na migracao.
7. Registrar saida, migrationsExecutadas, validacao final, commit, JAR e aprovador.
   Baseline ou migrate nao habilitam BFF/reservas nem configuram usuarios/vinculos.

Operacoes da mesma familia compartilham concurrency fixa, sem cancelar uma escrita
em curso. O lock de sessao usa a chave do executor baseline. Esses controles nao
impedem DDL por ferramentas externas que nao cooperam: a janela exclusiva continua
obrigatoria. O guard e Flyway usam conexoes diferentes, sob esse lock cooperativo.

## O que executa e como protege o segredo

Runner GitHub efemero Ubuntu; checkout do SHA exato, actions fixadas pelos mesmos
commits do CI, Java25 e build Maven sem acesso a senha. O SHA256 do JAR e impresso
antes da etapa com segredo. A CA publica vem somente da URL observada no painel:
`https://supabase-downloads.s3-ap-southeast-1.amazonaws.com/prod/ssl/prod-ca-2021.crt`.
O download exige TLS, timeout, tamanho limitado, certificado CA valido por pelo menos
um dia e verificacao da assinatura da raiz. A CA fica readonly no caminho exigido e
e removida ao encerrar. A origem HTTPS e fixa, mas nao ha fingerprint pinado: uma raiz valida renovada no mesmo endpoint nao e bloqueada automaticamente; seu fingerprint publico e registrado para auditoria.

A senha entra somente no ambiente do passo operacional e do subprocesso Java,
sem argv, arquivo, echo ou xtrace. O subprocesso recebe allowlist minima de variaveis;
nao herda overrides Spring/JDBC, proxies ou opcoes JVM. Logs do CLI sao sanitizados.
Nao ha cache, upload de artefatos, dump, fixture, SQL manual de historico, repair ou
clean. O guard Java efemero esta integralmente no YAML e so consulta o historico;
as escritas sao feitas pela API oficial Flyway.

Este executor usa JVM direta no runner efemero, com artefato identificado por SHA
de origem e SHA256 do JAR. Nao usa as imagens/Compose de release, porque imagens
publicadas por digest ainda dependem do registro aprovado. Os mesmos validadores
de destino e fontes do CLI baseline sao reutilizados; nao se apresenta um digest
local como RepoDigest nem se declara que o preflight Compose foi executado aqui.
O procedimento manual com imagens/Compose permanece em BASELINE_FLYWAY.md.

## Falha e limites

Erro apos iniciar uma escrita pode significar resultado desconhecido, especialmente
se houver timeout/perda de conexao perto do commit. Suspender a promocao e conferir
o historico por leitura autorizada; nao repetir cegamente, fabricar linhas, usar
repair, alterar marcador, afrouxar RLS ou resetar senha. Guard e Flyway rejeitam divergencias de historico/checksums,
falhas, gaps e versoes acima do ATE reconhecido. Esse guard nao atesta alteracoes estruturais ou de RLS feitas fora das migrations depois do baseline. Replay de migrate validado pode resultar em zero
migrations; isso nao autoriza repeticao apos erro sem conferir o estado.

O pacote nao comprova que GitHub tenha rota/permissao de rede ate o banco. Firewall,
restricoes de acesso do Supabase, limites de conexao e o cofre precisam ser conferidos
no ambiente. Nao abrir o banco para toda a internet para fazer o job passar.
O Environment esta vazio; a senha ainda precisa ser fornecida no cofre pelo
responsavel. Nenhum segredo foi preenchido, nenhum job foi disparado e nenhum
recurso de banco ou credencial foi criado por esta entrega.

Validacao inerte: `node --test tests/database-operations.test.mjs`. Os testes exercitam
o guard de dispatch e a allowlist do subprocesso com dados sinteticos, alem de conferir
isolamento do workflow e ordem de validacao do guard Java. Um teste adicional extrai
o JAR compilado, compila o mesmo Java embutido no YAML e executa seu caminho fechado
com senha sintetica e sem projeto/URL. Sem JDK25/JAR local, esse teste fica marcado
como nao executado; no CI deve rodar logo depois de Maven verify.

O nono teste usa o mesmo helper extraido do YAML e um harness separado, ausente
dos artefatos de producao. Exige explicitamente CP_DB_TEST_OK=DISPOSABLE, PostgreSQL
loopback, condpulse_ci e credenciais sinteticas especificas. Cria somente bancos
novos `condpulse_dbops_<id>_*`, reconstroi V001/V002 como fixture legada e gera o
marcador por Flyway.baseline oficial. Confere V003/V004/V005 aplicadas, replay zero,
rejeicao de historico sem baseline, checksum divergente numa copia temporaria de
V003 e versao006 sintetica aplicada por Flyway. Nao fabrica linhas de historico.
Fecha as conexoes e remove somente os bancos criados pela propria execucao, sem
FORCE ou terminacao de sessoes alheias. Sem o ambiente descartavel, fica marcado
como nao executado. Esses testes nunca recebem credenciais ou alvo remoto.

Referencias oficiais consultadas:

- https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow
- https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments
- https://documentation.red-gate.com/flyway/reference/commands/baseline
- docs/BASELINE_FLYWAY.md e docs/adr/0003-bootstrap-supabase.md
