# Preparação protegida do BFF, sem ativação

Este executor prepara somente a configuração de autenticação do projeto CondPulse.
Não habilita login ou reservas, não publica deployment e não escreve no banco/Auth.
O executor JDBC e sua geração READY permanecem independentes e não devem ser
executados novamente para preparar o BFF.

## Alvo e valores permitidos

- Supabase: `rpnrqfrfwywglvyykgrg`, São Paulo.
- Vercel: `prj_IeUa4Zjg1IwaPqJQwv525H14I837`, time
  `team_6fzA029jxhDb1LpnrnphWGQ0`, somente target Production.
- Origem já configurada: `https://project-rhvdq.vercel.app`; issuer e JWKS precisam
  continuar apontando para o projeto Supabase acima.
- `CP_BFF_ENABLED=false` e `CP_RESERVATIONS_ENABLED=false` são pré-condições,
  conferidas antes e depois da escrita. O executor não as altera.
- Duas chaves distintas de 32 bytes, geradas com CSPRNG dentro do runner e
  codificadas em Base64 canônico: `CP_BFF_ENCRYPTION_KEY` e
  `CP_BFF_RATE_LIMIT_KEY`. São armazenadas como Secrets, sem arquivo ou log.
- `CP_SUPABASE_PUBLISHABLE_KEY` é Config pública, validada com um GET HTTPS
  de `/auth/v1/settings` do projeto fixo, usando somente o header `apikey`.
  Não aceita chave administrativa `sb_secret_*`, anon JWT legado ou URL arbitrária.

O rótulo Production da Vercel não constitui promoção do produto. Este ambiente
continua na preparação de homologação da ADR-0005; isolamento próprio entre
homologação e produção e demais gates continuam exigidos para uso real.

## Cofre e inspeção

O workflow manual `.github/workflows/bff-configuration.yml` usa o Environment
existente `condpulse-database-operations`, restrito à branch principal
`ccr-5e725112-3x8hyg`. O gate sem segredos confere evento/repositório/branch,
SHA exato, geração, operação e formato do ACK antes de liberar o Environment.
Somente o último passo recebe:

1. `CP_VERCEL_TOKEN`, credencial já cadastrada para o projeto CondPulse.
2. `CP_SUPABASE_PUBLISHABLE_KEY`, chave publicável do próprio projeto Supabase,
   cadastrada no mesmo cofre. Não colar chaves no chat, Git ou formulário de inputs
   do workflow. Este job não recebe senha do banco nem chave Auth Admin.

Depois de revisão e CI aprovados no source exato integrado:

1. Abrir o workflow **CondPulse BFF configuration (manual, flags remain off)**.
2. Selecionar a branch principal, `operation=inspect`, uma geração UUID canônica
   nova e `expected_commit` com os 40 hex do source revisado. Deixar ACK vazio.
3. Conferir o JSON sanitizado. `state=ABSENT`, `eligible=true`, `applied=false`
   e `code` vazio permitem preparar a aplicação. A inspeção só consulta e não
   gera as duas chaves. Registrar source, geração e fingerprint.

Nenhum target, projeto, origem ou endpoint é selecionável por input. A leitura
Vercel usa a coleção de variáveis sem pedir descriptografia de Secrets; nunca
acessa o campo de valor de nomes secretos ou desconhecidos. Configs esperadas
precisam ser legíveis como Config/plain; formatos legados ambíguos são recusados.
Paginação incompleta, duplicatas, branch/custom environment, integração compartilhada,
target misto ou flags habilitadas bloqueiam a operação.

## Aplicação e conferência

O ACK é exatamente:

```text
rpnrqfrfwywglvyykgrg:bff:GENERATION:SOURCE_SHA:FINGERPRINT64
```

