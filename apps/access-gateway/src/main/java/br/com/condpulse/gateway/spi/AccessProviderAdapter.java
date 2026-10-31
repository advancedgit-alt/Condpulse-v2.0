package br.com.condpulse.gateway.spi;

import java.util.Optional;
import java.util.Set;
import br.com.condpulse.gateway.spi.Spi.*;

/**
 * Contrato comum dos adaptadores (AC-019; manual cap. 15, "AccessProviderPort"). Uma instância atende um equipamento
 * comissionado. Toda operação devolve resultado explícito; operação que a documentação oficial pública consultada não
 * cobre devolve {@link OperationState#NAO_SUPORTADO_SEM_DOCUMENTACAO} e aparece no manifesto como não implementada.
 * O adaptador nunca recebe URL, método ou corpo livre: só operações tipadas e canais já cadastrados.
 */
public interface AccessProviderAdapter {
 Manifest manifest();

 /** Firmware, SKU e série reais, para confrontar com o perfil homologado. */
 DeviceIdentity identify();

 OperationResult upsertPerson(PersonSpec person);
 /** Remove a pessoa e, quando o manifesto declarar, tudo o que depende dela no equipamento. */
 OperationResult removePerson(String subjectRef);
 OperationResult enrollFace(FaceSpec face);
 OperationResult removeFace(String subjectRef);
 OperationResult setCredential(CredentialSpec credential);
 OperationResult removeCredential(String subjectRef,CredentialKind kind);
 /** Substitui os direitos da pessoa pelos informados (restrição antes de ampliação). */
 OperationResult applyRights(RightsSpec rights);
 OperationResult revokeRights(String subjectRef);
 AppliedState readState(String subjectRef);
 /**
  * O valor exato desta credencial está vinculado à pessoa no equipamento? Vazio quando o adaptador não consegue
  * comparar o valor (só contar não basta para confirmar substituição).
  */
 default Optional<Boolean> credentialPresent(CredentialSpec credential) { return Optional.empty(); }
 /** Campos da pessoa que {@link #upsertPerson} grava, para comparar com a leitura de volta. Vazio: não comparável. */
 default Optional<java.util.Map<String,String>> personAttributes(PersonSpec person) { return Optional.empty(); }
 /** Chaves nativas que {@link #applyRights} gravaria, para comparar com a leitura de volta. Vazio se não representável. */
 Optional<Set<String>> rightKeys(RightsSpec rights);
 OpenResult open(OpenAction action);
 /** Busca de eventos por cursor (backfill). */
 EventPage fetchEvents(String cursor,int limit);
 HealthReport health();

 /** Libera recursos de fundo (ex.: assinatura contínua de eventos). */
 default void shutdown() {}

 /** Ponto de entrada para chamadas iniciadas pelo próprio equipamento (modo online/push/nuvem), quando houver. */
 default Optional<InboundEndpoint> inbound() { return Optional.empty(); }

 /**
  * true somente quando a fonte oficial declara que {@link #removePerson} também retira cartões, PIN, QR e face. Sem
  * essa declaração o núcleo retira cada método separadamente.
  */
 default boolean removePersonCoversCredentials() { return false; }

 /** true somente quando a fonte oficial declara que {@link #removePerson} também retira os direitos da pessoa. */
 default boolean removePersonCoversRights() { return false; }

 /**
  * true quando {@link #rightKeys} e a leitura de volta incluem os limites temporais (início/fim) aplicados. Sem
  * isso, direitos com prazo nunca são confirmados por leitura (ficam não verificados).
  */
 default boolean rightKeysIncludeValidity() { return false; }
}
