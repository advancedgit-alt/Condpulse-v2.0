package br.com.condpulse.accessmap.application;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accessgovernance.application.TechnicalScope;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.accessmap.infrastructure.MapStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.dao.DataAccessException;

/**
 * Áreas e pontos (AC-004, V041). Gestão (area.manage, AAL2): cria e edita a área lógica e interdita; lê pontos pelo nome.
 * MASTER de implantação (installation.manage, AAL2): marca rota essencial e separação física e cria/edita pontos. O banco
 * repete as fronteiras (campos físicos, comissionamento, hierarquia, versão e autoria).
 */
public class MapService {
 private static final Set<String> AREA_KINDS=Set.of("COMMON_AREA","AMENITY","RECEPTION","TECHNICAL_AREA","RESIDENTIAL_ROUTE","EXTERIOR");
 private static final Set<String> SUBTYPES=Set.of("EXTERIOR","HALL","CORRIDOR","GYM","POOL","GARAGE","PARTY_ROOM","PLAYGROUND","SPORTS_COURT",
  "MACHINE_ROOM","STORAGE","OTHER");
 private static final Set<String> AREA_STATES=Set.of("DRAFT","ACTIVE","INTERDICTED","RETIRED");
 private static final Set<String> POINT_KINDS=Set.of("DOOR","TURNSTILE","VEHICLE_GATE","PEDESTRIAN_GATE","ELEVATOR_CONTROL","OTHER");
 private static final Set<String> FLOWS=Set.of("FROM_TO","TO_FROM","BOTH","UNKNOWN");
 private static final Set<String> EGRESS=Set.of("FREE_EGRESS","MANAGED_EGRESS","NOT_EGRESS","UNVERIFIED");
 private static final Set<String> POINT_STATES=Set.of("DRAFT","MAPPED","COMMISSIONED","SUSPENDED","RETIRED");
 private static final String CODE="[A-Z0-9][A-Z0-9_-]{0,63}",REF="[A-Za-z0-9._:/-]{3,120}";
 private final TenantTransactionExecutor tenants;
 private final TechnicalTransactionExecutor technical;
 private final MapStore store;
 private final AuditRecorder audit;
 public MapService(TenantTransactionExecutor tenants,TechnicalTransactionExecutor technical,MapStore store,AuditRecorder audit) {
  this.tenants=tenants; this.technical=technical; this.store=store; this.audit=audit;
 }

