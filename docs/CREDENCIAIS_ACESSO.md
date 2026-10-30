# Credenciais de acesso e revogação prioritária (CP-046)

Atrás de `CP_ACCESS_CREDENTIALS_ENABLED` (API e web) e de `CP_WORKER_ACCESS_SYNC_ENABLED` (worker, desligado). Banco:
V026 (`cp.access_connectors`, `cp.access_devices`, `cp.access_credentials`, `cp.credential_syncs`). Nenhum parceiro físico
está contratado: o único adaptador existente é o simulado do CP-043, usado só nos testes.

## Modelo

- **Estado desejado** fica no CondPulse: `desired_state` (`ACTIVE`/`REVOKED`) e `desired_version`. A credencial nasce
  `ACTIVE` na versão 1; a única mudança é `ACTIVE→REVOKED` com versão 2, motivo, autor e horário do banco. Revogada é
  final: para voltar a liberar, cadastra-se outra credencial (PRD: "nunca recriar credencial revogada").
- **Confirmação por equipamento** (`cp.credential_syncs`): `attempted_*` é o último comando enviado; `observed_*` só
  existe quando o equipamento confirmou (`APPLIED`). O banco recusa (23514) gravar comando ou confirmação de versão ou
  ação que não sejam as desejadas **no momento da gravação**: confirmação atrasada da ativação antiga nunca reativa
  credencial revogada.
- **Situação** derivada: `ACTIVE` (todos confirmaram), `SYNCING`, `PARTIAL`, `FAILED` (recusa definitiva desta versão),
  `REVOKING` (revogação ainda não confirmada em todos), `REVOKED` (confirmada em todos), `NO_DEVICES`.
- Sem foto, template facial ou número de cartão: `kind` é `CARD` ou `QR` e o rótulo é livre ("Cartão final 1234").
  Credencial facial (CP-045/CP-050) depende de base legal e do parceiro.
- `external_ref` é opaca (`cpc_` + 32 hex), sem nome, unidade ou documento.

## Quem faz o quê

- **Gestão** (`memberships.manage`, AAL2): cadastra para vínculo residencial vigente (morador, responsável ou
  dependente) numa integração ativa, com chave de operação; revoga pela versão lida, com motivo. **Encerrar o vínculo**
  revoga as credenciais dele na mesma transação (auditoria por credencial).
- **Portaria** (AAL2): vê as revogações ainda não confirmadas, das mais antigas, com os equipamentos pendentes, para
  seguir a contingência local. A tela nunca afirma "bloqueado" enquanto houver equipamento pendente.
- **Pessoa**: vê as próprias credenciais (`GET /units/{u}/access-credentials`).
- **Conector e equipamento**: só a operação da plataforma cadastra (a aplicação não tem grant de escrita).

## Reconciliação (worker)

`CredentialReconciler`: para cada conector ativo com adaptador disponível, lê os destinos que ainda não confirmaram o
estado desejado **atual** (não reproduz fila antiga), **revogações primeiro**, das mais antigas. Resultado incerto
(`UNKNOWN`) ou só recebido (`ACCEPTED`) é consultado (`getApplication`) antes de reenviar; versão ausente no equipamento
é reenviada (comando idempotente por versão e ação). Espera: 30 s para incerto ou recebido, `Retry-After` para limite,
60 s dobrando até 1 h para fora do ar; recusa definitiva só volta com mudança. Nenhuma transação fica aberta durante a
conversa com o parceiro; resultado que deixou de ser o desejado é descartado pelo banco.

## Rotas

`GET /v1/tenants/{t}/access-connectors`, `GET|POST /management/access-credentials` (`Idempotency-Key`),
`POST /management/access-credentials/{id}/revoke`, `GET /concierge/pending-revocations`,
`GET /units/{u}/access-credentials`. Contrato em `contracts/openapi.json`.

## Evidências

`tests/database/run.py` (`credential_checks`), `CredentialJdbcTest`, `CredentialWorkerJdbcTest` (worker com o adaptador
simulado: ativação só recebida substituída pela revogação, consulta antes de reenviar, fora do ar com espera,
prioridade da revogação, conector sem adaptador), `DatabaseRoleGuardJdbcTest`; web `api/accessCredentials.test.ts` e
`e2e/access-credentials.spec.ts`.

## Pendentes

- BLOQUEADO: adaptador real e equipamentos do parceiro físico (CP-044) — responsável — comercial/integrador. Sem ele,
  nenhuma credencial chega a equipamento e a homologação não terá conector cadastrado.
- Worker contínuo não implantado (WORKER_IMPLANTACAO.md); celular ("Meu acesso", MOR-06) e aviso de pendência há mais
  de 60 s ainda não existem.
