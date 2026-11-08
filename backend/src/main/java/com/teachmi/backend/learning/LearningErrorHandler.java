package com.teachmi.backend.learning;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice(assignableTypes = LearningSessionController.class)
class LearningErrorHandler {
    record ErrorBody(String code, String message, SessionView currentSession,
                     @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                     GenerationRecovery recovery) {
        ErrorBody(String code,String message,SessionView session) { this(code,message,session,null); }
    }

    @ExceptionHandler(LearningException.class)
    ResponseEntity<ErrorBody> learning(LearningException ex) {
        return ResponseEntity.status(ex.status)
                .body(new ErrorBody(ex.code, ex.getMessage(), ex.currentSession,ex.recovery));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ErrorBody> validation(Exception ex) {
        return ResponseEntity.unprocessableContent()
                .body(new ErrorBody("INVALID_REQUEST", "요청 항목을 확인하세요.", null));
    }
}
