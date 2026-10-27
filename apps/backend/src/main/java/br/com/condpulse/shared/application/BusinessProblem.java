package br.com.condpulse.shared.application;
public final class BusinessProblem extends RuntimeException {
 private final String code; private final int status;
 public BusinessProblem(String code,int status,String message) {
  super(message); this.code=code; this.status=status;
 }
 public String code() { return code; }
 public int status() { return status; }
}
