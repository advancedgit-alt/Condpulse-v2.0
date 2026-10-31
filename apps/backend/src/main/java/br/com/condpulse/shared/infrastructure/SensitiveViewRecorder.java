package br.com.condpulse.shared.infrastructure;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * SENSITIVE_VIEWED no Log de Segurança (CPX-011, V050; PRD complementar R5.6): toda consulta sensível pela equipe (log,
 * histórico de acesso do condomínio, cadastro de pessoas e evidência de idade) grava o evento na mesma transação da
 * leitura; se a gravação falha, a leitura falha. Atrás de CP_SECURITY_LOG_ENABLED (padrão false): desligada, não grava.
 * Autoria, papel e horários vêm do banco; details leva só identificadores e contagens, nunca conteúdo pessoal.
 */
@Component @Profile("api")
public class SensitiveViewRecorder {
 /** Origens aceitas para a consulta (subconjunto da coluna source da V050). */
 public static final Set<String> SOURCES=Set.of("MANAGEMENT","CONCIERGE","REMOTE_CENTRAL","PLATFORM");
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;
 private final boolean enabled;

 @Autowired
 public SensitiveViewRecorder(JdbcTemplate jdbc,@Value("${condpulse.security-log.enabled:false}") boolean enabled) {
  this.jdbc=enabled?Objects.requireNonNull(jdbc):jdbc; this.enabled=enabled;
 }
 /** Para construções fora do Spring (testes de serviços existentes): nunca grava. */
 public static SensitiveViewRecorder disabled() { return new SensitiveViewRecorder(null,false); }
 public boolean enabled() { return enabled; }

 /**
  * Grava a consulta no condomínio do contexto da transação corrente. view identifica a tela/rota (ex.: ACCESS_HISTORY);
  * subjectType/subjectId apontam a pessoa, unidade ou recurso consultado quando há um só.
  */
 public void record(UUID tenant,String source,String view,String subjectType,UUID subjectId,UUID unitId,Map<String,?> details) {
  if(!enabled) return;
  Objects.requireNonNull(tenant);
  if(source==null || !SOURCES.contains(source)) throw new IllegalArgumentException("Origem de consulta inválida.");
  if(view==null || !view.matches("[A-Z][A-Z0-9_]{2,39}") || (subjectType!=null && !subjectType.matches("[A-Z][A-Z_]{1,39}")))
   throw new IllegalArgumentException("Consulta sensível inválida.");
  var payload=new TreeMap<String,Object>();
  if(details!=null) details.forEach((k,v)->{ if(k!=null && v!=null) payload.put(k,v); });
  payload.put("view",view);
  jdbc.update("""
   insert into cp.security_log(id,tenant_id,event_type,severity,source,subject_type,subject_id,unit_id,details)
   values(?,?,'SENSITIVE_VIEWED','INFO',?,?,?,?,?::jsonb)
   """,UUID.randomUUID(),tenant,source,subjectType,subjectId,unitId,JSON.writeValueAsString(payload));
 }
}
