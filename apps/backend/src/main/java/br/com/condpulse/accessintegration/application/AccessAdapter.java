package br.com.condpulse.accessintegration.application;

/**
 * Contrato interno de integração de acesso v1 (spec/api/contrato_integracao.md). Não é a API de nenhum fabricante: cada
 * adaptador real (CP-044) traduz as operações documentadas do parceiro para este contrato. O estado desejado fica no
 * CondPulse; a evidência de aplicação vem do equipamento. Se o parceiro só confirma recebimento, o máximo afirmado é
 * {@link AccessModels.Outcome#ACCEPTED}.
 */
public interface AccessAdapter {
 AccessModels.Capabilities capabilities(String connectorId);
 AccessModels.PersonResult upsertPerson(AccessModels.UpsertPerson request);
 AccessModels.Application applyCredential(AccessModels.ApplyCredential request);
 AccessModels.Application getApplication(AccessModels.ApplicationQuery query);
 AccessModels.Application revokeCredential(AccessModels.RevokeCredential request);
 AccessModels.EventPage getEvents(AccessModels.EventQuery query);
 AccessModels.Health health(String connectorId);
 /** Abertura remota: não habilitada no lançamento; pedido vencido nunca chega ao equipamento. */
 AccessModels.OpenResult requestOpen(AccessModels.OpenRequest request);
}
