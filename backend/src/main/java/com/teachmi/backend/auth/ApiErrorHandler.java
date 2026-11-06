package com.teachmi.backend.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A가 소유한 컨트롤러(auth, material)의 오류만 처리한다. B·C의 오류 형식은 바꾸지 않는다. */
@RestControllerAdvice(basePackages = {"com.teachmi.backend.auth", "com.teachmi.backend.material"})
class ApiErrorHandler {
    record ErrorBody(String code, String message) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorBody(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException ex) {
        return ResponseEntity.unprocessableContent()
                .body(new ErrorBody("INVALID_REQUEST", "입력 항목을 확인하세요."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody("MALFORMED_REQUEST", "요청 형식이 올바르지 않습니다."));
    }
}
