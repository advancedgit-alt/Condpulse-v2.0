# Operação e recuperação
## Gate de produção
Responsáveis, monitor externo, SMTP/MFA, região/pool, inventário de segredos e suporte.
Sem backup/restore contratado ou executado nesta fundação.
Banco precisa de PITR/equivalente para RPO<=15min. Objetos têm cópia externa separada.
Metas de objetos<=1h e RTO<=4h só após ensaio. Retenção proposta7diários/4semanais a aprovar.

## Restore
Congelar escritas -> parar worker/conectores/comandos físicos -> restaurar em ambiente novo ->
resetar custom role passwords/segredos -> recuperar objetos e conferir hashes/ACL ->
validar contagens/isolamento -> reaplicar revogações posteriores ->
reconciliar reservas/handoffs/outbox -> smoke -> liberar tráfego -> retomar worker.
Nunca religar integração física apenas porque banco voltou.

## Release
Expandir schema, migration job, API readiness, smoke, tráfego, worker, observação.
Rollback de aplicação por imagem compatível. Sem reversão destrutiva automática do banco.
Registrar commit/digest/migrations/aprovador/ambiente/resultado.

## Incidentes
P0: acesso indevido, exposição entre tenants ou indisponibilidade com risco.
P1: jornada importante indisponível com contingência. P2: falha localizada. P3: melhoria.
Revogação pendente: mostrar destinos, alertar e acionar integrador; não afirmar bloqueado.
Sem internet: seguir procedimento local homologado; não confirmar operação online.
Push falho: manter dado oficial, conferir receipt/token e canal alternativo permitido.
Segredo exposto: conter/rotacionar/revisar usos e avaliar impacto com privacidade.

## Migração de condomínio
Diagnóstico -> preview CSV -> revisão -> lote idempotente -> reconciliação ->
aceite -> convites revisados -> treinamento de todos os turnos -> corte por processo.
Uma agenda oficial; não cancelar fornecedor físico anterior sem homologação e rollback.
