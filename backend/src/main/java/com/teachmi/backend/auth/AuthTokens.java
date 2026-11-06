package com.teachmi.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 인증 토큰 발급과 해시. DB에는 원래 토큰이 아니라 SHA-256 해시만 저장한다. */
final class AuthTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private AuthTokens() {}

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL.encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    /** 쿠키 토큰에서 파생한 CSRF 토큰. 쿠키 값을 모르는 다른 사이트는 계산할 수 없다. */
    static String csrfFor(String rawToken) {
        return URL.encodeToString(sha256("csrf:" + rawToken));
    }

    static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
