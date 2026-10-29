# Mobile — CondPulse (React Native/Expo)

Expo SDK 58, React Native 0.88, versões exatas no `package.json` e no `package-lock.json`. Navegação única por estado
da sessão (decisão do PRD §291: "Expo Router ou uma navegação única"); telas de produto entram por ticket (CP-024 em
diante). Sem distribuição: falta build de desenvolvimento próprio e teste em aparelhos reais (CP-053/CP-062).

## Sessão nativa (CP-012)

- Login direto no Supabase Auth com a chave **publicável** (`flowType: 'pkce'`); a API recebe o access token como
  `Bearer`. O BFF de cookies é só da web.
- Sessão do Auth guardada no cofre do aparelho (`expo-secure-store`, `WHEN_UNLOCKED_THIS_DEVICE_ONLY`), dividida em
  partes abaixo do limite do SecureStore, com índice para apagar tudo. Android com `allowBackup: false`.
- Reinstalação: marca em arquivo do próprio app (some ao desinstalar). Sem a marca, o cofre é apagado antes de qualquer
  leitura — no iOS o Keychain sobrevive à desinstalação.
- Saída pelo botão, renovação recusada (token expirado ou revogado), 401 depois de uma renovação, identidade de outra
  conta e troca de conta: apagam o cofre e o cache em memória antes da próxima tela. Sair sem rede ainda apaga o aparelho.
- Conta com TOTP verificado precisa do código (AAL2) antes de qualquer token ir para a API.
- Cache de dados só em memória (TanStack Query); nada do condomínio é gravado no aparelho.

## Reservas (CP-024)

- Com um vínculo de unidade escolhido: áreas do condomínio (capacidade, taxa informativa, aprovação), regras da área
  (horário, antecedência, duração, limpeza, cancelamento), dia e duração permitidos pela regra e horários calculados com
  as mesmas regras do servidor (`src/reservations/slots.ts`), marcando o que a consulta de ocupação mostra como ocupado.
- Envio exige aceite da versão das regras. Área com aprovação vira **pedido aguardando aprovação**, nunca "confirmada".
  Conflito (horário ocupado entre a consulta e o envio) atualiza a ocupação, desfaz a escolha e pede outro horário.
- Resultado incerto trava a escolha e repete com a mesma `Idempotency-Key` (nada é reservado duas vezes).
- Minhas reservas (30 dias): situação, motivo, retirar pedido ou cancelar com confirmação e versão lida.
- A API aceita escrita com `Authorization: Bearer` sem CSRF quando não há cookie de sessão do CondPulse (cliente nativo);
  sessão do navegador continua exigindo CSRF (`SecurityConfiguration.bearerWithoutBrowserSession`).

## Configuração

Variáveis públicas embutidas no build (nunca segredo; sem `.env` no Git):

- `EXPO_PUBLIC_SUPABASE_URL` — `https://{ref}.supabase.co`
- `EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY` — `sb_publishable_...` (chave secreta ou de serviço é recusada)
- `EXPO_PUBLIC_API_URL` — origem HTTPS da API

## Comandos

```sh
npm ci
npm run typecheck
npm test
EXPO_OFFLINE=1 npx expo export --platform android   # só confere o bundle JS
```

## Pendências

- Build de desenvolvimento (EAS ou local) e ensaio em Android/iOS reais: reinstalação, troca de conta, token expirado,
  app em segundo plano. Os testes atuais são em Jest com Auth e armazenamento simulados.
- Origem pública da API para o app (hoje a homologação responde pela URL da Vercel) e configuração por ambiente.
- Push, câmera e deep links não existem ainda.
