package com.teachmi.backend.learning;

public class LearningException extends RuntimeException {
    final int status;
    final String code;
    final SessionView currentSession;

    public LearningException(int status, String code, String message) {
        this(status, code, message, null);
    }

    LearningException(int status, String code, String message, SessionView currentSession) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentSession = currentSession;
    }

    public static LearningException notFound() {
        return new LearningException(404, "NOT_FOUND", "학습 자료 또는 세션을 찾을 수 없습니다.");
    }

    public String code() {
        return code;
    }
}
