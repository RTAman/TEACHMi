package com.teachmi.backend.assessment;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class AssessmentFixtures {
    static UUID ready(JdbcTemplate jdbc, String member) {
        var id = UUID.randomUUID();
        var last = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO teachmi.learning_session
                (id,member_id,material_id,concept_id,concept_snapshot,mode,stage,status,version,
                 final_explanation_id,created_at,updated_at)
                VALUES (?,?,'m','c','{"name":"삼각함수","scope":"사인","sourceChunkIds":["trig-source"]}',
                'review','problem','active',7,?,now(),now())
                """, id, member, last);
        jdbc.update("""
                INSERT INTO teachmi.learning_message
                (id,session_id,sequence,role,kind,chat_stage,text,source_chunk_ids,created_at)
                VALUES (?,?,1,'user','answer','wrapup','사인은 길이의 비율입니다.','[]',now())
                """, last,id);
        return id;
    }
}
