package br.com.condpulse.files.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import br.com.condpulse.files.application.AttachmentStorage;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Supabase Storage pela API REST, com a chave de serviço só no servidor (nunca enviada ao navegador, a logs ou a
 * respostas). Bucket privado; o caminho é sempre {condomínio}/{anexo}. URLs assinadas devolvidas pelo provedor são
 * relativas e só são aceitas no formato esperado, ligadas ao mesmo bucket e chave.
 */
public final class SupabaseAttachmentStorage implements AttachmentStorage {
 private static final Pattern BUCKET=Pattern.compile("[a-z0-9][a-z0-9-]{1,62}");
 private static final Pattern KEY=Pattern.compile("[0-9a-f-]{36}/[0-9a-f-]{36}");
 private static final Pattern TOKEN=Pattern.compile("[A-Za-z0-9._~-]{16,4096}");
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final HttpClient http;
 private final URI base;
 private final String serviceKey;

 public SupabaseAttachmentStorage(HttpClient http,URI base,String serviceKey) {
  if(base==null || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()))
     || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null
     || !(base.getPath()==null || base.getPath().isEmpty()))
   throw new IllegalArgumentException("Endereço do Storage inválido.");
  if(serviceKey==null || serviceKey.isBlank() || serviceKey.length()<20)
   throw new IllegalArgumentException("Configure a chave de serviço do Storage.");
  this.http=http; this.base=base; this.serviceKey=serviceKey;
 }

 @Override public URI signUpload(String bucket,String key) {
  Map<?,?> body=postJson("/storage/v1/object/upload/sign/"+path(bucket,key),"{}");
  return signed(text(body,"url"),"/object/upload/sign/"+path(bucket,key));
 }

 @Override public Optional<StoredObject> inspect(String bucket,String key,long limit) {
  if(limit<1) throw new IllegalArgumentException("Limite inválido.");
  var request=HttpRequest.newBuilder(base.resolve("/storage/v1/object/authenticated/"+path(bucket,key)))
   .timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+serviceKey).header("apikey",serviceKey).GET().build();
  try {
   HttpResponse<InputStream> response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
   try(InputStream stream=response.body()) {
    if(response.statusCode()==404 || response.statusCode()==400 && missing(stream)) return Optional.empty();
    if(response.statusCode()!=200) throw new StorageUnavailable();
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    byte[] head=new byte[16]; int headLength=0; long size=0; byte[] buffer=new byte[64*1024];
    for(int read;(read=stream.read(buffer))!=-1;) {
     if(headLength<head.length) {
      int copy=Math.min(read,head.length-headLength);
      System.arraycopy(buffer,0,head,headLength,copy); headLength+=copy;
     }
     digest.update(buffer,0,read); size+=read;
     if(size>limit) break; // Maior que o declarado: não precisa ler o resto.
    }
    byte[] exact=new byte[headLength]; System.arraycopy(head,0,exact,0,headLength);
    return Optional.of(new StoredObject(size,exact,HexFormat.of().formatHex(digest.digest())));
   }
  } catch(InterruptedException interrupted) {
   Thread.currentThread().interrupt(); throw new StorageUnavailable();
  } catch(IOException | NoSuchAlgorithmException error) { throw new StorageUnavailable(); }
 }

 @Override public URI signDownload(String bucket,String key,Duration lifetime,String downloadName) {
  long seconds=lifetime.toSeconds();
  if(seconds<10 || seconds>600) throw new IllegalArgumentException("Prazo do link inválido.");
  Map<?,?> body=postJson("/storage/v1/object/sign/"+path(bucket,key),"{\"expiresIn\":"+seconds+"}");
  URI signed=signed(text(body,"signedURL"),"/object/sign/"+path(bucket,key));
  return URI.create(signed+"&download="+URLEncoder.encode(downloadName,StandardCharsets.UTF_8).replace("+","%20"));
 }

 private static String text(Map<?,?> body,String field) {
  return body.get(field) instanceof String value?value:"";
 }
 private Map<?,?> postJson(String path,String json) {
  var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
   .header("Authorization","Bearer "+serviceKey).header("apikey",serviceKey).header("Content-Type","application/json")
   .POST(HttpRequest.BodyPublishers.ofString(json)).build();
  try {
   HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()!=200 || response.body()==null || response.body().length()>16384) throw new StorageUnavailable();
   return JSON.readValue(response.body(),Map.class);
  } catch(InterruptedException interrupted) {
   Thread.currentThread().interrupt(); throw new StorageUnavailable();
  } catch(StorageUnavailable unavailable) { throw unavailable;
  } catch(Exception error) { throw new StorageUnavailable(); }
 }
 /** Aceita só "{prefixo}{bucket}/{chave}?token=..." devolvido pelo provedor e monta a URL absoluta no mesmo host. */
 private URI signed(String relative,String expectedPrefix) {
  String prefix=expectedPrefix+"?token=";
  if(!relative.startsWith(prefix) || !TOKEN.matcher(relative.substring(prefix.length())).matches())
   throw new StorageUnavailable();
  return base.resolve("/storage/v1"+relative);
 }
 private static String path(String bucket,String key) {
  if(bucket==null || !BUCKET.matcher(bucket).matches() || key==null || !KEY.matcher(key).matches())
   throw new IllegalArgumentException("Caminho de objeto inválido.");
  return bucket+"/"+key;
 }
 /** O Storage responde 400 com statusCode "404" no corpo para objeto inexistente. */
 private static boolean missing(InputStream stream) throws IOException {
  byte[] body=stream.readNBytes(4096);
  try {
   Map<?,?> node=JSON.readValue(body,Map.class);
   return "404".equals(String.valueOf(node.get("statusCode"))) || "not_found".equals(String.valueOf(node.get("error")));
  } catch(Exception invalid) { return false; }
 }
 @Override public String toString() { return "SupabaseAttachmentStorage[base="+base+",credentials=REDACTED]"; }
}
