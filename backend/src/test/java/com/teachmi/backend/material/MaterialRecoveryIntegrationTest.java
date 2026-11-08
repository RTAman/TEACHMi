package com.teachmi.backend.material;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.teachmi.backend.auth.*;
import com.teachmi.backend.learning.LearningSourceReader;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.hikari.maximum-pool-size=2","spring.datasource.hikari.minimum-idle=0"})
class MaterialRecoveryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MaterialService materials;
    @Autowired MaterialAnalysis analysis;
    @Autowired LearningSourceReader sources;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.zaxxer.hikari.HikariDataSource pool;
    @MockitoBean(name="materialAnalysisExecutor") TaskExecutor executor;
    final Queue<Runnable> jobs=new ArrayDeque<>();
    UUID user;
    MockMvc mvc;
    final byte[] file="%PDF-local-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final OpenApiInteractionValidator contract=OpenApiInteractionValidator.createForSpecificationUrl(
            Path.of("api/auth-materials.openapi.json").toUri().toString()).build();

    @BeforeEach void setup() {
        // Flyway needs two connections during initialization; the recovery operations below get only one.
        pool.setMaximumPoolSize(1);
        pool.getHikariPoolMXBean().softEvictConnections();
        jdbc.execute("TRUNCATE teachmi.app_user CASCADE");
        user=UUID.randomUUID();
        jdbc.update("INSERT INTO teachmi.app_user(id,email,password_hash,created_at) VALUES (?,?,'test-hash',now())",user,user+"@example.test");
        jobs.clear();
        acceptJobs();
        // Authentication/CSRF have separate filter integration tests; this exercises authenticated controller responses.
        mvc=MockMvcBuilders.webAppContextSetup(context).build();
    }
    @AfterEach void cleanup() {
        jobs.clear();
        jdbc.execute("TRUNCATE teachmi.app_user CASCADE");
    }
    void acceptJobs() {
        doAnswer(call -> { jobs.add(call.getArgument(0,Runnable.class));return null; }).when(executor).execute(any(Runnable.class));
    }
    void rejectJobs() {
        doThrow(new TaskRejectedException("test queue full")).when(executor).execute(any(Runnable.class));
    }
    MaterialViews.MaterialView upload(String key) {
        return materials.createFile(user,key,"review.pdf",file,null).value();
    }
    <B extends org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<B>> B member(B request) {
        return request.requestAttr(AuthenticatedMember.class.getName(),new AuthenticatedMember(user,"test@example.test",UUID.randomUUID(),"csrf"));
    }
    void response(MvcResult result,int status,String code) throws Exception {
        var reply=result.getResponse();
        assertThat(reply.getStatus()).isEqualTo(status);
        assertThat(json.readTree(reply.getContentAsByteArray()).path("code").asText()).isEqualTo(code);
        var report=contract.validateResponse(result.getRequest().getRequestURI(),Request.Method.POST,
                SimpleResponse.Builder.status(status).withContentType("application/json").withBody(reply.getContentAsByteArray()).build());
        assertThat(report.hasErrors()).as(report.getMessages().toString()).isFalse();
    }

    @Test void saturatedUploadReturns503WithoutCommittingAnOrphanAndTheSameKeyCanRecover() throws Exception {
        rejectJobs();
        response(mvc.perform(member(multipart("/api/v1/materials").file(
                new MockMultipartFile("file","review.pdf","application/pdf",file))).header("Idempotency-Key","busy")).andReturn(),
                503,"MATERIAL_ANALYSIS_BUSY");
        for(var table:List.of("material","material_file","material_request"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi."+table,Long.class)).isZero();
        acceptJobs();
        assertThat(upload("busy").status()).isEqualTo("analyzing");
        assertThat(jobs).hasSize(1);
    }

    @Test void rejectedRetryPreservesTheFailureVersionAndOriginalAndDoesNotConsumeItsKey() throws Exception {
        UUID id=UUID.fromString(upload("original").materialId());
        jobs.remove().run(); // Original malformed test file becomes a normal format failure.
        assertThat(materials.get(user,id).errorCode()).isEqualTo("format");
        rejectJobs();
        response(mvc.perform(member(post("/api/v1/materials/"+id+"/retry")).header("Idempotency-Key","retry")).andReturn(),
                503,"MATERIAL_ANALYSIS_BUSY");
        var failed=materials.get(user,id);
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.errorCode()).isEqualTo("format");
        assertThat(failed.analysisVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.material_request WHERE request_key='retry'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT content FROM teachmi.material_file WHERE material_id=?",byte[].class,id)).isEqualTo(file);
        acceptJobs();
        assertThat(materials.retry(user,id,"retry").value().analysisVersion()).isEqualTo(2);
        assertThat(jobs).hasSize(1);
    }

    @Test void startupQueueRejectionIsRetryableAndDoesNotAbortTheRemainingScan() {
        UUID first=UUID.fromString(upload("one").materialId());
        UUID second=UUID.fromString(upload("two").materialId());
        jobs.clear(); // Simulate the in-memory queue being lost on process restart.
        rejectJobs();
        assertThatCode(analysis::resumePending).doesNotThrowAnyException();
        for(var id:List.of(first,second)) {
            assertThat(materials.get(user,id).status()).isEqualTo("failed");
            assertThat(materials.get(user,id).errorCode()).isEqualTo("analysis");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.material_file",Long.class)).isEqualTo(2);
        acceptJobs();
        assertThat(materials.retry(user,first,"restart-retry").value().analysisVersion()).isEqualTo(2);
        assertThat(jobs).hasSize(1);
    }

    @Test void aRejectedOldRecoveryJobCannotFailANewerAnalysisVersion() {
        UUID id=UUID.fromString(upload("old").materialId());
        jobs.clear();
        doAnswer(call -> {
            jdbc.update("UPDATE teachmi.material SET analysis_version=2 WHERE id=?",id);
            throw new TaskRejectedException("old startup job rejected");
        }).when(executor).execute(any(Runnable.class));
        analysis.resumePending();
        assertThat(materials.get(user,id).analysisVersion()).isEqualTo(2);
        assertThat(materials.get(user,id).status()).isEqualTo("analyzing");
    }

    @Test void queuedWorkFromARolledBackTransactionDoesNotRunAnalysis() {
        var transaction=new TransactionTemplate(transactionManager);
        var id=new AtomicReference<UUID>();
        assertThatThrownBy(() -> transaction.execute(status -> {
            id.set(UUID.fromString(upload("rollback").materialId()));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jobs).hasSize(1);
        assertThatCode(jobs.remove()::run).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.material WHERE id=?",Long.class,id.get())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.source_chunk",Long.class)).isZero();
    }

    record Pages(UUID material,List<String> chunks) {}
    Pages pages(String text) {
        UUID id=UUID.fromString(upload("pages").materialId());
        jobs.clear();
        var chunks=new ArrayList<String>();
        for(int i=1;i<=3;i++) {
            var chunk=UUID.randomUUID();chunks.add(chunk.toString());
            jdbc.update("INSERT INTO teachmi.source_chunk(id,material_id,analysis_version,seq,location_kind,location_number,text,created_at) VALUES (?,?,1,?,'page',?,?,now())",
                    chunk,id,i,i,text);
        }
        jdbc.update("UPDATE teachmi.material SET status='done' WHERE id=?",id);
        return new Pages(id,chunks);
    }
    @Test void explicitPagesOverTheLimitReturn422WithoutSavingAConceptOrKey() throws Exception {
        var pages=pages("개".repeat(6000));
        response(mvc.perform(member(post("/api/v1/materials/"+pages.material()+"/concepts")).header("Idempotency-Key","too-large")
                .contentType("application/json").content(json.writeValueAsString(Map.of(
                        "name","개념","scope","학습 범위","sourceChunkIds",pages.chunks())))).andReturn(),422,"EVIDENCE_TOO_LARGE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.concept",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.material_request WHERE request_key='too-large'",Long.class)).isZero();
    }

    @Test void exactlyFifteenThousandCharactersAreAcceptedAndReplayDoesNotDuplicateTheConcept() {
        var pages=pages("개".repeat(5000));
        var request=new MaterialViews.AddConceptRequest("개념","학습 범위",null,pages.chunks());
        var concept=materials.addConcept(user,pages.material(),"boundary",request).value();
        var raw=sources.readOwned(user.toString(),pages.material().toString(),concept.conceptId(),concept.sourceChunkIds());
        assertThat(raw.chunks().stream().mapToInt(c -> c.text().length()).sum()).isEqualTo(15000);
        assertThat(materials.addConcept(user,pages.material(),"boundary",request).replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.concept",Long.class)).isEqualTo(1);
    }

    @Test void wholeFileFallbackCountsSupplementaryCharactersTheSameWayAsB() {
        var pages=pages("😀".repeat(3000)); // 9,000 PostgreSQL characters, 18,000 Java UTF-16 units.
        assertThatThrownBy(() -> materials.addConcept(user,pages.material(),"unicode",
                new MaterialViews.AddConceptRequest("개념","학습 범위",null,null)))
                .isInstanceOfSatisfying(ApiException.class,e -> {
                    assertThat(e.status()).isEqualTo(422);assertThat(e.code()).isEqualTo("EVIDENCE_TOO_LARGE");
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teachmi.concept",Long.class)).isZero();
    }
}
