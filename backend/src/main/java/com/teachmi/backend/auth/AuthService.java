package com.teachmi.backend.auth;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가입·로그인·로그인 세션 확인·로그아웃. 로그인 세션이 끝나도 학습 데이터는 회원 계정에 남는다. */
@Service
public class AuthService {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD = 64;
    private static final int BCRYPT_MAX_BYTES = 72;
    /** 게스트 계정 이메일의 도메인. .invalid는 실제로 존재할 수 없는 예약 도메인이다. */
    static final String GUEST_DOMAIN = "guest.teachmi.invalid";

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;
    private final AuthProperties properties;
    private final LoginThrottle throttle;
    private final GuestThrottle guestThrottle;
    private final String dummyHash;

    AuthService(JdbcTemplate jdbc, BCryptPasswordEncoder encoder, AuthProperties properties, LoginThrottle throttle,
                GuestThrottle guestThrottle) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.properties = properties;
        this.throttle = throttle;
        this.guestThrottle = guestThrottle;
        // 없는 이메일로 로그인해도 비밀번호 검사 시간을 비슷하게 맞춘다.
        this.dummyHash = encoder.encode("teachmi-timing-equalizer");
    }

    public record Member(UUID id, String email) {}

    record Login(Member member, String token, String csrfToken, OffsetDateTime expiresAt) {}

    @Transactional
    public Member signup(String rawEmail, String password) {
        String email = normalize(rawEmail);
        if (email.length() > 254 || !EMAIL.matcher(email).matches() || isGuest(email)) {
            throw ApiException.invalid("INVALID_EMAIL", "이메일 형식을 확인하세요.");
        }
        validatePassword(password);
        var member = new Member(UUID.randomUUID(), email);
        try {
            jdbc.update("INSERT INTO teachmi.app_user(id, email, password_hash, created_at) VALUES (?, ?, ?, ?)",
                    member.id(), email, encoder.encode(password), now());
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "EMAIL_ALREADY_REGISTERED", "이미 가입한 이메일입니다.");
        }
        return member;
    }

    @Transactional
    Login login(String rawEmail, String password) {
        String email = normalize(rawEmail);
        if (throttle.isBlocked(email)) {
            throw new ApiException(429, "TOO_MANY_LOGIN_ATTEMPTS", "로그인 시도가 많습니다. 잠시 후 다시 시도하세요.");
        }
        var found = jdbc.query("SELECT id, email, password_hash FROM teachmi.app_user WHERE email = ?",
                (rs, i) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash")},
                email);
        boolean matches;
        if (found.isEmpty()) {
            encoder.matches(password == null ? "" : password, dummyHash);
            matches = false;
        } else {
            matches = password != null && encoder.matches(password, (String) found.getFirst()[2]);
        }
        if (!matches) {
            throttle.recordFailure(email);
            throw new ApiException(401, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        throttle.reset(email);
        var row = found.getFirst();
        return startSession(new Member((UUID) row[0], (String) row[1]));
    }

    /**
     * 가입 없이 체험할 수 있는 임시 계정을 만들고 바로 로그인시킨다.
     * 비밀번호는 아무도 모르는 임의 값이라 이메일·비밀번호로는 로그인할 수 없고, 쿠키가 사라지면 다시 들어갈 수 없다.
     */
    @Transactional
    Login guest() {
        if (!properties.guestEnabled()) {
            throw new ApiException(404, "GUEST_LOGIN_DISABLED", "게스트 체험을 사용할 수 없습니다. 회원가입 후 이용하세요.");
        }
        if (!guestThrottle.tryAcquire()) {
            throw new ApiException(429, "TOO_MANY_GUESTS", "게스트 접속이 많습니다. 잠시 후 다시 시도하거나 회원가입 후 이용하세요.");
        }
        var id = UUID.randomUUID();
        var member = new Member(id, "guest-" + id.toString().replace("-", "") + "@" + GUEST_DOMAIN);
        jdbc.update("INSERT INTO teachmi.app_user(id, email, password_hash, created_at) VALUES (?, ?, ?, ?)",
                member.id(), member.email(), encoder.encode(AuthTokens.newToken()), now());
        return startSession(member);
    }

    /** 게스트 계정 이메일인지. 게스트 도메인은 일반 가입에 쓸 수 없다. */
    public static boolean isGuest(String email) {
        return email != null && email.endsWith("@" + GUEST_DOMAIN);
    }

    private Login startSession(Member member) {
        String token = AuthTokens.newToken();
        var createdAt = now();
        var expiresAt = createdAt.plus(properties.sessionTtl());
        jdbc.update("""
                INSERT INTO teachmi.auth_session(id, user_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), member.id(), AuthTokens.sha256Hex(token), createdAt, expiresAt);
        return new Login(member, token, AuthTokens.csrfFor(token), expiresAt);
    }

    /** 만료·폐기되지 않은 로그인 세션이면 현재 회원을 돌려준다. */
    @Transactional(readOnly = true)
    public Optional<AuthenticatedMember> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 128) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT s.id AS session_id, u.id AS user_id, u.email
                FROM teachmi.auth_session s JOIN teachmi.app_user u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ?
                """, (rs, i) -> new AuthenticatedMember(rs.getObject("user_id", UUID.class), rs.getString("email"),
                        rs.getObject("session_id", UUID.class), AuthTokens.csrfFor(rawToken)),
                AuthTokens.sha256Hex(rawToken), now()).stream().findFirst();
    }

    /** 현재 로그인 세션을 폐기한다. 이미 폐기됐거나 없는 토큰이어도 오류 없이 끝난다. */
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 128) {
            return;
        }
        jdbc.update("UPDATE teachmi.auth_session SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
                now(), AuthTokens.sha256Hex(rawToken));
    }

    static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private static void validatePassword(String password) {
        if (password == null || password.isBlank() || password.length() < MIN_PASSWORD
                || password.length() > MAX_PASSWORD
                || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw ApiException.invalid("INVALID_PASSWORD", "비밀번호는 8~64자로 입력하세요.");
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
