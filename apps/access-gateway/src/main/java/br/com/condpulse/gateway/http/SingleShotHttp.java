package br.com.condpulse.gateway.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Uma requisição HTTP/1.1, uma conexão, nenhuma repetição. O {@code java.net.http.HttpClient} reenvia
 * automaticamente métodos idempotentes (GET, PUT) quando a conexão cai depois do envio e antes dos cabeçalhos; para
 * escrita e ação física isso pode executar a mesma ordem duas vezes. Aqui a conexão é aberta, o pedido é escrito uma
 * única vez com {@code Connection: close} e qualquer falha depois do início da escrita é ambígua — nunca reenviada.
 * O prazo total (conexão, envio, cabeçalhos e corpo) é garantido por um fechamento agendado do socket.
 */
final class SingleShotHttp {
 private static final int MAX_HEADER_BYTES=64*1024;

 /** Falha antes de qualquer byte do pedido ter sido escrito: comprovadamente não enviado. */
 static final class NotSentException extends IOException {
  NotSentException(String reason) { super(reason,null); }
 }

 record Result(int status,Map<String,String> headers,byte[] body) {
  String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
 }

 private SingleShotHttp() {}

 static Result exchange(URI uri,String method,String contentType,byte[] body,String authorization,Duration timeout,int maxBody,
     ScheduledExecutorService closer) throws IOException {
  boolean tls="https".equals(uri.getScheme());
  int port=uri.getPort()>0?uri.getPort():(tls?443:80);
  var expired=new AtomicBoolean();
  Socket socket=new Socket(java.net.Proxy.NO_PROXY);
  var deadline=closer.schedule(()->{ expired.set(true); try { socket.close(); } catch(IOException ignored) {} },timeout.toMillis(),TimeUnit.MILLISECONDS);
  boolean writing=false;
  try {
   Socket channel;
   try {
    socket.connect(new InetSocketAddress(uri.getHost(),port),(int)Math.max(1,timeout.toMillis()));
    socket.setSoTimeout((int)Math.max(1,timeout.toMillis()));
    channel=tls?handshake(socket,uri.getHost(),port):socket;
   } catch(UnknownHostException|java.net.ConnectException|SocketTimeoutException e) {
    throw new NotSentException("DEVICE_UNREACHABLE");
   } catch(IOException e) {
    // Conexão ou TLS falhou antes de qualquer byte do pedido: nada foi enviado ao equipamento.
    throw new NotSentException("CONNECTION_FAILED");
   }
   var head=new StringBuilder();
   String target=uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
   head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
   head.append("Host: ").append(uri.getRawAuthority()).append("\r\n");
   head.append("Connection: close\r\n");
   head.append("Accept: */*\r\n");
   if(contentType!=null) head.append("Content-Type: ").append(contentType).append("\r\n");
   if(authorization!=null) head.append("Authorization: ").append(authorization).append("\r\n");
   if(body!=null || !"GET".equals(method)) head.append("Content-Length: ").append(body==null?0:body.length).append("\r\n");
   head.append("\r\n");
   writing=true;
   OutputStream out=channel.getOutputStream();
   out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
   if(body!=null) out.write(body);
   out.flush();
   InputStream in=new BufferedInputStream(channel.getInputStream());
   Result result;
   do { result=readResponse(in,maxBody,method); } while(result.status()>=100 && result.status()<200);
   if(expired.get()) throw new IOException("Prazo da chamada esgotado.");
   return result;
  } catch(NotSentException e) {
   throw e;
  } catch(IOException e) {
   if(!writing && !expired.get()) throw new NotSentException("CONNECTION_FAILED");
   throw expired.get()?new IOException("Prazo da chamada esgotado."):e;
  } finally {
   deadline.cancel(false);
   try { socket.close(); } catch(IOException ignored) {}
  }
 }

 private static Socket handshake(Socket plain,String host,int port) throws IOException {
  SSLContext context;
  try { context=SSLContext.getDefault(); } catch(java.security.NoSuchAlgorithmException e) { throw new IOException("TLS indisponível."); }
  var ssl=(SSLSocket)context.getSocketFactory().createSocket(plain,host,port,true);
  var params=ssl.getSSLParameters();
  params.setEndpointIdentificationAlgorithm("HTTPS");
  if(!host.matches("[0-9.]+") && !host.contains(":")) params.setServerNames(java.util.List.of(new SNIHostName(host)));
  ssl.setSSLParameters(params);
  ssl.startHandshake();
  return ssl;
 }

