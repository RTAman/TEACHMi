package com.teachmi.backend.assessment;

import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.LearningException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A·B의 오류 처리기는 C 컨트롤러에 적용되지 않으므로 C 응답을 여기서 {code, message} 형식으로 맞춘다. */
@RestControllerAdvice(basePackages = "com.teachmi.backend.assessment")
class AssessmentErrorHandler {
    record ErrorBody(String code, String message) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorBody(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(LearningException.class)
    ResponseEntity<ErrorBody> learning(LearningException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorBody(ex.code(), ex.getMessage()));
    }
}