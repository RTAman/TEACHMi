package com.teachmi.backend.auth;

import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final AuthService auth;
    private final AuthProperties properties;

    AuthController(AuthService auth, AuthProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    record Credentials(@NotNull @Size(max = 254) String email, @NotNull @Size(max = 128) String password) {}

    record MemberView(String id, String email) {}

    record LoginView(String id, String email, String csrfToken, String expiresAt) {}

    record MeView(String id, String email, String csrfToken) {}

    @PostMapping("/signup")
    ResponseEntity<MemberView> signup(@Valid @RequestBody Credentials request) {
        var member = auth.signup(request.email(), request.password());
        return ResponseEntity.created(URI.create("/api/v1/auth/me"))
                .body(new MemberView(member.id().toString(), member.email()));
    }

    @PostMapping("/login")
    ResponseEntity<LoginView> login(@Valid @RequestBody Credentials request) {
        var login = auth.login(request.email(), request.password());
        var cookie = cookie(login.token(), properties.sessionTtl().toSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .cacheControl(CacheControl.noStore())
                .body(new LoginView(login.member().id().toString(), login.member().email(), login.csrfToken(),
                        login.expiresAt().toInstant().toString()));
    }

    @GetMapping("/me")
    ResponseEntity<MeView> me(HttpServletRequest request) {
        var member = AuthenticatedMember.require(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new MeView(member.userId().toString(), member.email(), member.csrfToken()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(AuthFilter.readCookie(request, properties.cookieName()));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", 0).toString())
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private ResponseCookie cookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite())
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