 private static Result readResponse(InputStream in,int maxBody,String method) throws IOException {
  String statusLine=readLine(in);
  if(statusLine==null) throw new IOException("Conexão encerrada antes da resposta.");
  String[] parts=statusLine.split(" ",3);
  if(parts.length<2 || !parts[0].startsWith("HTTP/1.") || !parts[1].matches("\\d{3}")) throw new IOException("Linha de status inválida.");
  int status=Integer.parseInt(parts[1]);
  var headers=new HashMap<String,String>();
  int total=statusLine.length();
  String line;
  while((line=readLine(in))!=null && !line.isEmpty()) {
   total+=line.length();
   if(total>MAX_HEADER_BYTES) throw new IOException("Cabeçalhos acima do limite.");
   int colon=line.indexOf(':');
   if(colon<=0) throw new IOException("Cabeçalho inválido.");
   headers.putIfAbsent(line.substring(0,colon).trim().toLowerCase(Locale.ROOT),line.substring(colon+1).trim());
  }
  if(line==null) throw new IOException("Conexão encerrada no meio dos cabeçalhos.");
  if((status>=100 && status<200) || status==204 || status==304 || "HEAD".equals(method)) return new Result(status,headers,new byte[0]);
  byte[] body;
  String transfer=headers.get("transfer-encoding");
  String length=headers.get("content-length");
  if(transfer!=null && transfer.toLowerCase(Locale.ROOT).contains("chunked")) body=readChunked(in,maxBody);
  else if(length!=null) {
   long n;
   try { n=Long.parseLong(length); } catch(NumberFormatException e) { throw new IOException("Content-Length inválido."); }
   if(n<0 || n>maxBody) throw new IOException("Resposta acima do limite.");
   body=in.readNBytes((int)n);
   if(body.length!=n) throw new IOException("Corpo incompleto.");
  } else {
   body=in.readNBytes(maxBody+1);
   if(body.length>maxBody) throw new IOException("Resposta acima do limite.");
  }
  return new Result(status,headers,body);
 }

 private static byte[] readChunked(InputStream in,int maxBody) throws IOException {
  var out=new ByteArrayOutputStream();
  while(true) {
   String sizeLine=readLine(in);
   if(sizeLine==null) throw new IOException("Corpo em partes incompleto.");
   int semi=sizeLine.indexOf(';');
   int size;
   try { size=Integer.parseInt((semi<0?sizeLine:sizeLine.substring(0,semi)).trim(),16); } catch(NumberFormatException e) { throw new IOException("Parte inválida."); }
   if(size<0 || out.size()+(long)size>maxBody) throw new IOException("Resposta acima do limite.");
   if(size==0) {
    String trailer;
    while((trailer=readLine(in))!=null && !trailer.isEmpty()) { /* trailers ignorados */ }
    if(trailer==null) throw new IOException("Corpo em partes incompleto.");
    return out.toByteArray();
   }
   byte[] chunk=in.readNBytes(size);
   if(chunk.length!=size) throw new IOException("Corpo em partes incompleto.");
   out.write(chunk);
   String end=readLine(in);
   if(end==null || !end.isEmpty()) throw new IOException("Parte sem terminador.");
  }
 }

 /** Linha terminada em CRLF (ou LF); null se a conexão terminou antes de qualquer byte da linha. */
 private static String readLine(InputStream in) throws IOException {
  var line=new ByteArrayOutputStream();
  int b;
  boolean any=false;
  while((b=in.read())>=0) {
   any=true;
   if(b=='\n') break;
   if(line.size()>=MAX_HEADER_BYTES) throw new IOException("Linha acima do limite.");
   line.write(b);
  }
  if(!any) return null;
  if(b<0) throw new IOException("Linha incompleta.");
  String s=line.toString(StandardCharsets.ISO_8859_1);
  return s.endsWith("\r")?s.substring(0,s.length()-1):s;
 }
}
