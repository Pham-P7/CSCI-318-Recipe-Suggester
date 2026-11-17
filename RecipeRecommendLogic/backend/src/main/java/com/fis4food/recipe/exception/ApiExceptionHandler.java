package com.fis4food.recipe.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    //handle d1 exceptions
    @ExceptionHandler(D1AccessException.class)
    public ResponseEntity<Map<String, String>> handleDatabase(D1AccessException ex) {
        log.warn("D1 query failed: {}", ex.getMessage());

        // A failed cloud query is not the same as finding no recipes.
        int status = ex.isTimeout() ? 504 : 502;
        String message = ex.isTimeout() ? "Database request timed out" : "Database request failed";
        return ResponseEntity.status(status).body(Map.of("message", message));
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {

        if (ex instanceof ErrorResponse response) {
            return ResponseEntity.status(response.getStatusCode()).body(Map.of("message", "Request failed"));
        }
        log.error("Unexpected recipe backend error", ex);
        return ResponseEntity.internalServerError().body(Map.of("message", "Internal server error"));
    }

    @ExceptionHandler(ResponseStatusException.class)

    public ResponseEntity<Map<String, String>> handleStatus(
            ResponseStatusException ex) {
        String reason = ex.getReason();
        String message = reason == null ? "Request failed" : reason;

        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("message", message));
    }


    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of(
                        "message",
                        "Invalid value for parameter: " + ex.getName()));
    }
}
