# Recuperação de acesso pela web — CP-020

Implementado atrás de `CP_BFF_ENABLED` **e** `CP_BFF_RECOVERY_ENABLED` (padrão `false`, também na Vercel).
Com a flag desligada as rotas não existem (404). Não há e-mail real nem homologação com Supabase Auth:
o envio depende de SMTP e do modelo de e-mail descritos abaixo, que não foram configurados.

## Fluxo

1. **Pedido** — `POST /auth/recovery` (Origin + CSRF) com o e-mail. A resposta é sempre `202
   RECOVERY_REQUESTED`, com duração mínima de 1,2 s, exista ou não a conta e qualquer que seja a resposta do
   provedor (aceito, recusado, limite próprio do provedor ou indisponível). Limites: 3 pedidos por e-mail a
   cada 15 min e 60 por minuto no total, chaves HMAC sem e-mail em claro.
2. **Link** — o e-mail leva para `https://<origem>/recuperar-acesso#token_hash=<hash>`. O hash fica no
   fragmento (não vai ao servidor nem a logs de acesso); a página guarda o hash só na aba, limpa a barra de
   endereço e só o envia depois de um clique em "Continuar" (pré-visualizadores de e-mail não consomem o link
   de uso único). `POST /auth/recovery/verify` troca o hash no provedor (`POST /verify`, `type=recovery`) e cria
   uma tentativa de **10 minutos** em `platform.recovery_attempts` (V009), ligada ao cookie
   `__Host-cp_recovery` (HttpOnly, Secure, SameSite=Strict, Path=/). A tentativa guarda apenas o token de acesso
   do provedor, cifrado com o mesmo AES-GCM das sessões web e contexto próprio; o refresh token é descartado.
   O cookie de recuperação **nunca autentica a API**. A resposta informa se o segundo fator será exigido.
   Conta local inativa recebe `403 ACCOUNT_INACTIVE` e a sessão aberta pelo link é encerrada no provedor.
3. **Nova senha** — `POST /auth/recovery/complete` com a senha e, quando a conta tem fator TOTP verificado,
   o código atual do autenticador (verificado com a sessão do link, que passa a AAL2 antes da troca). A
   política local exige 12 a 72 caracteres, até 72 bytes (limite do bcrypt) e pelo menos 4 caracteres
   diferentes; o provedor pode exigir mais. "Senha alterada" (`200 PASSWORD_CHANGED`) só é devolvido depois de
   o provedor confirmar a troca. Em seguida a tentativa é removida, todas as sessões da conta são encerradas no
   provedor (`/logout?scope=global`) e todas as sessões web locais da conta são apagadas (política V009 com
   marcador próprio da transação). O navegador perde os cookies de recuperação, sessão e CSRF e entra de novo.
   `otherSessionsEnded=false` informa quando o provedor não confirmou o encerramento global; as sessões web
   locais são apagadas mesmo assim.

## Falhas

- Código TOTP ou senha recusados contam tentativa (máximo 5; esgotadas, a tentativa e a sessão do link
  acabam). Limite do provedor ou validação local não contam.
- Resultado incerto na troca (timeout/5xx): `503 RECOVERY_OUTCOME_UNKNOWN`; a tentativa acaba, as sessões são
  encerradas e nada é repetido. A tela orienta tentar entrar com a nova senha ou pedir outro link.
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
4. Ligar `CP_BFF_RECOVERY_ENABLED=true` somente depois do ensaio com Auth real em homologação.

## Limites conhecidos

- O tempo mínimo reduz, mas não elimina, diferenças de tempo entre contas; o limite por e-mail reduz abuso.
- Tentativas vencidas ficam na tabela até a limpeza pelo worker (ainda inexistente); a linha vencida não é
  aceita e a role runtime só a lê pelo próprio hash.
- Com mais de um fator TOTP verificado, o primeiro listado pelo provedor é exigido.
- Sessões do aplicativo móvel (CP-012) não existem ainda; o encerramento global no provedor cobre os tokens
  emitidos por ele.
- Não há aceite de CP-020: falta o ensaio com e-mail e Auth reais, além do primeiro acesso por convite.
