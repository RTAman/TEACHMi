package com.teachmi.backend.assessment;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.teachmi.backend.learning.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class AssessmentCommandTest {
    @Autowired LearningCommands commands;
    @Autowired LearningSessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Test void failedCallbacksRollBackTheirWritesAndReceiptAndCanRetry() {
        var member="cmd-"+UUID.randomUUID();var id=AssessmentFixtures.ready(jdbc,member);
        assertThatThrownBy(() -> commands.execute(member,id,"retry","c/test","",String.class,locked -> {
            jdbc.update("UPDATE teachmi.learning_session SET status='abandoned' WHERE id=?",id);
            throw new IllegalStateException("callback failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(sessions.get(member,id).status()).isEqualTo("active");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.learning_session_request WHERE member_id=?",Long.class,member)).isZero();
        assertThat(commands.execute(member,id,"retry","c/test","",String.class,locked -> "saved")).isEqualTo("saved");
    }
    @Test void repliesAreStoredOnceAndScopeIncludesOperationAndBody() {
        var member="cmd-"+UUID.randomUUID();var id=AssessmentFixtures.ready(jdbc,member);var calls=new AtomicInteger();
        assertThat(commands.execute(member,id,"key","c/test","body",String.class,locked -> "result-"+calls.incrementAndGet())).isEqualTo("result-1");
        assertThat(commands.execute(member,id,"key","c/test","body",String.class,locked -> "result-"+calls.incrementAndGet())).isEqualTo("result-1");
        assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(() -> commands.execute(member,id,"key","c/other","body",String.class,locked -> "bad"))
                .isInstanceOfSatisfying(LearningException.class,e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThatThrownBy(() -> commands.execute(member,id,"key","c/test","other-body",String.class,locked -> "bad"))
                .isInstanceOf(LearningException.class);
    }
    @Test void normalBCommandsCannotReuseAStoredCCommandKey() {
        var member="cmd-"+UUID.randomUUID();var id=AssessmentFixtures.ready(jdbc,member);
        commands.execute(member,id,"key","c/test","",String.class,locked -> "ok");
        assertThatThrownBy(() -> sessions.chooseMode(member,id,"key",new ChooseModeRequest("first",7L)))
                .isInstanceOfSatisfying(LearningException.class,e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }
}
