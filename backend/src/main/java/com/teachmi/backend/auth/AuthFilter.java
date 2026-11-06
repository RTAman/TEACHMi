package com.teachmi.backend.auth;

import java.io.IOException;
import java.security.Principal;
import java.util.Set;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /api 요청의 인증 쿠키를 확인해 현재 회원을 요청에 붙인다.
 * 로그인한 상태에서 상태를 바꾸는 요청(POST·PUT·PATCH·DELETE)은 X-CSRF-Token 헤더가 맞아야 통과한다.
 * 쿠키가 없거나 만료·폐기된 경우 회원 없이 통과시키고, 각 API가 401을 반환한다.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {
    public static final String CSRF_HEADER = "X-CSRF-Token";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> CSRF_EXEMPT = Set.of("/api/v1/auth/signup", "/api/v1/auth/login");

    private final AuthService auth;
    private final AuthProperties properties;

    AuthFilter(AuthService auth, AuthProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var member = auth.authenticate(readCookie(request, properties.cookieName()));
        if (member.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        var current = member.get();
        if (!SAFE_METHODS.contains(request.getMethod()) && !CSRF_EXEMPT.contains(path(request))
                && !AuthTokens.constantTimeEquals(current.csrfToken(), request.getHeader(CSRF_HEADER))) {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":\"CSRF_TOKEN_INVALID\",\"message\":\"보안 토큰이 없거나 맞지 않습니다. 다시 로그인하세요.\"}");
            return;
        }
        request.setAttribute(AuthenticatedMember.ATTRIBUTE, current);
        chain.doFilter(new MemberRequest(request, current), response);
    }

    static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /** Servlet의 Principal에도 회원 ID를 노출해 다른 파트의 기본 회원 확인 경로와 맞춘다. */
    private static final class MemberRequest extends HttpServletRequestWrapper {
        private final AuthenticatedMember member;

        MemberRequest(HttpServletRequest request, AuthenticatedMember member) {
            super(request);
            this.member = member;
        }

        @Override
        public Principal getUserPrincipal() {
            String id = member.userId().toString();
            return () -> id;
        }

        @Override
        public String getRemoteUser() {
            return member.userId().toString();
        }
    }
}
