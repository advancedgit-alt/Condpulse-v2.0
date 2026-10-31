package br.com.condpulse.gateway.support;

/**
 * Servidor que lê o pedido inteiro e fecha a conexão sem responder: é o caso em que o HttpClient do JDK reenvia
 * GET/PUT sozinho. Conta quantos pedidos chegaram.
 */
public final class DropAfterRequestServer implements AutoCloseable {
 private final java.net.ServerSocket socket=new java.net.ServerSocket(0,50,java.net.InetAddress.getLoopbackAddress());
 public final java.util.concurrent.atomic.AtomicInteger received=new java.util.concurrent.atomic.AtomicInteger();
 private final Thread loop;
 public DropAfterRequestServer() throws java.io.IOException {
  loop=new Thread(()->{
   while(!socket.isClosed()) {
    try(var client=socket.accept()) {
     var in=client.getInputStream();
     var head=new StringBuilder();
     int b;
     while((b=in.read())>=0) { head.append((char)b); if(head.toString().endsWith("\r\n\r\n")) break; }
     var m=java.util.regex.Pattern.compile("(?i)content-length: *(\\d+)").matcher(head);
     if(m.find()) in.readNBytes(Integer.parseInt(m.group(1)));
     if(head.length()>0) received.incrementAndGet();
    } catch(java.io.IOException ignored) {}
   }
  },"drop-after-request");
  loop.setDaemon(true); loop.start();
 }
 public java.net.URI base() { return java.net.URI.create("http://127.0.0.1:"+socket.getLocalPort()); }
 @Override public void close() throws java.io.IOException { socket.close(); }
}
