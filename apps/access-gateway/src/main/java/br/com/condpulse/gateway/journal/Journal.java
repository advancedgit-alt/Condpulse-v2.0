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
 * Uma última linha incompleta (queda no meio da escrita) é ignorada; linha corrompida no meio interrompe a carga.
 */
public final class Journal implements AutoCloseable {
 private final Path file;
 private final FileChannel channel;

 public Journal(Path file) {
  this.file=file;
  try {
   Files.createDirectories(file.getParent());
   channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND);
  } catch(IOException e) { throw new IllegalStateException("Diário local indisponível: "+file.getFileName()); }
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
