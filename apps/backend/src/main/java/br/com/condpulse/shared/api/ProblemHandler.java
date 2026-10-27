package br.com.condpulse.shared.api;
import java.net.URI;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice @Profile("api")
public class ProblemHandler {
 @ExceptionHandler(BusinessProblem.class)
 ProblemDetail business(BusinessProblem ex) {
  ProblemDetail p=ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(ex.status()),ex.getMessage());
  p.setType(URI.create("urn:condpulse:problem:"+ex.code()));
  p.setProperty("code",ex.code()); p.setProperty("traceId",UUID.randomUUID().toString());
  return p;
 }
 @ExceptionHandler(DataAccessException.class)
 ProblemDetail dependency(DataAccessException ex) {
  ProblemDetail p=ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
    "Não foi possível consultar seus vínculos. Tente novamente.");
  p.setProperty("code","DEPENDENCY_UNAVAILABLE"); p.setProperty("traceId",UUID.randomUUID().toString());
  return p;
 }
}
