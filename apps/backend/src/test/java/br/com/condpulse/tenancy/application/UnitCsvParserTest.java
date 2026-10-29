package br.com.condpulse.tenancy.application;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Mapa de colunas versão 1 da importação de unidades: cabeçalho, aspas, erros por linha e repetição sem diferenciar caixa. */
class UnitCsvParserTest {
 @Test void parsesSemicolonAndCommaFilesWithQuotesAndBom() {
  var parsed=UnitCsvParser.parse("﻿bloco;unidade;codigo_externo\r\nA;101;x1\n\"Bloco \"\"B\"\"\";202;\n\n");
  assertEquals(2,parsed.valid()); assertEquals(0,parsed.invalid()); assertEquals(0,parsed.duplicate());
  assertEquals(new UnitCsvParser.Row(2,"A","101","x1","VALID",null),parsed.rows().get(0));
  assertEquals(new UnitCsvParser.Row(3,"Bloco \"B\"","202",null,"VALID",null),parsed.rows().get(1));
  var comma=UnitCsvParser.parse("Unidade,Bloco\n101,A\n");
  assertEquals(new UnitCsvParser.Row(2,"A","101",null,"VALID",null),comma.rows().getFirst());
  assertEquals(new UnitCsvParser.Row(2,null,"7",null,"VALID",null),UnitCsvParser.parse("unidade\n7").rows().getFirst());
 }

 @Test void reportsErrorsPerLineAndRepeatedUnitsIgnoringCaseAndSpaces() {
  var parsed=UnitCsvParser.parse("bloco;unidade;codigo_externo\nA;"+"y".repeat(41)+";\n"+"b".repeat(61)+";1;\n"
   +"C;  3   01 ;\nD;1;\u0007\nc;3 01;\nF;3\n;;\n");
  assertEquals(java.util.Arrays.asList("UNIT_LABEL_TOO_LONG","BLOCK_NAME_TOO_LONG",null,"IMPORT_ROW_CONTROL_CHARACTER",null,
   "IMPORT_ROW_COLUMNS_INVALID","UNIT_LABEL_REQUIRED"),parsed.rows().stream().map(UnitCsvParser.Row::errorCode).toList());
  assertEquals("3 01",parsed.rows().get(2).unit(),"espaços internos normalizados");
  assertEquals("DUPLICATE",parsed.rows().get(4).outcome(),"mesmo bloco e unidade com outra caixa");
  assertNull(parsed.rows().getFirst().unit(),"valor fora do limite não é gravado");
  assertEquals(1,parsed.valid()); assertEquals(5,parsed.invalid()); assertEquals(1,parsed.duplicate());
 }

 @Test void sameContentHasTheSameHash() {
  String content="unidade\n1\n";
  assertEquals(UnitCsvParser.parse(content).fileHash(),UnitCsvParser.parse(content).fileHash());
  assertNotEquals(UnitCsvParser.parse(content).fileHash(),UnitCsvParser.parse(content+"\n").fileHash());
  assertTrue(UnitCsvParser.parse(content).fileHash().matches("[0-9a-f]{64}"));
 }

 @Test void refusesTheWholeFileWhenTheHeaderOrSizeIsWrong() {
  for(String content:new String[]{"","casa;unidade\n1","bloco;bloco;unidade\n1","bloco\n1","\nunidade\n1"})
   assertEquals("IMPORT_HEADER_INVALID".equals(code(content))||"IMPORT_FILE_EMPTY".equals(code(content)),true,content);
  assertEquals("IMPORT_FILE_EMPTY",code("unidade\n\n"));
  assertEquals("IMPORT_FILE_TOO_LARGE",code("unidade\n"+"1\n".repeat(140_000)));
  assertEquals("IMPORT_TOO_MANY_ROWS",code("unidade\n"+"1\n".repeat(2001)));
 }

 private static String code(String content) {
  try { UnitCsvParser.parse(content); return null; }
  catch(UnitCsvParser.FileProblem problem) { return problem.code(); }
 }
}
