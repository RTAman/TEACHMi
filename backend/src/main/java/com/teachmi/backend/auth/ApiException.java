package com.teachmi.backend.auth;

/** A의 인증·자료 API가 사용하는 오류. 본문은 {@code {code, message}} 형태로 응답한다. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException unauthenticated() {
        return new ApiException(401, "UNAUTHENTICATED", "로그인이 필요합니다.");
    }

    public static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "자료를 찾을 수 없습니다.");
    }

    public static ApiException invalid(String code, String message) {
        return new ApiException(422, code, message);
    }
}
