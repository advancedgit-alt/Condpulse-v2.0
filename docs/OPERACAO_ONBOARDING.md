# Onboarding operacional inicial

Este procedimento cadastra um condomínio novo, suas unidades, vínculos e áreas a
partir de um manifesto aprovado. É um job manual isolado, sem servidor HTTP, Spring,
migrations automáticas ou mutação do Supabase Auth. Não é o fluxo de convite,
recuperação, tutorial ou troca de dispositivo do CP-020; esses percursos continuam
pendentes. Criar uma conta no Auth, por si só, não concede vínculo nem papel local.

Estado desta entrega: Java/JDBC e launcher da imagem passaram no CI40619649142,
head9e5b5fb638c261b6d6f6f26c936f85a6e48d1160. Os testes usam PostgreSQL descartável
e protocolo Auth controlado; não houve onboarding ou prova Auth no Supabase remoto.

## Separação de identidades e pré-requisitos

| Identidade ou recurso | Uso permitido nesta operação |
| --- | --- |
| Migrador Flyway | Aplicar V001–V005 pelo processo de migração separado; não participa da execução do onboarding |
| Login `cp_onboarding_*` | Principal operacional próprio, membro apenas do grupo operacional `cp_onboarding`, com DML limitado e RLS |
| Chave Auth Admin `sb_secret_*` | Consultar somente os UUIDs Auth declarados no manifesto, pelo job operacional isolado |
| API/worker | Continuam com seus próprios grupos; não recebem login, chave ou manifesto do onboarding |
| Manifesto e CA | Arquivos privados, regulares, montados somente para leitura no job |

O projeto admitido nesta versão é exclusivamente `rpnrqfrfwywglvyykgrg`, São Paulo.
Outro ambiente exige revisão específica; não substituir o identificador para
contornar o guard. Nada da Advanced participa deste procedimento.

Antes da inspeção:

1. Confirmar a revisão aprovada, digest próprio do target Docker `onboarding`,
   ambiente, operador responsável e origem dos cadastros. Construir a imagem a
   partir do mesmo código revisado da release, mas registrar seu digest separado.
2. No projeto existente, concluir primeiro o baseline oficial 002 conforme
   `BASELINE_FLYWAY.md`; depois aplicar V003, V004 e V005 com o migrador separado.
   Banco novo segue o migrate normal desde V001. O job de onboarding não cria
   schemas, roles ou tabelas e não executa baseline, migrate, repair ou clean.
3. Provisionar no cofre um login operacional com prefixo `cp_onboarding_`, membro
   de `cp_onboarding`. A V005 cria o grupo NOLOGIN e os privilégios necessários,
   mas não cria esse login, senha ou associação de principal.
4. Conferir o principal efetivo: `current_user` e `session_user` devem ser o login
   operacional esperado, sem troca de role. Ele não pode ser SUPERUSER, BYPASSRLS,
   CREATEDB, CREATEROLE ou REPLICATION, integrar `cp_api`/`cp_worker`, criar objetos
   nos schemas, alterar contas existentes ou reescrever/apagar recibos operacionais.
5. Obter CA PostgreSQL validada, legível pelo UID10001, e testar a conexão direta
   ou pooler de sessão do próprio projeto na porta5432 e banco `postgres`.
   Exigir `sslmode=verify-full` e `sslrootcert=/run/secrets/db-ca.crt`; usuário e
   senha ficam em variáveis separadas, nunca na URL. No pooler de sessão, o usuário
   recebe o sufixo `.rpnrqfrfwywglvyykgrg`. Porta6543 e parâmetros adicionais não
   são admitidos por este CLI.
6. Disponibilizar a chave secreta Auth Admin desse projeto somente ao job. Ela é
   privilegiada: não entra na web, API, worker, migrador, logs, Git ou imagem.
   A chave publicável da aplicação não substitui essa credencial operacional.
7. Conferir backup, acesso administrativo individual, janela sem outro onboarding
   e arquivo de manifesto aprovado. Reservas e login público continuam sujeitos
   aos gates de homologação; concluir esse cadastro não libera o produto.

O inventário inerte está em `infra/onboarding.env.example`. Os valores reais vêm
do cofre e permanecem fora do repositório. Não imprimir variáveis, usar `set -x`,
executar o arquivo com `source` ou salvar a configuração Compose expandida.

## Manifesto privado

