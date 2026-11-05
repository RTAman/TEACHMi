package com.teachmi.backend.learning;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LearningSessionController.class)
class LearningErrorHandler {
    record ErrorBody(String code, String message, SessionView currentSession) {}

    @ExceptionHandler(LearningException.class)
    ResponseEntity<ErrorBody> learning(LearningException ex) {
        return ResponseEntity.status(ex.status)
                .body(new ErrorBody(ex.code, ex.getMessage(), ex.currentSession));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException ex) {
        return ResponseEntity.unprocessableContent()
                .body(new ErrorBody("INVALID_REQUEST", "요청 항목을 확인하세요.", null));
    }
}
