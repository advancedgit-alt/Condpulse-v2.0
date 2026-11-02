# Identidade conferida de visitantes — ADR-0014

Em implementação; V059 ainda não aplicada em HML. Esta parte prepara a identidade compartilhada entre convites e chegadas para o bloqueio de reentrada. Não ativa bloqueio físico nem representa reconhecimento facial.

A portaria autenticada com segundo fator busca identidades já conferidas pelo início do nome (mínimo três caracteres, até20 resultados). A busca é auditada; o nome e o final do documento servem somente à conferência humana. Não há associação automática. Depois de conferir pessoalmente o documento, a portaria registra uma identidade nova ou associa o cadastro da chegada/convite a uma raiz já conferida, com motivo. Documento completo, fotografia e biometria não são armazenados por este fluxo.

Uma conferência ativa por cadastro, alias de um único nível, sem ciclos nem troca silenciosa da raiz. Autor, horário e origem vêm do servidor e não mudam. A gestão com segundo fator anula uma associação errada por versão e motivo; os registros e eventos anteriores permanecem. Antes de anular uma raiz, é necessário corrigir suas associações ativas. A portaria pode conferir novamente o cadastro depois da anulação.

Rotas sob CP_VISITS_ENABLED e módulo VISITS:

| Método e rota sob /v1/tenants/{tenantId} | Uso |
| --- | --- |
| GET /stay-identities/source?permitId=… ou arrivalId=… | Conferência ativa da origem, portaria/AAL2 |
| GET /stay-identities?prefix=… | Raízes conferidas, portaria/AAL2, leitura auditada |
| POST /stay-identities | requestId, origem, canonicalVisitorId opcional, reason; portaria/AAL2 |
| POST /stay-identities/{checkId}/void | version e reason; gestão de vínculos/AAL2 |

POST da conferência repete o mesmo requestId/conteúdo/autor sem novo evento. Alterar os dados sob o mesmo identificador gera conflito. Todas as respostas são no-store; sessão web exige CSRF. Runtime sem DELETE/TRUNCATE nem atualização de identidade/autor/horário. Worker lê somente identificadores opacos necessários à decisão futura, sem nome/documento/motivos.

Validação prevista: PostgreSQL real com dois convites de nomes diferentes associados explicitamente; repetição; nomes sem associação automática; alias encadeado; raiz com associações; anulação versionada e reconferência; conta/unidade/tenant/AAL incorretos; SQL direto imutável; rotas HTTP reais com autenticação e CSRF. Resultado só será aceito após CI e revisão do commit final.

Ainda pendente nesta entrega parcial: restrição por atraso, aceite de responsabilidade do morador e confirmação da portaria, pendências proativas e integração física confirmada por adaptador. Não ligar configuração de bloqueio antes de a decisão canônica e seus testes existirem.
