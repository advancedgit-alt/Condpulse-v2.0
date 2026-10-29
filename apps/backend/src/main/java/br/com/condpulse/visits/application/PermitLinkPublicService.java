package br.com.condpulse.visits.application;

import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.visits.infrastructure.PermitLinkPublicStore;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Visualização do convite por quem tem o link (CP-037). Link desconhecido, revogado, de convite revogado ou com a
 * vigência encerrada dão a mesma resposta: o link não serve mais, sem dizer o motivo nem confirmar que existiu.
 */
@Service @Profile("api")
public class PermitLinkPublicService {
 private final PermitLinkPublicStore store;
 public PermitLinkPublicService(PermitLinkPublicStore store) { this.store=store; }
 public VisitModels.PublicPermit view(String token) {
  if(!PermitLinkTokens.wellFormed(token)) throw unavailable();
  VisitModels.PublicPermit permit;
  try { permit=store.view(PermitLinkTokens.hash(token)); }
  catch(DataAccessException | TransactionException error) {
   throw new BusinessProblem("PERMIT_LINK_DEPENDENCY_UNAVAILABLE",503,"Não foi possível abrir o convite agora. Tente novamente.");
  }
  if(permit==null || "REVOKED".equals(permit.state()) || "EXPIRED".equals(permit.state())) throw unavailable();
  return permit;
 }
 public static BusinessProblem unavailable() {
  return new BusinessProblem("PERMIT_LINK_UNAVAILABLE",404,"Este link de convite não está disponível. Peça um novo link a quem convidou.");
 }
}
