package br.com.condpulse.gateway.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * Origem dos segredos do gateway: senha de fabricante, token de entrada do equipamento (modo nuvem) e senha do
 * keystore mTLS. O arquivo de configuração só guarda a referência ({@code credentialRef}); o valor vem de cofre ou de
 * arquivo montado em tempo de execução (Docker/Kubernetes secrets). Nenhum valor é registrado em log: os tipos
 * devolvidos têm {@code toString} mascarado.
 */
public interface SecretProvider {
 Optional<char[]> secret(String ref);

 /** Usuário e senha de um equipamento; o valor do segredo é "usuario:senha" (sem quebra de linha). */
 default Optional<DeviceCredential> deviceCredential(String ref) {
  return secret(ref).map(value->{
   int sep=-1;
   for(int i=0;i<value.length;i++) if(value[i]==':') { sep=i; break; }
   if(sep<=0) { Arrays.fill(value,'\0'); throw new IllegalStateException("Segredo de equipamento fora do formato usuario:senha: "+ref); }
   var credential=new DeviceCredential(new String(value,0,sep),Arrays.copyOfRange(value,sep+1,value.length));
   Arrays.fill(value,'\0');
   return credential;
  });
 }

 /** Usuário e senha do equipamento. A senha nunca aparece em {@code toString}. */
 record DeviceCredential(String username,char[] password) {
  public DeviceCredential {
   if(username==null || username.isBlank() || password==null || password.length==0) throw new IllegalArgumentException("Credencial incompleta.");
  }
  public String passwordString() { return new String(password); }
  @Override public String toString() { return "DeviceCredential[username=***, password=***]"; }
 }

 /**
  * Lê cada segredo de um arquivo com o nome da referência dentro de um diretório (ex.: {@code /run/secrets}). A
  * referência aceita só letras, números, ponto, hífen e sublinhado: nunca caminho relativo.
  */
 final class FileSecretProvider implements SecretProvider {
  private final Path directory;
  public FileSecretProvider(Path directory) { this.directory=directory; }
  @Override public Optional<char[]> secret(String ref) {
   if(ref==null || !ref.matches("[A-Za-z0-9._-]{1,120}") || ref.startsWith(".")) throw new IllegalArgumentException("Referência de segredo inválida.");
   Path file=directory.resolve(ref);
   if(!Files.isRegularFile(file)) return Optional.empty();
   try {
    byte[] bytes=Files.readAllBytes(file);
    String text=new String(bytes,StandardCharsets.UTF_8);
    Arrays.fill(bytes,(byte)0);
    int end=text.length();
    while(end>0 && (text.charAt(end-1)=='\n' || text.charAt(end-1)=='\r')) end--;
    return Optional.of(text.substring(0,end).toCharArray());
   } catch(IOException e) {
    throw new IllegalStateException("Segredo ilegível: "+ref);
   }
  }
 }
}
