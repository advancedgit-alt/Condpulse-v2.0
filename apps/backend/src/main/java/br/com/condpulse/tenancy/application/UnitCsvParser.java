package br.com.condpulse.tenancy.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Mapa de colunas versão 1 da importação de unidades (CP-016): cabeçalho obrigatório com {@code unidade} e, opcionais,
 * {@code bloco} e {@code codigo_externo}, separados por ponto e vírgula ou vírgula (o do cabeçalho vale para o arquivo),
 * campos entre aspas duplas com aspas dobradas. Puro: não toca banco nem decide o que já existe no condomínio.
 * Linha repetida (mesmo bloco e unidade, sem diferenciar caixa e espaços) fica como DUPLICATE depois da primeira.
 */
public final class UnitCsvParser {
 public static final int MAX_CONTENT_CHARS=256*1024;
 public static final int MAX_ROWS=2000;
 public static final int MAPPING_VERSION=1;
 private static final Map<String,String> HEADERS=Map.of("bloco","block","unidade","unit","codigo_externo","external");

 private UnitCsvParser() {}

 public record Row(int line,String block,String unit,String externalId,String outcome,String errorCode) {}
 public record Parsed(String fileHash,List<Row> rows,int valid,int invalid,int duplicate) {
  public Parsed { rows=List.copyOf(rows); }
 }
 /** Problema do arquivo inteiro (cabeçalho, tamanho, quantidade de linhas): nada é gravado. */
 public static final class FileProblem extends RuntimeException {
  private final String code;
  public FileProblem(String code,String message) { super(message); this.code=code; }
  public String code() { return code; }
 }

 public static Parsed parse(String content) {
  if(content==null || content.isBlank()) throw new FileProblem("IMPORT_FILE_EMPTY","O arquivo está vazio.");
  if(content.length()>MAX_CONTENT_CHARS)
   throw new FileProblem("IMPORT_FILE_TOO_LARGE","O arquivo passa de 256 KB. Divida em lotes menores.");
  String hash=sha256(content);
  String text=content.startsWith("﻿")?content.substring(1):content;
  List<String> lines=splitLines(text);
  int headerIndex=0;
  while(headerIndex<lines.size() && lines.get(headerIndex).isBlank()) headerIndex++;
  if(headerIndex!=0 || lines.isEmpty())
   throw new FileProblem("IMPORT_HEADER_INVALID","A primeira linha precisa ser o cabeçalho: bloco;unidade;codigo_externo.");
  String header=lines.getFirst();
  char separator=header.indexOf(';')>=0?';':',';
  List<String> names=fields(header,separator).orElseThrow(()->headerProblem());
  int block=-1,unit=-1,external=-1;
  Set<String> seen=new HashSet<>();
  for(int i=0;i<names.size();i++) {
   String name=names.get(i).strip().toLowerCase(Locale.ROOT);
   String key=HEADERS.get(name);
   if(key==null || !seen.add(key)) throw headerProblem();
   switch(key) { case "block" -> block=i; case "unit" -> unit=i; default -> external=i; }
  }
  if(unit<0) throw headerProblem();
  List<Row> rows=new ArrayList<>();
  Set<String> keys=new HashSet<>();
  int valid=0,invalid=0,duplicate=0;
  for(int index=1;index<lines.size();index++) {
   String line=lines.get(index);
   if(line.isBlank()) continue;
   int number=index+1;
   // Linhas vazias não viram registro, mas contam na posição: a posição gravada nunca passa de 2.001 (cabeçalho + 2.000).
   if(rows.size()==MAX_ROWS || number>MAX_ROWS+1)
    throw new FileProblem("IMPORT_TOO_MANY_ROWS","O arquivo passa de 2.000 linhas. Divida em lotes menores.");
   var parsed=fields(line,separator);
   if(parsed.isEmpty()) { rows.add(new Row(number,null,null,null,"INVALID","IMPORT_ROW_QUOTES_INVALID")); invalid++; continue; }
   List<String> values=parsed.get();
   if(values.size()!=names.size()) { rows.add(new Row(number,null,null,null,"INVALID","IMPORT_ROW_COLUMNS_INVALID")); invalid++; continue; }
   String blockName=block<0?null:normalize(values.get(block));
   String unitLabel=normalize(values.get(unit));
   String externalId=external<0?null:normalize(values.get(external));
   String error=null;
   if(hasControl(values)) error="IMPORT_ROW_CONTROL_CHARACTER";
   else if(unitLabel==null) error="UNIT_LABEL_REQUIRED";
   else if(unitLabel.length()>40) error="UNIT_LABEL_TOO_LONG";
   else if(blockName!=null && blockName.length()>60) error="BLOCK_NAME_TOO_LONG";
   else if(externalId!=null && externalId.length()>80) error="EXTERNAL_ID_TOO_LONG";
   if(error!=null) {
    rows.add(new Row(number,fit(blockName,60),fit(unitLabel,40),fit(externalId,80),"INVALID",error)); invalid++; continue;
   }
   String key=(blockName==null?"":blockName.toLowerCase(Locale.ROOT))+"\u0000"+unitLabel.toLowerCase(Locale.ROOT);
   if(!keys.add(key)) { rows.add(new Row(number,blockName,unitLabel,externalId,"DUPLICATE",null)); duplicate++; continue; }
   rows.add(new Row(number,blockName,unitLabel,externalId,"VALID",null)); valid++;
  }
  if(rows.isEmpty()) throw new FileProblem("IMPORT_FILE_EMPTY","O arquivo não tem linhas depois do cabeçalho.");
  return new Parsed(hash,rows,valid,invalid,duplicate);
 }

