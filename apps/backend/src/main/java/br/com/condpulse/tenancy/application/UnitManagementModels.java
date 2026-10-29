package br.com.condpulse.tenancy.application;

import java.util.List;
import java.util.UUID;

/** Contratos da gestão local de blocos e unidades. Não expõem moradores nem dados de outro tenant. */
public final class UnitManagementModels {
 private UnitManagementModels() {}
 public record Block(UUID blockId,String name) {}
 public record CreateBlock(String name) {}
 public record Unit(UUID unitId,String label,UUID blockId,String blockName,String status,long version) {}
 public record UnitPage(List<Unit> units,boolean truncated) {
  public UnitPage { units=List.copyOf(units); }
 }
 public record CreateUnit(String label,UUID blockId) {}
 public record UpdateUnit(String label,UUID blockId,String status,Long expectedVersion,String reason) {}
}
