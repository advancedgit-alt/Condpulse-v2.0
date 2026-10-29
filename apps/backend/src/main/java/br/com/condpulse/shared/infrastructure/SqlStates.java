package br.com.condpulse.shared.infrastructure;

import java.sql.SQLException;

/** Localiza o SQLSTATE original depois do rollback, sem expor mensagem ou SQL ao cliente. */
public final class SqlStates {
 private SqlStates() {}
 public static String of(Throwable error) {
  for(Throwable cause=error;cause!=null;cause=cause.getCause())
   if(cause instanceof SQLException sql && sql.getSQLState()!=null) return sql.getSQLState();
  return null;
 }
}
