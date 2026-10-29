# Recuperação de acesso pela web — CP-020

Implementado atrás de `CP_BFF_ENABLED` **e** `CP_BFF_RECOVERY_ENABLED` (padrão `false`, também na Vercel).
Com a flag desligada as rotas não existem (404). Não há e-mail real nem homologação com Supabase Auth:
o envio depende de SMTP e do modelo de e-mail descritos abaixo, que não foram configurados.

## Fluxo

1. **Pedido** — `POST /auth/recovery` (Origin + CSRF) com o e-mail. A resposta é sempre `202
   RECOVERY_REQUESTED`, em prazo fixo de 1,5 s mais variação de até 0,3 s, exista ou não a conta e qualquer
   que seja a resposta do provedor (aceito, recusado, limite próprio do provedor ou indisponível). A chamada ao
   provedor roda numa fila curta (2 execuções, 32 na espera): provedor lento não atrasa a resposta além do
   prazo e fila cheia descarta o envio sem mudar a resposta. Limites: 3 pedidos por e-mail a cada 15 min e 60
   por minuto no total, chaves HMAC sem e-mail em claro.
2. **Link** — o e-mail leva para `https://<origem>/recuperar-acesso#token_hash=<hash>`. O hash fica no
   fragmento (não vai ao servidor nem a logs de acesso); a página guarda o hash só na aba, limpa a barra de
   endereço e só o envia depois de um clique em "Continuar" (pré-visualizadores de e-mail não consomem o link
   de uso único). `POST /auth/recovery/verify` troca o hash no provedor (`POST /verify`, `type=recovery`) e cria
   uma tentativa de **10 minutos** em `platform.recovery_attempts` (V009), ligada ao cookie
   `__Host-cp_recovery` (HttpOnly, Secure, SameSite=Strict, Path=/). A tentativa guarda apenas o token de acesso
   do provedor, cifrado com o mesmo AES-GCM das sessões web e contexto próprio; o refresh token é descartado.
   O cookie de recuperação **nunca autentica a API**. A resposta informa se o segundo fator será exigido.
   Conta local inativa recebe `403 ACCOUNT_INACTIVE` e a sessão aberta pelo link é encerrada no provedor.
   A conta devolvida pelo provedor em `/user` precisa ser a mesma do token. Conta com fator verificado de
   outro tipo (telefone, webauthn), que a web não sabe exigir, recebe `403 RECOVERY_FACTOR_UNSUPPORTED`.
3. **Nova senha** — `POST /auth/recovery/complete` com a senha e, quando a conta tem fator TOTP verificado,
   o código atual do autenticador (verificado com a sessão do link, que passa a AAL2 antes da troca; o código
   usa o mesmo limite por conta do `/auth/mfa/verify`, 5 a cada 15 min, que links novos não renovam). A
   política local exige 12 a 72 caracteres, até 72 bytes (limite do bcrypt) e pelo menos 4 caracteres
   diferentes; o provedor pode exigir mais. A conta é conferida de novo antes da troca (suspensão depois do
   link encerra a tentativa). "Senha alterada" (`200 PASSWORD_CHANGED`) só é devolvido depois de o provedor
   responder 200 com a mesma conta; outro 2xx ou redirecionamento é resultado incerto. Em seguida a tentativa
   é removida, todas as sessões da conta são encerradas no
   provedor (`/logout?scope=global`) e todas as sessões web locais da conta são apagadas (política V009 com
   marcador próprio da transação). O navegador perde os cookies de recuperação, sessão e CSRF e entra de novo.
   `otherSessionsEnded=false` informa quando o provedor não confirmou o encerramento global; as sessões web
   locais são apagadas mesmo assim.

## Falhas

- Código TOTP ou senha recusados contam tentativa (máximo 5; esgotadas, `422 RECOVERY_ATTEMPTS_EXHAUSTED`
  encerra a tentativa, o cookie e a sessão do link). Limite do provedor, limite por conta do código ou
  validação local não contam. No banco, falhas só aumentam e a exigência do segundo fator só cai junto com a
  troca do token (gatilho da V009); a role runtime não escolhe `created_at`, falhas, reserva nem versão na
  criação.
