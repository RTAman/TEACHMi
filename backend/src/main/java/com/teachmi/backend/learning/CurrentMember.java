package com.teachmi.backend.learning;

import jakarta.servlet.http.HttpServletRequest;

/** A supplies a bean that resolves the member from a verified, unexpired authentication session. */
public interface CurrentMember {
    String requireId(HttpServletRequest request);
}
