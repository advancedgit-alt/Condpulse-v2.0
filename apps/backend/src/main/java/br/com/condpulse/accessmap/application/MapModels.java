package br.com.condpulse.accessmap.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contratos de áreas e pontos (AC-004, V041). Gestão vê e edita a área lógica; o físico é do MASTER de implantação. */
public final class MapModels {
 private MapModels() {}
 public record Area(UUID areaId,String code,String name,String areaKind,UUID blockId,UUID parentAreaId,UUID amenityId,String timeZone,
   boolean essentialRoute,boolean physicalBoundaryVerified,String physicalSubtype,String status,Instant updatedAt,long version) {}
 public record AreaPage(List<Area> areas) {}
 /** areaId do cliente: o mesmo pedido inteiro é repetição. timeZone vazio usa o fuso do condomínio. */
 public record CreateArea(UUID areaId,String code,String name,String areaKind,UUID blockId,UUID parentAreaId,UUID amenityId,String timeZone,
   String physicalSubtype,String status) {}
 public record UpdateArea(String name,String areaKind,UUID blockId,UUID parentAreaId,UUID amenityId,String timeZone,String physicalSubtype,
   String status,Long expectedVersion) {}
 public record AreaPhysical(Boolean essentialRoute,Boolean physicalBoundaryVerified,Long expectedVersion) {}
 /** Projeção do ponto para gestão e portaria: nome e áreas, sem referências de projeto. */
 public record PointSummary(UUID pointId,String code,String name,UUID fromAreaId,UUID toAreaId,String pointKind,String flowDirection,String status) {}
 public record PointSummaryPage(List<PointSummary> points) {}
 public record Point(UUID pointId,String code,String name,UUID fromAreaId,UUID toAreaId,String pointKind,String flowDirection,String egressClass,
   String accessibleRouteRef,String emergencyDesignRef,String status,long version) {}
 public record PointPage(List<Point> points) {}
 public record CreatePoint(UUID pointId,String code,String name,UUID fromAreaId,UUID toAreaId,String pointKind,String flowDirection,
   String egressClass,String accessibleRouteRef,String emergencyDesignRef,String status) {}
 public record UpdatePoint(String name,UUID fromAreaId,UUID toAreaId,String pointKind,String flowDirection,String egressClass,
   String accessibleRouteRef,String emergencyDesignRef,String status,Long expectedVersion) {}
}
