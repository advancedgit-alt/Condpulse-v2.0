# Executor protegido de onboarding

O workflow manual `.github/workflows/onboarding-operations.yml` executa somente a
CLI existente `OperationalOnboarding`, em `inspect` ou `apply`. Esta entrega prepara
o caminho de execução; não cadastra um condomínio, não cria principal PostgreSQL,
não altera Auth, não aplica migrations e não habilita BFF/reservas. Também não
consulta o estado das flags na Vercel. O aceite das jornadas continua pendente.

O alvo desta versão é exclusivamente o projeto CondPulse
`rpnrqfrfwywglvyykgrg`, pooler de sessão São Paulo na porta5432. Não trocar esse
identificador para representar outro ambiente. Um segundo ambiente exige revisão
dos alvos/guardas e credenciais próprias. O rótulo `Production` da Vercel não é
aceite operacional de produção. Ver [ADR-0007](adr/0007-tenancy-e-promocao-de-releases.md)
e [MVP_ENTREGA.md](MVP_ENTREGA.md).

## Cofre separado e dados privados

Criar/conferir o GitHub Environment `condpulse-onboarding-operations`, com deployment
branches selecionadas: somente a branch `ccr-5e725112-3x8hyg`, sem tags. Conferir
essa regra antes de adicionar qualquer valor. O workflow não usa o Environment
administrativo de migrations nem suas credenciais. Guardas no código não substituem
a restrição do cofre e a revisão do source exato.

| Secret do Environment | Conteúdo privado e uso |
| --- | --- |
| `CP_ONBOARDING_MANIFEST_BASE64` | Base64 canônico do manifesto JSON aprovado, sem espaços ou quebras; nunca no Git/input público |
| `CP_ONBOARDING_DATABASE_USER` | Login restrito `cp_onboarding_*` com sufixo `.rpnrqfrfwywglvyykgrg` para o pooler de sessão |
| `CP_ONBOARDING_DATABASE_PASSWORD` | Senha própria desse login, separada da identidade owner/API/worker |
| `CP_ONBOARDING_AUTH_SECRET_KEY` | Chave moderna `sb_secret_*` do próprio projeto, somente no job operacional |

Os valores são cadastrados diretamente pelo responsável no cofre. Não enviá-los
no chat, copiá-los para arquivos do repositório ou imprimir o ambiente expandido.
O principal operacional precisa ser provisionado e conferido separadamente,
com membro apenas do grupo `cp_onboarding` e as restrições descritas em
[OPERACAO_ONBOARDING.md](OPERACAO_ONBOARDING.md). A migration V005 já criou o grupo,
mas não criou esse LOGIN ou senha. O executor recusa a identidade `postgres`,
credenciais runtime e a chave publicável BFF como substitutos.

O contrato de negócio permanece `contracts/operational-onboarding.schema.json` e
o parser semântico Java. Este transporte limita o manifesto decodificado a32KiB,
com Base64 de no máximo43692 caracteres para caber no limite do Secret GitHub.
O limite do parser Java é maior; não supor que todo lote aceito pela CLI cabe neste
transporte. Um lote maior exige outro transporte privado revisado, sem fracionar
ou mudar `operationId` para contornar a semântica de onboarding inicial.

Cada UUID Auth deve corresponder a conta confirmada e aprovada; a CLI consulta
esses sujeitos antes da transação. Ela não cria usuário, confirma e-mail, altera
senha, reativa conta ou concede papel com base em `user_metadata`. O responsável
revisa fora dos logs públicos nome/fuso do condomínio, unidades, sujeitos,
papéis/vigências e política completa de pelo menos uma área. A mesma conta só
acumula papel gestor e residencial com aprovação explícita.

## Source, manifesto e ACK são vínculos distintos

O acionamento contém somente dados públicos de controle:

- `expected_commit`: SHA Git completo revisado. Precisa ser exatamente o SHA da
  execução na branch principal, também conferido pelo checkout antes do build.
- `manifest_sha256`: SHA256 dos bytes exatos do JSON privado decodificado. Mudança
  de espaço, ordem ou quebra de linha muda esse hash. Não é o fingerprint Java.
- `operation`: `inspect` por padrão; `apply` precisa ser selecionado explicitamente.
- `onboarding_ack`: vazio na inspeção. Na aplicação, exige o formato abaixo.
- `maintenance_confirmed`: `true` somente após conferir aprovação do cadastro,
  backup compatível e janela sem outra operação de banco.

O gate sem segredos exige repositório, evento manual, branch e SHA exatos, valida
modo/hash/ACK e só então permite chegar ao Environment. A concorrência é a mesma
dos executores operacionais de banco/runtime/BFF; não cancela uma escrita em curso.
O wrapper repete a validação antes de ler/configurar o manifesto privado.

