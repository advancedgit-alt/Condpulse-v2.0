package br.com.condpulse.accessgovernance.application;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Escopos técnicos do acesso facial (AC-003, V037). Lista fechada, igual à função cp.technical_scopes_valid: o cliente
 * nunca envia curinga. Nenhum escopo é herdado pelo papel MANAGER; só existem por concessão técnica vigente e AAL2.
 */
public enum TechnicalScope {
 /** Ler a instalação técnica (equipamentos, canais, vínculos) do condomínio. */
 INSTALLATION_READ("installation.read"),
 /** Cadastrar conector, equipamento e instância provisionada. */
 INSTALLATION_MANAGE("installation.manage"),
 /** Vincular relé, leitor e sensor a ponto físico (revisão de binding). */
 INSTALLATION_BIND_CHANNEL("installation.bind_channel"),
 /** Registrar comissionamento e aceite técnico do ponto. */
 INSTALLATION_COMMISSION("installation.commission"),
 /** Iniciar rotação de segredo do conector pelo cofre; nunca exibe o segredo. */
 CONNECTOR_ROTATE_SECRET("connector.rotate_secret"),
 /** Identidade e estado do gateway local ou de nuvem do condomínio. */
 GATEWAY_MANAGE("gateway.manage"),
 /** Solicitar e registrar ensaio de homologação (a aprovação é só da plataforma). */
 HOMOLOGATION_REQUEST("homologation.request"),
 /** Cadastrar e aprovar pessoas, somente quando delegado explicitamente. */
 PERSON_REVIEW("person.review"),
 /** Abertura pontual, somente em ensaio autorizado. */
 ACCESS_REQUEST_OPEN("access.request_open"),
 /** Exportar eventos identificáveis com finalidade específica. */
 AUDIT_EXPORT("audit.export");

 private final String key;
 TechnicalScope(String key) { this.key=key; }
 /** Identificador estável publicado em app.technical_scopes. */
 public String key() { return key; }

 public static Optional<TechnicalScope> fromKey(String key) {
  for(var scope:values()) if(scope.key.equals(key)) return Optional.of(scope);
  return Optional.empty();
 }
 /** Escopos que cada papel técnico pode receber; papel desconhecido não recebe nada. */
 public static Set<TechnicalScope> allowedFor(String roleCode) {
  if(roleCode==null) return EnumSet.noneOf(TechnicalScope.class);
  return switch(roleCode) {
   case "MASTER_IMPLANTACAO" -> EnumSet.allOf(TechnicalScope.class);
   case "TECHNICAL_SUPPORT" -> EnumSet.of(INSTALLATION_READ,HOMOLOGATION_REQUEST);
   default -> EnumSet.noneOf(TechnicalScope.class);
  };
 }
 /** Escopos padrão do MASTER de implantação: os de instalação, sem pessoas, abertura nem exportação. */
 public static Set<TechnicalScope> implantationDefaults() {
  return EnumSet.of(INSTALLATION_READ,INSTALLATION_MANAGE,INSTALLATION_BIND_CHANNEL,INSTALLATION_COMMISSION,CONNECTOR_ROTATE_SECRET,
   GATEWAY_MANAGE,HOMOLOGATION_REQUEST);
 }
}
