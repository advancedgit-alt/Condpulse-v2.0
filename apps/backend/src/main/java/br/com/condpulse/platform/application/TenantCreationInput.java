package br.com.condpulse.platform.application;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import br.com.condpulse.reservations.application.AmenityManagementModels;
import br.com.condpulse.shared.application.BusinessProblem;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pedido de criação já validado, com as mesmas regras do manifesto do onboarding (nome até 160, fuso IANA, unidades de
 * 1 a 40 caracteres sem repetição, até 250 unidades) e o hash canônico usado pela idempotência.
 */
record TenantCreationInput(String key,String name,String timeZone,List<String> units,String managerName,String managerEmail,
  int validDays,AmenityManagementModels.ValidAmenity amenity,String reason,String requestHash) {
 static final int MAX_UNITS=250;
 private static final JsonMapper JSON=JsonMapper.builder().build();
 record Manager(String name,String email,int days) {}

 static TenantCreationInput validate(String key,PlatformModels.CreateTenant request) {
  if(key==null || !key.matches("[A-Za-z0-9._:-]{16,128}"))
   throw PlatformService.invalid("IDEMPOTENCY_KEY_INVALID","Informe uma chave de operação válida de 16 a 128 caracteres.");
  if(request==null) throw PlatformService.invalid("TENANT_INPUT_INVALID","Informe os dados do condomínio.");
  String name=PlatformService.text(request.name(),160,"TENANT_NAME_INVALID","Informe o nome do condomínio com até 160 caracteres visíveis.");
  String zone=request.timeZone()==null?null:request.timeZone().strip();
  if(zone==null || zone.isEmpty() || zone.length()>80 || !ZoneId.getAvailableZoneIds().contains(zone))
   throw PlatformService.invalid("TENANT_TIME_ZONE_INVALID","Informe um fuso horário IANA válido, por exemplo America/Sao_Paulo.");
  if(request.units()==null || request.units().isEmpty() || request.units().size()>MAX_UNITS)
   throw PlatformService.invalid("TENANT_UNITS_INVALID","Informe de 1 a 250 unidades.");
  var units=new ArrayList<String>(); var seen=new HashSet<String>();
  for(String raw:request.units()) {
   String label=PlatformService.text(raw,40,"UNIT_LABEL_INVALID","Cada unidade deve ter de 1 a 40 caracteres visíveis.");
   if(!seen.add(label.toLowerCase(Locale.ROOT)))
    throw PlatformService.invalid("TENANT_UNITS_DUPLICATED","A unidade \""+label+"\" aparece mais de uma vez.");
   units.add(label);
  }
  var manager=manager(request.manager());
  AmenityManagementModels.ValidAmenity amenity=null;
  if(request.amenity()!=null) {
   var input=request.amenity();
   // Área nasce ativa; motivo e versão da gestão local não se aplicam à criação.
   amenity=AmenityManagementModels.validate(new AmenityManagementModels.AmenityInput(input.name(),input.capacity(),
    input.approvalRequired(),input.feeCents(),input.bufferMinutes(),true,input.policy(),null,null),true);
  }
  String reason=PlatformService.text(request.reason(),500,"AUDIT_REASON_REQUIRED","Informe o motivo do cadastro, com até 500 caracteres.");
  var canonical=new LinkedHashMap<String,Object>();
  canonical.put("name",name); canonical.put("timeZone",zone); canonical.put("units",units);
  canonical.put("managerName",manager.name()); canonical.put("managerEmail",manager.email()); canonical.put("validDays",manager.days());
  if(amenity!=null) {
   var area=new LinkedHashMap<String,Object>();
   area.put("name",amenity.name()); area.put("capacity",amenity.capacity()); area.put("approvalRequired",amenity.approvalRequired());
   area.put("feeCents",amenity.feeCents()); area.put("bufferMinutes",amenity.bufferMinutes()); area.put("policy",amenity.policyJson());
   canonical.put("amenity",area);
  }
  canonical.put("reason",reason);
  String hash=ManagerInvitationTokens.sha256(JSON.writeValueAsString(canonical));
  return new TenantCreationInput(key,name,zone,List.copyOf(units),manager.name(),manager.email(),manager.days(),amenity,reason,hash);
 }
 static Manager manager(PlatformModels.ManagerInput input) {
  if(input==null) throw PlatformService.invalid("MANAGER_REQUIRED","Informe o primeiro gestor do condomínio.");
  String name=PlatformService.text(input.displayName(),160,"MANAGER_NAME_INVALID","Informe o nome do gestor com até 160 caracteres visíveis.");
  String email=ManagerInvitationTokens.normalizedEmail(input.email());
  int days=input.validDays()==null?7:input.validDays();
  if(days<1 || days>14) throw PlatformService.invalid("MANAGER_INVITATION_VALIDITY_INVALID","A validade do convite deve ter entre 1 e 14 dias.");
  return new Manager(name,email,days);
 }
}