 // ---- gestão
 public MapModels.AreaPage areas(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_AREAS);
   return new MapModels.AreaPage(store.areas(tenant));
  }));
 }
 public MapModels.PointSummaryPage pointSummaries(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_AREAS);
   return new MapModels.PointSummaryPage(store.points(tenant).stream().map(MapStore::summary).toList());
  }));
 }
 public MapModels.Area createArea(UUID actor,UUID tenant,AssuranceLevel assurance,MapModels.CreateArea request) {
  var a=validate(request);
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_AREAS);
   String zone=a.timeZone()==null?context.timeZone().getId():a.timeZone();
   var existing=store.area(tenant,a.areaId());
   if(existing!=null) {
    if(MapStore.sameArea(existing,a,zone)) return existing;
    throw new BusinessProblem("ACCESS_AREA_ID_CONFLICT",409,"Este identificador já foi usado para outra área.");
   }
   store.insertArea(tenant,a,zone,actor);
   audit.record(tenant,actor,"AREA_CREATED","ACCESS_AREA",a.areaId(),null);
   return store.area(tenant,a.areaId());
  }));
 }
 public MapModels.Area updateArea(UUID actor,UUID tenant,AssuranceLevel assurance,UUID areaId,MapModels.UpdateArea request) {
  if(areaId==null) throw notFound("ACCESS_AREA_NOT_FOUND");
  var a=validate(areaId,request);
  return mapped(()->tenants.execute(actor,tenant,assurance,context->{
   context.requireCapability(StaffCapability.MANAGE_AREAS);
   var current=store.area(tenant,areaId);
   if(current==null) throw notFound("ACCESS_AREA_NOT_FOUND");
   String zone=a.timeZone()==null?current.timeZone():a.timeZone();
   if(!store.updateArea(tenant,areaId,a,zone,actor)) throw stale();
   audit.record(tenant,actor,"AREA_UPDATED","ACCESS_AREA",areaId,null);
   return store.area(tenant,areaId);
  }));
 }

 // ---- MASTER de implantação
 public MapModels.AreaPage technicalAreas(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_READ);
   return new MapModels.AreaPage(store.areas(tenant));
  }));
 }
 public MapModels.Area setPhysical(UUID actor,UUID tenant,AssuranceLevel assurance,UUID areaId,MapModels.AreaPhysical request) {
  if(areaId==null) throw notFound("ACCESS_AREA_NOT_FOUND");
  if(request==null || request.essentialRoute()==null || request.physicalBoundaryVerified()==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("ACCESS_AREA_PHYSICAL_INVALID","Informe rota essencial, separação física verificada e a versão lida.");
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_MANAGE);
   if(store.area(tenant,areaId)==null) throw notFound("ACCESS_AREA_NOT_FOUND");
   if(!store.updatePhysical(tenant,areaId,request.essentialRoute(),request.physicalBoundaryVerified(),request.expectedVersion(),actor)) throw stale();
   audit.record(tenant,actor,"AREA_PHYSICAL_UPDATED","ACCESS_AREA",areaId,null);
   return store.area(tenant,areaId);
  }));
 }
 public MapModels.PointPage technicalPoints(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_READ);
   return new MapModels.PointPage(store.points(tenant));
  }));
 }
 public MapModels.Point createPoint(UUID actor,UUID tenant,AssuranceLevel assurance,MapModels.CreatePoint request) {
  var p=validate(request);
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_MANAGE);
   var existing=store.point(tenant,p.pointId());
   if(existing!=null) {
    if(MapStore.samePoint(existing,p)) return existing;
    throw new BusinessProblem("ACCESS_POINT_ID_CONFLICT",409,"Este identificador já foi usado para outro ponto.");
   }
   store.insertPoint(tenant,p,actor);
   audit.record(tenant,actor,"POINT_CREATED","ACCESS_POINT",p.pointId(),null);
   return store.point(tenant,p.pointId());
  }));
 }
 public MapModels.Point updatePoint(UUID actor,UUID tenant,AssuranceLevel assurance,UUID pointId,MapModels.UpdatePoint request) {
  if(pointId==null) throw notFound("ACCESS_POINT_NOT_FOUND");
  var p=validate(request);
  return mapped(()->technical.execute(actor,tenant,assurance,context->{
   context.require(TechnicalScope.INSTALLATION_MANAGE);
   if(store.point(tenant,pointId)==null) throw notFound("ACCESS_POINT_NOT_FOUND");
   if(!store.updatePoint(tenant,pointId,p,actor)) throw stale();
   audit.record(tenant,actor,"POINT_UPDATED","ACCESS_POINT",pointId,null);
   return store.point(tenant,pointId);
  }));
 }

 // ---- validação (antes de abrir transação)
 MapModels.CreateArea validate(MapModels.CreateArea r) {
  if(r==null || r.areaId()==null) throw invalid("ACCESS_AREA_ID_REQUIRED","Informe o identificador da área.");
  if(r.code()==null || !r.code().matches(CODE)) throw invalid("ACCESS_AREA_CODE_INVALID","Código: maiúsculas, números, _ ou -, até 64.");
  String status=r.status()==null?"DRAFT":r.status();
  if(!Set.of("DRAFT","ACTIVE").contains(status)) throw invalid("ACCESS_AREA_STATUS_INVALID","Área nasce DRAFT ou ACTIVE.");
  var common=area(r.name(),r.areaKind(),r.amenityId(),r.timeZone(),r.physicalSubtype());
  return new MapModels.CreateArea(r.areaId(),r.code(),common[0],r.areaKind(),r.blockId(),r.parentAreaId(),r.amenityId(),common[1],common[2],status);
 }
 MapModels.UpdateArea validate(UUID areaId,MapModels.UpdateArea r) {
  if(r==null || r.expectedVersion()==null || r.expectedVersion()<0) throw invalid("ACCESS_AREA_VERSION_REQUIRED","Informe a versão lida.");
  if(r.status()==null || !AREA_STATES.contains(r.status())) throw invalid("ACCESS_AREA_STATUS_INVALID","Estado inválido.");
  if(areaId.equals(r.parentAreaId())) throw invalid("ACCESS_AREA_PARENT_INVALID","A área não pode ser pai de si mesma.");
  var common=area(r.name(),r.areaKind(),r.amenityId(),r.timeZone(),r.physicalSubtype());
  return new MapModels.UpdateArea(common[0],r.areaKind(),r.blockId(),r.parentAreaId(),r.amenityId(),common[1],common[2],r.status(),r.expectedVersion());
 }
 private static String[] area(String name,String kind,UUID amenity,String zone,String subtype) {
  String normalized=DisplayText.normalize(name,160);
  if(normalized==null) throw invalid("ACCESS_AREA_NAME_INVALID","Nome de 1 a 160 caracteres.");
  if(kind==null || !AREA_KINDS.contains(kind)) throw invalid("ACCESS_AREA_KIND_INVALID","Tipo de área inválido.");
  if(("AMENITY".equals(kind))!=(amenity!=null)) throw invalid("ACCESS_AREA_AMENITY_INVALID","Área reservável exige o tipo AMENITY e vice-versa.");
  if(subtype!=null && !SUBTYPES.contains(subtype)) throw invalid("ACCESS_AREA_SUBTYPE_INVALID","Subtipo físico inválido.");
  String z=zone==null || zone.isBlank()?null:zone.strip();
  if(z!=null) try { java.time.ZoneId.of(z); } catch(java.time.DateTimeException ex) { throw invalid("ACCESS_AREA_TIME_ZONE_INVALID","Fuso IANA inválido."); }
  return new String[]{normalized,z,subtype};
 }
 MapModels.CreatePoint validate(MapModels.CreatePoint r) {
  if(r==null || r.pointId()==null) throw invalid("ACCESS_POINT_ID_REQUIRED","Informe o identificador do ponto.");
  if(r.code()==null || !r.code().matches(CODE)) throw invalid("ACCESS_POINT_CODE_INVALID","Código: maiúsculas, números, _ ou -, até 64.");
  String status=r.status()==null?"DRAFT":r.status();
  if(!Set.of("DRAFT","MAPPED").contains(status)) throw invalid("ACCESS_POINT_STATUS_INVALID","Ponto nasce DRAFT ou MAPPED; comissionamento é aceite técnico.");
  var c=point(r.name(),r.fromAreaId(),r.toAreaId(),r.pointKind(),r.flowDirection(),r.egressClass(),r.accessibleRouteRef(),r.emergencyDesignRef());
  return new MapModels.CreatePoint(r.pointId(),r.code(),c[0],r.fromAreaId(),r.toAreaId(),r.pointKind(),c[1],c[2],r.accessibleRouteRef(),
   r.emergencyDesignRef(),status);
 }
 MapModels.UpdatePoint validate(MapModels.UpdatePoint r) {
  if(r==null || r.expectedVersion()==null || r.expectedVersion()<0) throw invalid("ACCESS_POINT_VERSION_REQUIRED","Informe a versão lida.");
  if(r.status()==null || !POINT_STATES.contains(r.status())) throw invalid("ACCESS_POINT_STATUS_INVALID","Estado inválido.");
  var c=point(r.name(),r.fromAreaId(),r.toAreaId(),r.pointKind(),r.flowDirection(),r.egressClass(),r.accessibleRouteRef(),r.emergencyDesignRef());
  return new MapModels.UpdatePoint(c[0],r.fromAreaId(),r.toAreaId(),r.pointKind(),c[1],c[2],r.accessibleRouteRef(),r.emergencyDesignRef(),r.status(),
   r.expectedVersion());
 }
 private static String[] point(String name,UUID from,UUID to,String kind,String flow,String egress,String route,String design) {
  String normalized=DisplayText.normalize(name,160);
  if(normalized==null) throw invalid("ACCESS_POINT_NAME_INVALID","Nome de 1 a 160 caracteres.");
  if(from==null || to==null || from.equals(to)) throw invalid("ACCESS_POINT_AREAS_INVALID","Informe origem e destino diferentes.");
  if(kind==null || !POINT_KINDS.contains(kind)) throw invalid("ACCESS_POINT_KIND_INVALID","Mecanismo inválido.");
  String f=flow==null?"UNKNOWN":flow,e=egress==null?"UNVERIFIED":egress;
  if(!FLOWS.contains(f)) throw invalid("ACCESS_POINT_FLOW_INVALID","Sentido inválido.");
  if(!EGRESS.contains(e)) throw invalid("ACCESS_POINT_EGRESS_INVALID","Classe de saída inválida.");
  if((route!=null && !route.matches(REF)) || (design!=null && !design.matches(REF))) throw invalid("ACCESS_POINT_REF_INVALID","Referência de projeto inválida.");
  return new String[]{normalized,f,e};
 }

 private static <T> T mapped(Supplier<T> action) {
  try { return action.get(); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("42501".equals(state)) throw new BusinessProblem("ACCESS_MAP_FORBIDDEN",403,"Operação não permitida para este acesso.");
   if("23505".equals(state)) throw new BusinessProblem("ACCESS_MAP_CODE_CONFLICT",409,"Código já usado neste condomínio.");
   if("23503".equals(state)) throw new BusinessProblem("ACCESS_MAP_REFERENCE_INVALID",422,"Área, bloco ou área reservável inexistente neste condomínio.");
   if("23514".equals(state)) throw new BusinessProblem("ACCESS_MAP_INVALID",422,"Alteração recusada pelas regras do mapa (hierarquia, estado ou comissionamento).");
   throw new BusinessProblem("ACCESS_MAP_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente.");
  }
 }
 private static BusinessProblem stale() { return new BusinessProblem("ACCESS_MAP_STALE",409,"O cadastro mudou. Atualize e tente de novo."); }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound(String code) { return new BusinessProblem(code,404,"Cadastro não encontrado neste condomínio."); }
}
