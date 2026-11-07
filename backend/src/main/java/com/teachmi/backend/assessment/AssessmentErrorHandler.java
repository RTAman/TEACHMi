package com.teachmi.backend.assessment;

import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.LearningException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import jakarta.validation.ConstraintViolationException;

/** A·B의 오류 처리기는 C 컨트롤러에 적용되지 않으므로 C 응답을 여기서 {code, message} 형식으로 맞춘다. */
@RestControllerAdvice(basePackages = "com.teachmi.backend.assessment")
class AssessmentErrorHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException ex) {
        return ResponseEntity.unprocessableContent().body(new ErrorBody("INVALID_REQUEST", "답과 풀이의 입력을 확인하세요."));
    }

    /** B 서비스(@Validated)가 요청 값을 거절한 경우도 같은 422로 맞춘다. */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorBody> constraint(ConstraintViolationException ex) {
        return ResponseEntity.unprocessableContent().body(new ErrorBody("INVALID_REQUEST", "요청 값을 확인하세요."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new ErrorBody("MALFORMED_JSON", "JSON 요청 형식을 확인하세요."));
    }
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