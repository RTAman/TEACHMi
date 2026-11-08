-- A: 업로드한 강의자료 원본. 재분석에 다시 쓰며 API로 내려보내지 않는다.
CREATE TABLE teachmi.material_file (
    material_id UUID PRIMARY KEY REFERENCES teachmi.material(id) ON DELETE CASCADE,
    format VARCHAR(8) NOT NULL CHECK (format IN ('pdf', 'ppt', 'pptx')),
    content BYTEA NOT NULL,
    sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 서버가 재시작되면 분석 중이던 자료를 다시 처리한다.
CREATE INDEX material_analyzing ON teachmi.material(updated_at) WHERE status = 'analyzing';
