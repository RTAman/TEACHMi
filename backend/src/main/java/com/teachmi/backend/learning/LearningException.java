package com.teachmi.backend.learning;

public class LearningException extends RuntimeException {
    final int status;
    final String code;
    final SessionView currentSession;
    final GenerationRecovery recovery;

    public LearningException(int status, String code, String message) {
        this(status, code, message, null);
    }

    LearningException(int status, String code, String message, SessionView currentSession) {
        this(status,code,message,currentSession,null);
    }

    public LearningException(int status,String code,String message,SessionView currentSession,GenerationRecovery recovery) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentSession = currentSession;
        this.recovery = recovery;
    }

    LearningException withSession(SessionView session) { return new LearningException(status,code,getMessage(),session,recovery); }

    static LearningException retryBlocked(String code,SessionView session) {
        boolean uncertain="AI_GENERATION_UNCERTAIN".equals(code);
        return new LearningException(uncertain?503:429,code,
                uncertain?"답변은 보존돼 있습니다. 호출 결과를 확인하기 전에는 다시 요청하지 마세요.":"생성 시도 한도에 도달했습니다.",
                session,new GenerationRecovery("RECOVERY_GUARD",uncertain?"AI_RESULT_UNCONFIRMED":"AI_ATTEMPT_LIMIT",false,
                uncertain?"review_required":"limit_reached"));
    }

    public GenerationRecovery recovery() { return recovery; }

    public static LearningException notFound() {
        return new LearningException(404, "NOT_FOUND", "학습 자료 또는 세션을 찾을 수 없습니다.");
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