O SHA do manifesto é conferido nos bytes privados antes de iniciar Java. O
fingerprint retornado pela inspeção Java vincula projeto, principal de banco e
manifesto canônico. Para aplicar pelo workflow, o ACK externo é:

```text
rpnrqfrfwywglvyykgrg:SHA_COMMIT_REVISADO:SHA256_BYTES_MANIFESTO:FINGERPRINT_JAVA
```

São40 caracteres hexadecimais para o commit e64 para cada hash/fingerprint,
sempre minúsculos. O wrapper entrega à CLI apenas o ACK interno original
`rpnrqfrfwywglvyykgrg:FINGERPRINT_JAVA`. A semântica da CLI não foi alterada.

## Inspecionar e aplicar

1. Concluir revisão/CI do source e conferir o Environment restrito e seus quatro
   Secrets. Conferir o principal, a origem privada do manifesto e os responsáveis.
2. Em Actions, abrir **CondPulse onboarding operations (manual)** na branch
   principal. Informar SHA revisado e SHA256 privado; executar `inspect` com ACK vazio.
3. Conferir `eligible=true`, códigos vazios, contagens e `alreadyApplied`. Revisar
   o manifesto em local privado; a saída pública não permite revisar nomes,
   sujeitos, papéis ou regras individualmente.
4. Se a inspeção estiver elegível e o cadastro aprovado, formar o ACK externo
   usando o fingerprint Java. Confirmar a janela e disparar explicitamente `apply`
   no mesmo source e com os mesmos bytes privados/principal. Qualquer mudança
   exige nova inspeção e revisão; não reaproveitar um ACK de outro manifesto.
5. Conferir `writeOutcome=COMMITTED`, códigos vazios e recibo. Os efeitos de negócio
   e o recibo são atômicos na CLI existente. Registrar a evidência sanitizada e a
   aprovação privada. O resultado não habilita acesso público nem homologa Auth.

Um manifesto já aplicado exige reconciliação, não novo cadastro. O replay da CLI
reconfere recibo e grafo existente; `alreadyApplied=true` é preservado. Não trocar
operationId/principal, apagar recibo ou sobrescrever dados para obter elegibilidade.

## Execução e saída

Build e extração do JAR acontecem antes do passo com credenciais. O SHA256 do JAR
é registrado. A CA pública é buscada por HTTPS, conferida pelo SHA256 fixado e por
OpenSSL, e instalada no caminho fixo usado pelo JDBC com `sslmode=verify-full`.
Rotação dessa CA exige revisão do hash; não remover a validação para contornar erro.

O manifesto é escrito byte a byte em arquivo exclusivo0600, dentro de diretório
efêmero0700 no runner, e removido em `finally`. O passo final também limpa o
diretório operacional e a CA em `always()`. Não há cache/upload de artefato do
manifesto. Interrupção do runner não garante que o `finally` rode; o ciclo de vida
do runner hospedado é uma proteção adicional, não prova de apagamento físico.

O Java recebe somente o ambiente permitido, alvo fixo e credenciais operacionais.
Senha/chave não entram em argumentos de processo. Configurações externas de JVM,
proxies e credenciais owner/runtime/Vercel não são herdadas. stdout/stderr têm
limites e são capturados; erros brutos nunca são ecoados. Só campos, contagens,
estados, códigos e fingerprint controlados são reconstruídos para o log público.

Timeout, kill, overflow ou saída não confiável após iniciar `apply` resultam em
`UNKNOWN`, mesmo com `applied=false`. O wrapper não repete a operação nem tenta
desfazer cadastros. Confirmar o fim da execução/conexão e reinspecionar com o mesmo
manifesto/principal, conforme [OPERACAO_ONBOARDING.md](OPERACAO_ONBOARDING.md).
Falha de limpeza após `COMMITTED` preserva esse resultado e encerra o job com erro;
resolver a limpeza sem inferir ausência de escrita ou repetir apply.

## Verificação e pendências

Testes Node exercitam o gate, transporte privado, ambiente do processo, resultados
desconhecidos e sanitização. O CI compila o Java e ensaia o wrapper contra a CLI
real com um manifesto sintético inválido, recusado antes de Auth/JDBC. Os testes
JDBC já existentes continuam verificando transação, RLS, replay e concorrência em
banco descartável. Esses ensaios não constituem execução gerenciada do onboarding.

Ainda dependem de conclusão: cadastro e regras reais aprovados, principal restrito
e seus Secrets, consulta Auth/Admin e pooler com as credenciais reais, recibo do
onboarding, ensaio de login e separação efetiva entre homologação e produção.
Não declarar uma dessas pendências concluída pelo build, por este documento ou
pelo sucesso dos testes sintéticos.
