package br.com.condpulse.gateway.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Autenticação HTTP Digest (RFC 7616; compatível com o exemplo da RFC 2617) respondendo ao desafio
 * {@code WWW-Authenticate} do próprio equipamento. É padrão HTTP, não endpoint de fabricante. Digest autentica, mas
 * não cifra o corpo (manual 15.1): por isso a rede dos leitores fica segregada.
 */
public final class DigestAuth {
 private static final SecureRandom RANDOM=new SecureRandom();
 private final Map<String,String> challenge;
 private int nonceCount;

 private DigestAuth(Map<String,String> challenge) { this.challenge=challenge; }

 /** Interpreta o cabeçalho; devolve null quando não for desafio Digest suportado. */
 public static DigestAuth fromChallenge(String header) {
  if(header==null || !header.regionMatches(true,0,"Digest ",0,7)) return null;
  var params=parse(header.substring(7));
  if(params.get("nonce")==null || params.get("realm")==null) return null;
  String algorithm=params.getOrDefault("algorithm","MD5").toUpperCase();
  if(!algorithm.equals("MD5") && !algorithm.equals("SHA-256")) return null;
  String qop=params.get("qop");
  if(qop!=null) {
   boolean auth=false;
   for(String q:qop.split(",")) if(q.trim().equals("auth")) auth=true;
   if(!auth) return null;
  }
  return new DigestAuth(params);
 }

 public synchronized String authorization(String method,String uri,String username,char[] password) {
  return authorization(method,uri,username,password,HexFormat.of().formatHex(randomBytes()));
 }

 synchronized String authorization(String method,String uri,String username,char[] password,String cnonce) {
  String algorithm=challenge.getOrDefault("algorithm","MD5").toUpperCase();
  String realm=challenge.get("realm"), nonce=challenge.get("nonce");
  boolean qop=challenge.get("qop")!=null;
  nonceCount++;
  String nc=String.format("%08x",nonceCount);
  String ha1=hash(algorithm,username+":"+realm+":"+new String(password));
  String ha2=hash(algorithm,method+":"+uri);
  String response=qop?hash(algorithm,ha1+":"+nonce+":"+nc+":"+cnonce+":auth:"+ha2):hash(algorithm,ha1+":"+nonce+":"+ha2);
  var out=new StringBuilder("Digest username=\"").append(escape(username)).append("\", realm=\"").append(escape(realm))
   .append("\", nonce=\"").append(escape(nonce)).append("\", uri=\"").append(escape(uri)).append("\", response=\"").append(response).append('"');
  if(challenge.containsKey("algorithm")) out.append(", algorithm=").append(algorithm);
  if(qop) out.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
  if(challenge.get("opaque")!=null) out.append(", opaque=\"").append(escape(challenge.get("opaque"))).append('"');
  return out.toString();
 }

 private static byte[] randomBytes() { byte[] b=new byte[16]; RANDOM.nextBytes(b); return b; }
 private static String escape(String s) { return s.replace("\\","\\\\").replace("\"","\\\""); }

 static String hash(String algorithm,String value) {
  try {
   var md=MessageDigest.getInstance(algorithm.equals("SHA-256")?"SHA-256":"MD5");
   return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
  } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
 }

 static Map<String,String> parse(String s) {
  var out=new LinkedHashMap<String,String>();
  int i=0;
  while(i<s.length()) {
   while(i<s.length() && (s.charAt(i)==' ' || s.charAt(i)==',')) i++;
   int eq=s.indexOf('=',i);
   if(eq<0) break;
   String key=s.substring(i,eq).trim().toLowerCase();
   i=eq+1;
   String value;
   if(i<s.length() && s.charAt(i)=='"') {
    var v=new StringBuilder();
    i++;
    while(i<s.length() && s.charAt(i)!='"') {
     if(s.charAt(i)=='\\' && i+1<s.length()) i++;
     v.append(s.charAt(i++));
    }
    i++;
    value=v.toString();
   } else {
    int end=s.indexOf(',',i);
    if(end<0) end=s.length();
    value=s.substring(i,end).trim();
    i=end;
   }
   out.put(key,value);
  }
  return out;
 }
}
