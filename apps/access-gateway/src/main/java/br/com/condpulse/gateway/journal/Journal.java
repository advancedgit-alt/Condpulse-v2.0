package br.com.condpulse.gateway.journal;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import br.com.condpulse.gateway.json.Json;

/**
 * Diário local só de acréscimo (uma linha JSON por registro), gravado com fsync antes de a operação seguir. É a base
 * da fila idempotente, dos recibos de abertura e da fila de eventos: reinício reconstrói o estado relendo o diário.
 * Uma cauda incompleta (queda no meio da escrita: sem quebra de linha final ou JSON inválido na última linha) é
 * truncada ao abrir, antes de qualquer acréscimo, para que o próximo registro não fique colado nela; linha
 * corrompida no meio interrompe a carga.
 */
public final class Journal implements AutoCloseable {
 private final Path file;
 private final FileChannel channel;

 public Journal(Path file) {
  this.file=file;
  try {
   Files.createDirectories(file.getParent());
   truncateIncompleteTail(file);
   channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND);
  } catch(IOException e) { throw new IllegalStateException("Diário local indisponível: "+file.getFileName()); }
 }

 /** Corta o arquivo no fim do último registro válido terminado em quebra de linha. */
 static void truncateIncompleteTail(Path file) throws IOException {
  if(!Files.exists(file)) return;
  byte[] bytes=Files.readAllBytes(file);
  int end=bytes.length;
  if(end==0) return;
  int lastNewline=-1;
  for(int i=end-1;i>=0;i--) if(bytes[i]=='\n') { lastNewline=i; break; }
  int valid=lastNewline+1; // tudo depois da última quebra de linha é cauda incompleta
  if(valid>0) {
   // A última linha completa também precisa ser JSON válido; se não for, era escrita parcial seguida de lixo.
   int start=valid-1;
   while(start>0 && bytes[start-1]!='\n') start--;
   String last=new String(bytes,start,valid-1-start,StandardCharsets.UTF_8);
   if(!last.isBlank()) {
    try { Json.object(last); } catch(Json.JsonException e) { valid=start; }
   }
  }
  if(valid<end) {
   try(var ch=FileChannel.open(file,StandardOpenOption.WRITE)) { ch.truncate(valid); ch.force(true); }
  }
 }

 public synchronized void append(Map<String,Object> record) {
  byte[] line=(Json.write(record)+"\n").getBytes(StandardCharsets.UTF_8);
  try {
   var buffer=ByteBuffer.wrap(line);
   while(buffer.hasRemaining()) channel.write(buffer);
   channel.force(true);
  } catch(IOException e) {
   // Sem gravação durável não há operação: quem chamou não pode seguir como se tivesse registrado.
   throw new IllegalStateException("Falha ao gravar o diário local: "+file.getFileName());
  }
 }

 public void replay(Consumer<Map<String,Object>> consumer) {
  if(!Files.exists(file)) return;
  List<String> lines=new ArrayList<>();
  try(BufferedReader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
   String line;
   while((line=reader.readLine())!=null) lines.add(line);
  } catch(IOException e) { throw new IllegalStateException("Diário local ilegível: "+file.getFileName()); }
  for(int i=0;i<lines.size();i++) {
   String line=lines.get(i);
   if(line.isBlank()) continue;
   Map<String,Object> record;
   try { record=Json.object(line); }
   catch(Json.JsonException e) {
    if(i==lines.size()-1) break;
    throw new IllegalStateException("Diário local corrompido na linha "+(i+1)+": "+file.getFileName());
   }
   consumer.accept(record);
  }
 }

 @Override public synchronized void close() {
  try { channel.close(); } catch(IOException ignored) {}
 }
}