O contrato está em `contracts/operational-onboarding.schema.json`; a validação
semântica do CLI também é obrigatória. O arquivo tem no máximo1MiB e não aceita
chaves duplicadas, campos desconhecidos, documentos adicionais ou coerção de tipos.

O responsável deve fornecer, sem valores presumidos:

- `operationId` estável, UUIDs canônicos explícitos e o projeto exato.
- Condomínio com nome e zona IANA, unidades e seus rótulos únicos.
- UUIDs de contas já existentes no Auth e aprovadas para esses vínculos.
- Vínculos `RESIDENT`, `RESPONSIBLE` ou `MANAGER`, com início e término explícitos;
  o término pode ser null. Morador/responsável exige unidade; gestor fica sem unidade.
- Pelo menos um `MANAGER` designado, cobertura de todas as contas e unidades e
  nenhuma referência fora das listas do manifesto.
- Áreas com capacidade, taxa informativa em centavos, buffer, versão de regulamento,
  exigência de aprovação e política completa de reservas.

O parser limita o lote a250 contas,250 unidades,500 vínculos e100 áreas. Esses
limites não asseguram que a consulta Auth termine dentro da janela de validade da
prova. Não reduzir verificações para fazer um lote passar.

Cada subject é consultado por HTTPS no endpoint Admin do próprio projeto antes da
transação de negócio. O job exige usuário existente, e-mail confirmado, ausência
de exclusão e ausência de banimento vigente. Não cria usuário Auth, altera senha,
confirma e-mail ou concede acesso com base em `user_metadata`. A prova é revalidada
contra o relógio do banco e deve continuar válida dentro da janela de120 segundos.
Uma mudança posterior no provedor ainda depende das verificações normais de sessão;
esta prova operacional não substitui a autorização da aplicação.

O fluxo inicial só cria um condomínio ainda ausente. Contas locais existentes
precisam estar ACTIVE; contas suspensas não são reativadas. Não serve para editar
um condomínio, adicionar um lote posterior ou transferir vínculos existentes.
O papel MANAGER cadastrado não implementa MFA/AAL2 ou telas administrativas.
Áreas com aprovação obrigatória continuam indisponíveis no fluxo residencial atual.

## Inspecionar, revisar e aplicar

Usar Docker Compose5.2.0 e o arquivo separado `infra/compose.onboarding.yaml`.
Nunca incluir esse serviço na stack pública ou no job de migração. O manifesto
inicia em `inspect /run/secrets/onboarding.json`, mesmo com ACK preenchido.

Validar primeiro a configuração. O JSON expandido contém segredos e deve passar
somente pelo pipe, sem `tee`, log ou upload:

```sh
set -o pipefail
docker compose --project-name condpulse-onboarding --env-file /run/condpulse/onboarding.env -f infra/compose.onboarding.yaml config --format json | node infra/validate-release.mjs --onboarding
```

O preflight confere isolamento do job, projeto, formato da credencial/ACK, digest,
ausência de portas, limites e duas montagens somente leitura. Não comprova rede,
autenticidade da CA/chave, privilégios reais, migrações aplicadas ou dados aprovados.

Executar a inspeção:

```sh
docker compose --project-name condpulse-onboarding --env-file /run/condpulse/onboarding.env -f infra/compose.onboarding.yaml run --rm --no-deps onboarding inspect /run/secrets/onboarding.json
```

`inspect` consulta Auth e banco em transação somente leitura, com trava transacional
por condomínio; não insere os cadastros. A saída contém apenas `eligible`, `applied`,
`alreadyApplied`, `fingerprint`, `writeOutcome`, contagens e códigos controlados.
Não contém os nomes, subjects, rótulos, conteúdo do manifesto ou credenciais.

O responsável revisa o manifesto no local privado, as contagens e o resultado da
inspeção. Deve conferir sujeitos Auth, papéis, unidades, vigências, política de áreas
e operador, registrando a aprovação fora dos logs públicos. A fingerprint liga o
projeto, principal de banco e hash canônico do manifesto. Trocar operador ou alterar
o conteúdo exige nova inspeção e novo reconhecimento; não reaproveitar ACK antigo.

Após essa revisão, preencher no cofre `CP_ONBOARDING_ACK` com
`rpnrqfrfwywglvyykgrg:FINGERPRINT`, usando exatamente os64 caracteres hexadecimais
retornados pela inspeção. A fingerprint não é senha; o manifesto continua privado.
O ACK incorreto impede o comando de aplicação antes de consultar Auth ou abrir banco.

