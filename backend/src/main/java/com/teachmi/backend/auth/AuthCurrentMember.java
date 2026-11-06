package com.teachmi.backend.auth;

import com.teachmi.backend.learning.CurrentMember;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** B·C의 학습 API가 쓰는 회원 확인. 인증 필터가 만료·폐기 여부까지 확인한 회원 ID만 돌려준다. */
@Component
class AuthCurrentMember implements CurrentMember {
    @Override
    public String requireId(HttpServletRequest request) {
        return AuthenticatedMember.from(request).map(member -> member.userId().toString()).orElse(null);
    }
}
