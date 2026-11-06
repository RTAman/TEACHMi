-- A: 회원·인증 세션·강의자료·원문 조각·개념
-- 학습 테이블(V2~)의 member_id·material_id·concept_id는 문자열이며, 이 테이블의 UUID를 문자열로 저장한다.

CREATE TABLE teachmi.app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 이메일은 서버에서 소문자로 정규화해 저장한다. 동시 가입도 이 제약으로 한 명만 생성된다.
CREATE UNIQUE INDEX app_user_email_unique ON teachmi.app_user(email);

CREATE TABLE teachmi.auth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES teachmi.app_user(id),
    token_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX auth_session_token_unique ON teachmi.auth_session(token_hash);
CREATE INDEX auth_session_user ON teachmi.auth_session(user_id);

CREATE TABLE teachmi.material (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES teachmi.app_user(id),
    source_type VARCHAR(8) NOT NULL CHECK (source_type IN ('file', 'text')),
    title VARCHAR(200),
    file_name VARCHAR(255),
    content_type VARCHAR(100),
    file_size BIGINT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('analyzing', 'done', 'failed')),
    error_code VARCHAR(16) CHECK (error_code IN ('format', 'size', 'no_text', 'analysis')),
    analysis_version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX material_user_created ON teachmi.material(user_id, created_at DESC);

-- 페이지·슬라이드 또는 직접 입력 단위의 원문. 재분석은 analysis_version을 올려 새 조각을 만든다.
CREATE TABLE teachmi.source_chunk (
    id UUID PRIMARY KEY,
    material_id UUID NOT NULL REFERENCES teachmi.material(id) ON DELETE CASCADE,
    analysis_version INT NOT NULL,
    seq INT NOT NULL,
    location_kind VARCHAR(8) NOT NULL CHECK (location_kind IN ('page', 'slide', 'input')),
    location_number INT NOT NULL,
    text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX source_chunk_material ON teachmi.source_chunk(material_id, analysis_version, seq);

CREATE TABLE teachmi.concept (
    id UUID PRIMARY KEY,
    material_id UUID NOT NULL REFERENCES teachmi.material(id) ON DELETE CASCADE,
    analysis_version INT NOT NULL,
    name VARCHAR(60) NOT NULL,
    scope VARCHAR(500) NOT NULL,
    custom BOOLEAN NOT NULL,
    seq INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX concept_material ON teachmi.concept(material_id, analysis_version, seq);

CREATE TABLE teachmi.concept_source (
    concept_id UUID NOT NULL REFERENCES teachmi.concept(id) ON DELETE CASCADE,
    chunk_id UUID NOT NULL REFERENCES teachmi.source_chunk(id) ON DELETE CASCADE,
    PRIMARY KEY (concept_id, chunk_id)
);

-- 자료 생성·개념 추가의 Idempotency-Key 기록. 같은 키·같은 요청이면 처음 만든 결과를 돌려준다.
CREATE TABLE teachmi.material_request (
    user_id UUID NOT NULL REFERENCES teachmi.app_user(id),
    request_key VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, request_key)
);