Acionar explicitamente a escrita:

```sh
docker compose --project-name condpulse-onboarding --env-file /run/condpulse/onboarding.env -f infra/compose.onboarding.yaml run --rm --no-deps onboarding apply /run/secrets/onboarding.json
```

O CLI consulta novamente o Auth antes de abrir a transação. Dentro do PostgreSQL,
reconfere principal, contexto local, prova, vigências, estado das contas e ausência
do condomínio, sob isolamento SERIALIZABLE e trava transacional. Contas locais
necessárias, condomínio, unidades, vínculos, áreas e recibo append-only em
`platform.onboarding_runs` são gravados na mesma transação. Não há chamada ao
provedor dentro dessa transação nem identidade Auth fictícia atribuída ao operador.

Resultado bem-sucedido tem `writeOutcome=COMMITTED`, códigos vazios e recibo coerente.
Registrar revisão/digest, ambiente, operador, manifesto/hash, aprovação, saída e
conferência posterior, mantendo os dados pessoais no armazenamento privado aprovado,
fora do Git.
Remover ACK e credenciais temporárias conforme a política do cofre.

## Idempotência, falha e resultado desconhecido

`operationId` é estável. Repetir o mesmo manifesto pelo mesmo operador encontra o
recibo e reconfere todos os cadastros; se estiverem coerentes, retorna
`alreadyApplied=true` sem criar novo condomínio ou novos vínculos. As contagens de
unidades/vínculos/áreas descrevem o manifesto, não uma promessa de novas inserções
em cada replay. Um operationId usado com conteúdo, condomínio ou operador divergente
aborta. Não trocar o identificador para mascarar uma tentativa anterior.

| Resultado | Conduta |
| --- | --- |
| `NOT_ATTEMPTED` | A escrita não foi iniciada; conferir o código e corrigir a causa antes de nova inspeção |
| `COMMITTED` | Conferir recibo e dados esperados; replay só é válido para o mesmo manifesto e operador |
| `UNKNOWN` | Interromper a promoção e reinspecionar; `applied=false` não comprova ausência de efeito |
| `ONBOARDING_BUSY` | Há operação concorrente; aguardar o encerramento confirmado e reinspecionar |
| `ONBOARDING_STATE_DIVERGED` ou `ONBOARDING_OPERATION_REUSED` | Reconciliar o histórico e os dados; não apagar recibo, sobrescrever linhas ou inventar novo ACK |

Uma conexão perdida perto do COMMIT pode ter confirmado. O executor classifica
conservadoramente falhas após iniciar a execução de escrita como UNKNOWN, mesmo
quando o motivo parece anterior às inserções. Não inferir rollback por código de
saída, repetir apply cegamente ou tentar desfazer automaticamente os cadastros.

Após UNKNOWN, confirmar que a execução anterior e sua conexão terminaram, preservar
o manifesto original e executar novamente apenas `inspect`, com o mesmo operador.
Se `eligible=true` e `alreadyApplied=true`, recibo e grafo esperados foram encontrados
e conferidos nessa leitura. Registrar a reconciliação. Se ainda não houver recibo,
ou houver divergência/ocupação, revisar o estado e a janela antes de decidir uma
nova aplicação explícita. Nunca modificar o histórico para produzir elegibilidade.

## Verificação e limites de aceite

Os testes do CI citado cobrem parser estrito, configuração/ACK, saída sanitizada,
consulta Auth antes de banco, transações/RLS, idempotência, divergência e concorrência
em PostgreSQL descartável. O launcher da imagem passou no ensaio sem rede. O estado de
execução por commit fica em `STATUS.md` e nas evidências dos tickets.

Baseline002 e V003–V005 já foram aplicadas remotamente conforme STATUS.md; não
repetir essas operações como novo pré-requisito do cadastro. O runtime da API tem
prova JDBC pontual no pooler após inicialização. Ainda faltam o ensaio do principal
operacional separado com Supabase Auth/CA/pooler reais, suas credenciais no cofre
e um manifesto de negócio aprovado. A preparação das chaves BFF segue
CONFIGURACAO_BFF.md e não cadastra usuários/vínculos nem ativa o produto. Não há
autoconvite, recuperação de acesso, AAL2 administrativo, edição posterior de
cadastros ou importação CSV com aprovação de lote nesta entrega. CP-020, CP-013,
CP-014 e CP-016 não podem ser tratados como integralmente concluídos por este job.