Usar os valores da inspeção, sem inferir fingerprint nem reaproveitar source antigo.
Acionar `operation=apply`, preencher o ACK e confirmar a janela exclusiva, depois
da revisão da operação concreta. Não deve haver edição no painel, provisionamento
ou outro job concorrente durante a operação. A trava do workflow coordena os jobs
do repositório, mas não é um compare-and-swap atômico com edições externas no painel.

O helper refaz a consulta imediatamente antes de gerar as chaves e exige o mesmo
fingerprint. Cria apenas as três variáveis acima em um POST sem `upsert`, não faz
overwrite e não executa retry. Exige `failed=[]`, conjunto exato de `created` e
readback de IDs/metadados próprios; HTTP2xx sozinho não comprova o lote completo.
O retorno `STAGED/applied=true` registra a criação e a conferência de metadados.
`secretValuesVerified=false` permanece explícito: Secrets armazenados não são lidos
de volta. O fingerprint final descreve os metadados posteriores e difere do inicial.

O JSON final contém somente versão, flags de resultado, estado, fingerprint e código
controlado. A resposta bruta do provedor não é registrada, pois erros da API podem
ecoar o valor submetido em `failed.error.value`. Transporte HTTPS normal, redirects
recusados, corpo limitado a256KiB e prazo de10s por requisição; sem arquivos,
artefatos ou caches contendo chaves.

## Estado parcial e recuperação

| Estado | Conduta |
| --- | --- |
| `ABSENT` | Nada preparado; revisar a inspeção antes de aplicar |
| `STAGED` | Criação completa e readback de metadados; flags continuam false |
| `STAGED_METADATA_ONLY` | As três variáveis próprias já existem; consulta sem geração ou escrita |
| `BLOCKED` | Escrita não iniciada; corrigir a pré-condição indicada |
| `UNKNOWN` | Um POST foi tentado; efeito parcial/completo é possível, interromper aplicação |

Após UNKNOWN, confirmar o término do job anterior e executar somente `inspect`,
com a mesma geração/source. Um conjunto próprio completo pode ser reconhecido
como STAGED_METADATA_ONLY, mas isso não prova novamente conteúdo, entropia ou
diferença das chaves armazenadas. Uma variável isolada, comentário alheio ou estado
incompatível exige reconciliação manual revisada. Não apagar, regenerar, usar upsert
ou trocar a geração para mascarar a tentativa anterior. Rotação não é implementada
por este executor e deve considerar sessões e limites compartilhados existentes.

## Próximo gate: cadastro aprovado e Auth real

Uma leitura remota agregada em28/10 confirmou1 identidade Auth confirmada e não
banida,1 conta local,0 condomínios/unidades/vínculos/recibos de onboarding.
Nenhum e-mail, UUID, nome ou outro dado pessoal foi lido nessa consulta.
Isso não escolhe o piloto nem autoriza criar vínculos presumidos.

O responsável precisa definir o condomínio, usuários Auth existentes e aprovados,
unidades, papéis/vigências e áreas/políticas em manifesto privado. Seguir
`OPERACAO_ONBOARDING.md`: login operacional `cp_onboarding_*` separado, chave Auth
Admin apenas no job e fluxo inspect → revisão/ACK → apply. O CLI não cria usuários
Auth. As migrations remotas já concluídas não devem ser repetidas como requisito novo.

Depois do cadastro, ativar BFF/reservas de forma controlada no alvo de homologação
e ensaiar Auth real, CSRF/cookies, refresh/logout, contexto, reserva/replay/cancelamento
e revogação antes da liberação aos usuários. Este pacote e seus testes sintéticos
não equivalem a essa homologação, aceite P0/P1, capacidade ou lançamento.

## Fontes do contrato consultadas

- https://vercel.com/docs/rest-api/projects/create-one-or-more-environment-variables
- https://vercel.com/docs/rest-api/projects/retrieve-the-environment-variables-of-a-project-by-id-or-name
- https://vercel.com/docs/environment-variables/sensitive-environment-variables
- https://supabase.com/docs/guides/api/api-keys
