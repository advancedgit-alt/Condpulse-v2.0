package br.com.condpulse.files.api;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** O Storage só é procurado no mesmo projeto Supabase do emissor do Auth; qualquer outro endereço impede a subida. */
class FilesConfigurationTest {
 @Test void storageBaseComesOnlyFromTheSupabaseAuthIssuer() {
  assertEquals(URI.create("https://abcdefghijklmnopqrst.supabase.co"),FilesConfiguration.storageBase("https://abcdefghijklmnopqrst.supabase.co/auth/v1"));
  for(String invalid:new String[]{null,"","http://abcdefghijklmnopqrst.supabase.co/auth/v1","https://auth.example.invalid/auth/v1",
    "https://abcdefghijklmnopqrst.supabase.co.evil.invalid/auth/v1","https://abcdefghijklmnopqrst.supabase.co/auth/v2",
    "https://user@abcdefghijklmnopqrst.supabase.co/auth/v1","https://abcdefghijklmnopqrst.supabase.co/auth/v1?x=1"})
   assertThrows(IllegalArgumentException.class,()->FilesConfiguration.storageBase(invalid),String.valueOf(invalid));
 }
}
