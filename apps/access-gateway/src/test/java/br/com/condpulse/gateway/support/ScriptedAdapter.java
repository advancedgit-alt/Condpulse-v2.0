package br.com.condpulse.gateway.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import br.com.condpulse.gateway.spi.BaseAdapter;
import br.com.condpulse.gateway.spi.Spi.*;

/**
 * Adaptador de teste do núcleo: estado em memória e respostas programáveis por operação. Não é fabricante.
 */
public class ScriptedAdapter extends BaseAdapter {
 public final Set<Operation> supported;
 public final Map<Operation,Deque<OperationResult>> scripted=new EnumMap<>(Operation.class);
 public final List<String> calls=new ArrayList<>();
 public final Deque<OpenResult> openResults=new ArrayDeque<>();
 public final Deque<AppliedState> readStates=new ArrayDeque<>();
 public boolean personPresent;
 public String personName;
 public Set<String> rights=new HashSet<>();
 public boolean coversCredentials;
 /** Resposta de credentialPresent (vazio = só sabe contar). */
 public Optional<Boolean> credentialMatch=Optional.empty();
 /** Faz readState lançar exceção (leitura indisponível). */
 public boolean readStateThrows;
 public final Map<CapabilityName,Boolean> documented=new EnumMap<>(CapabilityName.class);
 /** Captura o contexto de entrada entregue pelo núcleo (testes de decisão local). */
 public java.util.function.Consumer<br.com.condpulse.gateway.spi.InboundEndpoint.Context> inboundCapture=ctx->{};

 public ScriptedAdapter(DeviceContext context,Set<Operation> supported) {
  super(context);
  this.supported=EnumSet.copyOf(supported);
  for(CapabilityName c:CapabilityName.values()) documented.put(c,true);
 }
 public static Set<Operation> all() { return EnumSet.allOf(Operation.class); }

 public ScriptedAdapter script(Operation op,OperationResult... results) {
  scripted.computeIfAbsent(op,k->new ArrayDeque<>()).addAll(List.of(results));
  return this;
 }
 private OperationResult next(Operation op,OperationResult fallback) {
  calls.add(op.name());
  if(!supported.contains(op)) return OperationResult.notSupported(op);
  var q=scripted.get(op);
  return q==null || q.isEmpty()?fallback:q.poll();
 }

 @Override public Manifest manifest() {
  var ops=new EnumMap<Operation,OperationSupport>(Operation.class);
  for(Operation op:supported) ops.put(op,OperationSupport.implemented(op,List.of(new Source("TESTE","https://example.invalid/teste","sintético"))));
  return new Manifest(device().brand(),"scripted",device().connectionMode(),ops,documented);
 }
 @Override public boolean removePersonCoversCredentials() { return coversCredentials; }
 public boolean coversRights;
 @Override public boolean removePersonCoversRights() { return coversRights; }

 @Override public OperationResult upsertPerson(PersonSpec p) {
  var r=next(Operation.UPSERT_PERSON,OperationResult.of(OperationState.APPLIED_UNVERIFIED,null));
  if(r.state()!=OperationState.FAILED && r.state()!=OperationState.UNKNOWN) { personPresent=true; personName=p.displayName(); }
  return r;
 }
 @Override public Optional<Map<String,String>> personAttributes(PersonSpec p) { return Optional.of(Map.of("name",p.displayName())); }
 @Override public OperationResult removePerson(String s) { var r=next(Operation.REMOVE_PERSON,OperationResult.of(OperationState.APPLIED_UNVERIFIED,null)); personPresent=false; rights.clear(); return r; }
 @Override public OperationResult enrollFace(FaceSpec f) { return next(Operation.ENROLL_FACE,OperationResult.of(OperationState.APPLIED_UNVERIFIED,null)); }
 @Override public OperationResult removeFace(String s) { return next(Operation.REMOVE_FACE,OperationResult.of(OperationState.APPLIED_UNVERIFIED,null)); }
 @Override public OperationResult setCredential(CredentialSpec c) { return next(setOperation(c.kind()),OperationResult.of(OperationState.APPLIED_UNVERIFIED,null)); }
 @Override public OperationResult removeCredential(String s,CredentialKind k) { return next(removeOperation(k),OperationResult.of(OperationState.APPLIED_UNVERIFIED,null)); }
 @Override public OperationResult applyRights(RightsSpec r) {
  var res=next(Operation.APPLY_RIGHTS,OperationResult.of(OperationState.APPLIED_UNVERIFIED,null));
  if(res.state()==OperationState.APPLIED_UNVERIFIED || res.state()==OperationState.UNKNOWN) rights=rightKeys(r).orElseThrow();
  return res;
 }
 @Override public OperationResult revokeRights(String s) { var r=next(Operation.REVOKE_RIGHTS,OperationResult.of(OperationState.APPLIED_UNVERIFIED,null)); rights.clear(); return r; }
 @Override public Optional<Set<String>> rightKeys(RightsSpec r) {
  var out=new HashSet<String>();
  for(Right x:r.rights()) out.add(x.channelId()+"@"+x.scheduleRef());
  return Optional.of(out);
 }
 @Override public Optional<Boolean> credentialPresent(CredentialSpec c) { calls.add("CREDENTIAL_PRESENT"); return credentialMatch; }
 @Override public AppliedState readState(String s) {
  calls.add("READ_STATE");
  if(readStateThrows) throw new IllegalStateException("leitura indisponível");
  if(!readStates.isEmpty()) return readStates.poll();
  return new AppliedState(OperationState.CONFIRMED,null,personPresent,Set.copyOf(rights),Map.of(CredentialKind.CARD,0,CredentialKind.PIN,0,CredentialKind.QR,0),false,
   personName==null?null:Map.of("name",personName));
 }
 @Override public OpenResult open(OpenAction a) {
  calls.add("OPEN");
  return openResults.isEmpty()?new OpenResult(OpenOutcome.ACCEPTED,null):openResults.poll();
 }
 @Override public Optional<br.com.condpulse.gateway.spi.InboundEndpoint> inbound() {
  return Optional.of((request,ctx)->{ inboundCapture.accept(ctx); return br.com.condpulse.gateway.spi.InboundEndpoint.Response.empty(); });
 }
 @Override public HealthReport health() { calls.add("HEALTH"); return new HealthReport(true,null,null,null,null); }
}
