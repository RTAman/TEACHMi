package com.teachmi.backend.auth;

import java.util.Optional;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 인증 필터가 유효한 로그인 세션을 확인한 뒤 요청에 붙이는 현재 회원.
 * 다른 패키지는 {@link #require(HttpServletRequest)}로 회원 ID를 얻는다.
 */
public record AuthenticatedMember(UUID userId, String email, UUID authSessionId, String csrfToken) {
    static final String ATTRIBUTE = AuthenticatedMember.class.getName();

    public static Optional<AuthenticatedMember> from(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof AuthenticatedMember member
                ? Optional.of(member) : Optional.empty();
    }

    public static AuthenticatedMember require(HttpServletRequest request) {
        return from(request).orElseThrow(ApiException::unauthenticated);
    }
}
