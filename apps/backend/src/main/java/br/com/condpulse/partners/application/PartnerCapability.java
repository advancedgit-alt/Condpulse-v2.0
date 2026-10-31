package br.com.condpulse.partners.application;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Capacidades dos papéis de parceiro (PRD complementar §4.4, ADR-0011). Mesma matriz de
 * platform.partner_role_capabilities (V049), conferida em teste JDBC. Todas exigem AAL2; o banco confere de novo papel,
 * contrato e escala em platform.has_capability. {@link #tenantScoped()} só vale dentro de um condomínio alcançado.
 */
public enum PartnerCapability {
 MANAGE_PARTNER("partner.manage"),
 MANAGE_TEAM("partner.team.manage"),
 SECURITY_COMMAND("security.command"),
 READ_SECURITY_LOG("security.log.read"),
 READ_SHIFT_SECURITY_LOG("security.log.read.shift"),
 EXPORT_SECURITY_LOG("security.log.export"),
 REVIEW_REMOTE_OPEN("security.remote_open.review"),
 PUBLISH_SECURITY_POLICY("security.policy.publish"),
 APPROVE_BLOCKLIST("security.blocklist.approve"),
 SUPERVISE_SHIFT("ops.shift.supervise"),
 OPERATE_REMOTE("ops.remote.operate"),
 OPEN_REMOTE("ops.remote.open"),
 /** Sempre com concessão técnica vigente (V037) no condomínio; o banco confere. */
 COMMISSION_DEVICES("devices.commission");

 /** Papéis de parceiro aceitos pelo banco (V049), em ordem estável. */
 public static final List<String> ROLES=List.of("PARTNER_ADMIN","SECURITY_LEAD","SECURITY_SUPERVISOR","REMOTE_OPERATOR","TECHNICIAN");

 private final String key;
 PartnerCapability(String key) { this.key=key; }
 /** Identificador estável publicado em app.capabilities. */
 public String key() { return key; }
 /** Operação dentro do condomínio: nunca vale só pelo contexto do parceiro. */
 public boolean tenantScoped() { return this==OPERATE_REMOTE || this==OPEN_REMOTE || this==COMMISSION_DEVICES; }

 public static Set<PartnerCapability> forRole(String role) {
  if(role==null) return EnumSet.noneOf(PartnerCapability.class);
  return switch(role) {
   case "PARTNER_ADMIN" -> EnumSet.of(MANAGE_PARTNER,MANAGE_TEAM,READ_SECURITY_LOG);
   case "SECURITY_LEAD" -> EnumSet.of(SECURITY_COMMAND,READ_SECURITY_LOG,EXPORT_SECURITY_LOG,REVIEW_REMOTE_OPEN,
    PUBLISH_SECURITY_POLICY,APPROVE_BLOCKLIST);
   case "SECURITY_SUPERVISOR" -> EnumSet.of(READ_SHIFT_SECURITY_LOG,SUPERVISE_SHIFT,OPERATE_REMOTE,OPEN_REMOTE);
   case "REMOTE_OPERATOR" -> EnumSet.of(OPERATE_REMOTE,OPEN_REMOTE);
   case "TECHNICIAN" -> EnumSet.of(COMMISSION_DEVICES);
   default -> EnumSet.noneOf(PartnerCapability.class);
  };
 }
 public static Set<PartnerCapability> forRoles(Iterable<String> roles) {
  var granted=EnumSet.noneOf(PartnerCapability.class);
  for(String role:roles) granted.addAll(forRole(role));
  return granted;
 }
 /** Painel de destino do papel na /entrada (PRD §4.1, rota /p/{parceiro}/...). */
 public static String panel(String role) {
  return switch(role) {
   case "PARTNER_ADMIN" -> "gestao";
   case "SECURITY_LEAD" -> "comando";
   case "SECURITY_SUPERVISOR" -> "turno";
   case "REMOTE_OPERATOR" -> "console";
   case "TECHNICIAN" -> "tecnico";
   default -> throw new IllegalArgumentException("Papel de parceiro desconhecido.");
  };
 }
}
