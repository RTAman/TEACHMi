package com.teachmi.backend.home;

import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.LearningException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A·B·C 평가의 오류 처리기는 home 패키지에 적용되지 않으므로 같은 {code, message} 형식을 여기서 맞춘다. */
@RestControllerAdvice(basePackages = "com.teachmi.backend.home")
class HomeErrorHandler {
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