 static String sha256(String content) {
  try {
   return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
  } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
 }
 private static FileProblem headerProblem() {
  return new FileProblem("IMPORT_HEADER_INVALID","Cabeçalho inválido. Use as colunas bloco, unidade e codigo_externo (unidade é obrigatória), sem repetir.");
 }
 private static List<String> splitLines(String text) {
  // Quebra só fora de aspas; \r\n e \r viram fim de linha.
  List<String> lines=new ArrayList<>();
  StringBuilder current=new StringBuilder();
  boolean quoted=false;
  for(int i=0;i<text.length();i++) {
   char c=text.charAt(i);
   if(c=='"') quoted=!quoted;
   if(!quoted && (c=='\n' || c=='\r')) {
    if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n') i++;
    lines.add(current.toString()); current.setLength(0);
   } else current.append(c);
  }
  if(!current.isEmpty()) lines.add(current.toString());
  return lines;
 }
 /** Campos da linha; vazio quando as aspas não fecham ou há texto depois da aspa final. */
 static Optional<List<String>> fields(String line,char separator) {
  List<String> values=new ArrayList<>();
  StringBuilder value=new StringBuilder();
  int i=0;
  while(true) {
   value.setLength(0);
   if(i<line.length() && line.charAt(i)=='"') {
    i++;
    boolean closed=false;
    while(i<line.length()) {
     char c=line.charAt(i++);
     if(c=='"') {
      if(i<line.length() && line.charAt(i)=='"') { value.append('"'); i++; }
      else { closed=true; break; }
     } else value.append(c);
    }
    if(!closed) return Optional.empty();
    while(i<line.length() && line.charAt(i)==' ') i++;
    if(i<line.length() && line.charAt(i)!=separator) return Optional.empty();
   } else {
    while(i<line.length() && line.charAt(i)!=separator) {
     if(line.charAt(i)=='"') return Optional.empty();
     value.append(line.charAt(i++));
    }
   }
   values.add(value.toString());
   if(i>=line.length()) return Optional.of(values);
   i++;
   if(i==line.length()) { values.add(""); return Optional.of(values); }
  }
 }
 private static String normalize(String value) {
  String normalized=value.strip().replaceAll("\\s+"," ");
  return normalized.isEmpty()?null:normalized;
 }
 private static boolean hasControl(List<String> values) {
  return values.stream().anyMatch(v->v.strip().replaceAll("\\s+"," ").chars().anyMatch(Character::isISOControl));
 }
 private static String fit(String value,int maximum) {
  return value==null || value.length()>maximum || value.chars().anyMatch(Character::isISOControl)?null:value;
 }
}
