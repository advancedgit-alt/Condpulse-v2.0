# CP-011 — integração da sessão web BFF
Estado: código inicial e testes preparados, ainda sem execução Maven/JDBC/HTTP nesta sessão.
Nenhum token real foi usado. O BFF permanece desligado por padrão e não simula login.

## Arquivos novos
V003 cria platform.web_sessions. Cookie guarda segredo opaco de256bits; o banco só recebe SHA256.
Access/refresh tokens são cifrados AES256-GCM, nonce diferente a cada operação e AAD ligado a conta/hash/tipo.
Sessão absoluta de8h (configurável1–24); role runtime não pode alterar identidade, hash ou limite absoluto.
Refresh adquire lease/CAS por transação curta e chama Auth fora da transação. Timeout invalida a sessão;
nenhuma repetição cega. Lease ainda ativa retorna409. Lease vencida invalida a sessão e exige login:
o processo pode ter caído após rotacionar o token no Auth. O DELETE é commitado antes de lançar401
fora da TransactionTemplate. A conclusão também exige lease não vencida. Refresh que perde corrida
com logout não recria linha.
Logout remove sessão durável antes da revogação remota. Revogação no provedor é best-effort:
indisponibilidade do Auth não reativa a sessão local.

## Integração obrigatória em SecurityConfiguration
Adicionar imports:
```java
import br.com.condpulse.identity.bff.BffSecurityConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
```
Alterar assinatura de apiSecurity:
```java
SecurityFilterChain apiSecurity(HttpSecurity http,ObjectProvider<BffSecurityConfigurer> bff) throws Exception
```
Antes da cadeia atual:
```java
var configurer=bff.getIfAvailable();
if(configurer!=null) configurer.configure(http);
```
Antes de anyRequest().denyAll(), acrescentar:
```java
.requestMatchers(HttpMethod.GET,"/auth/csrf").permitAll()
.requestMatchers(HttpMethod.POST,"/auth/login","/auth/refresh","/auth/logout").permitAll()
```
Não desativar CSRF nem dispensá-lo por header Bearer. BffOriginFilter rejeita mutações /auth sem Origin
exatamente igual ao configurado, inclusive se houver Authorization. Cookie autentica /v1 apenas quando
não há Authorization; um Bearer inválido nunca cai para a conta do cookie.
Filtros BFF são criados dentro do configurer e registrados só na cadeia Security, evitando dupla execução Servlet.

## Configuração necessária em application.yaml
No mesmo nível de condpulse.auth:
```yaml
condpulse:
  bff:
    enabled: ${CP_BFF_ENABLED:false}
    origin: ${CP_BFF_ORIGIN:}
    publishable-key: ${CP_SUPABASE_PUBLISHABLE_KEY:}
    encryption-key: ${CP_BFF_ENCRYPTION_KEY:}
    session-hours: ${CP_BFF_SESSION_HOURS:8}
```
CP_BFF_ORIGIN deve ser a origem HTTPS final, sem barra/caminho; emissor Auth deve terminar /auth/v1.
A chave AES é aleatória32bytes em Base64, mantida no cofre; nunca gerar/commitar valor real.
Rotação dessa chave invalida sessões cifradas pela anterior; não declarar rotação transparente.
Publishable key é do próprio projeto/ambiente. A API exige conta platform.accounts ACTIVE previamente
aprovada; login não inventa vínculo, não ativa conta nova e não aceita papel de user_metadata.
Nenhuma configuração real foi preenchida nesta implementação.

## Fluxo de navegador
1. GET /auth/csrf: cookie __Host-cp_csrf Secure/HttpOnly/SameSite=Lax/Path=/; JSON headerName/token.
2. POST /auth/login com email/password, Origin correto e header X-CSRF-Token do passo1.
3. Resposta expõe somente accountId/expiresAt. Auth tokens permanecem no servidor.
4. Login rotaciona o cookie CSRF; repetir GET /auth/csrf antes de próxima mutação.
5. GET /v1/me usa cookie __Host-cp_session Secure/HttpOnly/SameSite=Lax/Path=/.
6. Se access token vencer, API retorna401 SESSION_REFRESH_REQUIRED preservando cookie;
   POST /auth/refresh requer CSRF/origem, renova fora de transação e rotaciona CSRF.
7.409 SESSION_REFRESH_IN_PROGRESS mantém cookie e não repete Auth.401/503 de refresh invalida cookie.
8. POST /auth/logout exige CSRF/origem, remove sessão e expira ambos cookies.
Todas as respostas /auth são no-store. Nenhum localStorage, token em URL ou confirmação otimista.

## CI
- A contagem de migrations no suite tests/database/run.py passa de2 para3.
- Depois do suite PostgreSQL, executar psql -X -v ON_ERROR_STOP=1 -f tests/database/bff_sessions.sql
  no mesmo banco descartável. O arquivo faz rollback das suas fixtures.
- Novos testes Java usam somente dependências já existentes: Spring Test/MockRestServiceServer/JUnit/Mockito.
  Nenhum spring-security-test adicional necessário.
- Os26 testes novos cobrem cifra/AAD/tamper/chave, cookie/CSRF/Origin, principal autenticado, DTO/provider protocol,
  rejeição sem vazar corpo, refresh concorrente/timeout/crash com lease vencida, commit de invalidação,
  conta divergente e corrida com logout.
- Executar Maven e corrigir eventuais incompatibilidades de API; não foram compilados localmente.

## Gates ainda necessários
- Teste HTTP integrado da cadeia Security configurada, banco e provedor sandbox em HTTPS.
- Teste JDBC real com store e pool reutilizado entre sessões/contas, incluindo expiração.
- Teste de refresh/logout concorrente com Auth real e interrupção de rede controlada.
- Rate limiting de login/recuperação por mecanismo de produção e observabilidade sem credenciais.
- MFA CP-013, onboarding/recuperação CP-020 e interface web são tickets separados, não implementados.
Não marcar CP-011 homologado ou ativar login público só porque o código compila.