- Resultado incerto na troca (timeout/5xx, resposta sem confirmação): `503 RECOVERY_OUTCOME_UNKNOWN`; a
  tentativa acaba, as sessões são encerradas e nada é repetido. Na web, resposta perdida ou inválida da
  conclusão vira o mesmo código, e a do link vira `RECOVERY_LINK_UNCERTAIN`; a tela orienta tentar entrar com
  a nova senha ou pedir outro link.
- Reserva concorrente da mesma tentativa: `409 RECOVERY_IN_PROGRESS`. Reserva vencida de processo
  interrompido remove a tentativa (não repete operação de resultado incerto).
- Link inválido, expirado ou já usado: `400 RECOVERY_LINK_INVALID`. Tentativa vencida: `401
  RECOVERY_SESSION_EXPIRED`.

## Configuração pendente no provedor (operação, não feita por esta PR)

1. SMTP próprio do projeto Supabase (o envio padrão do Supabase não serve para uso real).
2. Modelo "Reset password" apontando para o fragmento, por exemplo
   `{{ .SiteURL }}/recuperar-acesso#token_hash={{ .TokenHash }}`, com Site URL igual à origem BFF homologada.
3. Prazo do link e limites de envio do provedor revisados; política de senha do provedor compatível com a
   política local (o provedor recusa → `422 PASSWORD_REJECTED`, sem consumir novo e-mail).
4. Ligar `CP_BFF_RECOVERY_ENABLED=true` somente depois do ensaio com Auth real em homologação, incluindo a
   pré-condição abaixo.

## Pré-condição de segurança no provedor (bloqueia a flag)

O `token_hash` do e-mail também é credencial do próprio provedor: quem tem acesso ao e-mail pode chamar
`/auth/v1/verify` e `/auth/v1/user` diretamente com a chave publicável (que é pública por definição) e pular
o segundo fator, os limites e o cookie do BFF. O que impede a troca nesse caminho é o provedor recusar a nova
senha numa sessão AAL1 de conta com fator verificado. Isso **não foi verificado**. O ensaio com Auth real
precisa resgatar o hash diretamente e confirmar que `PUT /user` com a senha falha para conta com TOTP; se o
provedor aceitar, a flag continua desligada e o e-mail precisa levar um link que só o BFF consiga trocar.
O mesmo vale para a enumeração: `/recover` chamado diretamente não passa pela resposta neutra do BFF.

## Limites conhecidos

- O prazo fixo reduz diferenças de tempo entre contas no BFF; o limite por e-mail reduz abuso. A chamada direta
  ao provedor não passa por essa proteção (pré-condição acima).
- Os limites globais (60/min no pedido, 120/min na validação e na conclusão) são conferidos antes dos
  individuais, como no login: um cliente fora do navegador consegue esgotá-los e atrasar a recuperação de
  todos por até um minuto. Envio de e-mail e limites do provedor saem do mesmo IP do BFF.
- "Sessões encerradas" cobre as sessões web do CondPulse e o encerramento global no provedor. Um token de
  acesso já emitido (por exemplo, obtido por login concluído durante a troca) não é mais renovado, mas vale
  até expirar; a API não compara a emissão do token com a data da troca de senha.
- Tentativas vencidas ficam na tabela até a limpeza pelo worker (ainda inexistente); a linha vencida não é
  aceita e a role runtime só a lê pelo próprio hash.
- Com mais de um fator TOTP verificado, o primeiro listado pelo provedor é exigido.
- Sessões do aplicativo móvel (CP-012) não existem ainda; o encerramento global no provedor cobre os tokens
  emitidos por ele.
- Não há aceite de CP-020: falta o ensaio com e-mail e Auth reais, além do primeiro acesso por convite.
