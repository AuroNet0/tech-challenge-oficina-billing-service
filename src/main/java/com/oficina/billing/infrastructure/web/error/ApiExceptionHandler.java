package com.oficina.billing.infrastructure.web.error;

import java.net.URI;
import java.sql.SQLException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import com.oficina.billing.application.exception.ResourceNotFoundException;
import com.oficina.billing.domain.exception.DomainException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> notFound(ResourceNotFoundException exception, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler({DomainException.class, DataIntegrityViolationException.class, ConcurrencyFailureException.class})
    ResponseEntity<Object> conflict(Exception exception, WebRequest request) {
        return problem(HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler(TransactionSystemException.class)
    ResponseEntity<Object> transactionFailure(TransactionSystemException exception, WebRequest request) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("23") || sql.getSQLState().equals("40001")
                    || sql.getSQLState().equals("40P01"))) {
                return problem(HttpStatus.CONFLICT, request);
            }
        }
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(detail(status, request), headers, status);
    }

    private ResponseEntity<Object> problem(HttpStatus status, WebRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail(status, request));
    }

    private ProblemDetail detail(HttpStatusCode status, WebRequest request) {
        String message = switch (status.value()) {
            case 400 -> "Requisicao invalida.";
            case 404 -> "Recurso nao encontrado.";
            case 409 -> "Operacao em conflito com o estado atual do recurso.";
            case 405 -> "Metodo nao permitido.";
            case 415 -> "Tipo de conteudo nao suportado.";
            default -> "Nao foi possivel concluir a requisicao.";
        };
        var problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setInstance(URI.create(((ServletWebRequest) request).getRequest().getRequestURI()));
        return problem;
    }
}
