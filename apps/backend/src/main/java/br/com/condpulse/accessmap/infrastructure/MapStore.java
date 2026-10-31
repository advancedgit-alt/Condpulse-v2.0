package br.com.condpulse.accessmap.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import br.com.condpulse.accessmap.application.MapModels;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL de áreas e pontos (V041); o banco confere fuso, hierarquia, campos físicos, versão e autoria. */
public class MapStore {
 private final JdbcTemplate jdbc;
 public MapStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public List<MapModels.Area> areas(UUID tenant) {
  return jdbc.query("select * from cp.access_areas where tenant_id=? order by code limit 1000",MapStore::area,tenant);
 }
 public MapModels.Area area(UUID tenant,UUID id) {
  var rows=jdbc.query("select * from cp.access_areas where tenant_id=? and id=?",MapStore::area,tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static MapModels.Area area(ResultSet r,int n) throws SQLException {
  return new MapModels.Area(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("area_kind"),
   r.getObject("block_id",UUID.class),r.getObject("parent_area_id",UUID.class),r.getObject("amenity_id",UUID.class),r.getString("timezone"),
   r.getBoolean("essential_route"),r.getBoolean("physical_boundary_verified"),r.getString("physical_subtype"),r.getString("status"),
   r.getTimestamp("updated_at").toInstant(),r.getLong("version"));
 }
 public String tenantZone(UUID tenant) {
  return jdbc.queryForObject("select time_zone from platform.tenants where id=?",String.class,tenant);
 }
 public void insertArea(UUID tenant,MapModels.CreateArea a,String zone,UUID actor) {
  jdbc.update("""
   insert into cp.access_areas(id,tenant_id,code,name,area_kind,block_id,parent_area_id,amenity_id,timezone,physical_subtype,status,created_by)
   values(?,?,?,?,?,?,?,?,?,?,?,?)
   """,a.areaId(),tenant,a.code(),a.name(),a.areaKind(),a.blockId(),a.parentAreaId(),a.amenityId(),zone,a.physicalSubtype(),a.status(),actor);
 }
 public boolean updateArea(UUID tenant,UUID id,MapModels.UpdateArea a,String zone,UUID actor) {
  return jdbc.update("""
   update cp.access_areas set name=?,area_kind=?,block_id=?,parent_area_id=?,amenity_id=?,timezone=?,physical_subtype=?,status=?,updated_by=?,
    version=version+1 where tenant_id=? and id=? and version=?
   """,a.name(),a.areaKind(),a.blockId(),a.parentAreaId(),a.amenityId(),zone,a.physicalSubtype(),a.status(),actor,tenant,id,a.expectedVersion())==1;
 }
 public boolean updatePhysical(UUID tenant,UUID id,boolean essential,boolean boundary,long expected,UUID actor) {
  return jdbc.update("""
   update cp.access_areas set essential_route=?,physical_boundary_verified=?,updated_by=?,version=version+1 where tenant_id=? and id=? and version=?
   """,essential,boundary,actor,tenant,id,expected)==1;
 }
 /** Repetição só com o pedido inteiro igual ao gravado. */
 public static boolean sameArea(MapModels.Area area,MapModels.CreateArea a,String zone) {
  return area.code().equals(a.code()) && area.name().equals(a.name()) && area.areaKind().equals(a.areaKind())
   && Objects.equals(area.blockId(),a.blockId()) && Objects.equals(area.parentAreaId(),a.parentAreaId())
   && Objects.equals(area.amenityId(),a.amenityId()) && area.timeZone().equals(zone) && Objects.equals(area.physicalSubtype(),a.physicalSubtype())
   && area.status().equals(a.status());
 }

 public List<MapModels.Point> points(UUID tenant) {
  return jdbc.query("select * from cp.access_points where tenant_id=? order by code limit 1000",MapStore::point,tenant);
 }
 public MapModels.Point point(UUID tenant,UUID id) {
  var rows=jdbc.query("select * from cp.access_points where tenant_id=? and id=?",MapStore::point,tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static MapModels.Point point(ResultSet r,int n) throws SQLException {
  return new MapModels.Point(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getObject("from_area_id",UUID.class),
   r.getObject("to_area_id",UUID.class),r.getString("point_kind"),r.getString("flow_direction"),r.getString("egress_class"),
   r.getString("accessible_route_ref"),r.getString("emergency_design_ref"),r.getString("status"),r.getLong("version"));
 }
 public static MapModels.PointSummary summary(MapModels.Point p) {
  return new MapModels.PointSummary(p.pointId(),p.code(),p.name(),p.fromAreaId(),p.toAreaId(),p.pointKind(),p.flowDirection(),p.status());
 }
 public void insertPoint(UUID tenant,MapModels.CreatePoint p,UUID actor) {
  jdbc.update("""
   insert into cp.access_points(id,tenant_id,code,name,from_area_id,to_area_id,point_kind,flow_direction,egress_class,accessible_route_ref,
    emergency_design_ref,status,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?)
   """,p.pointId(),tenant,p.code(),p.name(),p.fromAreaId(),p.toAreaId(),p.pointKind(),p.flowDirection(),p.egressClass(),p.accessibleRouteRef(),
   p.emergencyDesignRef(),p.status(),actor);
 }
 public boolean updatePoint(UUID tenant,UUID id,MapModels.UpdatePoint p,UUID actor) {
  return jdbc.update("""
   update cp.access_points set name=?,from_area_id=?,to_area_id=?,point_kind=?,flow_direction=?,egress_class=?,accessible_route_ref=?,
    emergency_design_ref=?,status=?,updated_by=?,version=version+1 where tenant_id=? and id=? and version=?
   """,p.name(),p.fromAreaId(),p.toAreaId(),p.pointKind(),p.flowDirection(),p.egressClass(),p.accessibleRouteRef(),p.emergencyDesignRef(),
   p.status(),actor,tenant,id,p.expectedVersion())==1;
 }
 public static boolean samePoint(MapModels.Point point,MapModels.CreatePoint p) {
  return point.code().equals(p.code()) && point.name().equals(p.name()) && point.fromAreaId().equals(p.fromAreaId())
   && point.toAreaId().equals(p.toAreaId()) && point.pointKind().equals(p.pointKind()) && point.flowDirection().equals(p.flowDirection())
   && point.egressClass().equals(p.egressClass()) && Objects.equals(point.accessibleRouteRef(),p.accessibleRouteRef())
   && Objects.equals(point.emergencyDesignRef(),p.emergencyDesignRef()) && point.status().equals(p.status());
 }
}
