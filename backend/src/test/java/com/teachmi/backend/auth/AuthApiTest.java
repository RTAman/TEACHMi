package com.teachmi.backend.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
class AuthApiTest {
    @Autowired WebApplicationContext context;
    @Autowired AuthFilter filter;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        jdbc.execute("TRUNCATE teachmi.app_user CASCADE");
    }

    @Test
    void signupNormalizesEmailAndRejectsDuplicatesAndInvalidInput() throws Exception {
        var created = signup(" Student@Example.COM ", "correct-horse");
        assertThat(created.getStatus()).isEqualTo(201);
        assertThat(body(created).path("email").asText()).isEqualTo("student@example.com");
        assertThat(body(created).has("id")).isTrue();
        assertThat(created.getCookie("TEACHMI_SESSION")).as("가입은 로그인시키지 않는다").isNull();

        var duplicate = signup("student@example.com", "another-password");
        assertThat(duplicate.getStatus()).isEqualTo(409);
        assertThat(body(duplicate).path("code").asText()).isEqualTo("EMAIL_ALREADY_REGISTERED");

        assertThat(signup("not-an-email", "correct-horse").getStatus()).isEqualTo(422);
        assertThat(signup("short@example.com", "1234567").getStatus()).isEqualTo(422);
        var missing = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@example.com\"}")).andReturn().getResponse();
        assertThat(missing.getStatus()).isEqualTo(422);
        var malformed = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{")).andReturn().getResponse();
        assertThat(malformed.getStatus()).isEqualTo(400);

        String stored = jdbc.queryForObject("SELECT password_hash FROM teachmi.app_user WHERE email = ?",
                String.class, "student@example.com");
        assertThat(stored).doesNotContain("correct-horse").startsWith("$2");
    }

    @Test
    void concurrentSignupsWithTheSameEmailCreateOneMember() throws Exception {
        int threads = 6;
        var barrier = new CyclicBarrier(threads);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Integer> task = () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        auth.signup("race@example.com", "correct-horse");
                        return 201;
                    } catch (ApiException e) {
                        return e.status();
                    }
                };
                results.add(pool.submit(task));
            }
            var statuses = new ArrayList<Integer>();
            for (var result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnlyOnce(201);
            assertThat(statuses.stream().filter(status -> status == 409)).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teachmi.app_user WHERE email = 'race@example.com'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void loginIssuesAnHttpOnlyCookieAndMeReturnsTheMember() throws Exception {
        signup("me@example.com", "correct-horse");
        var login = login("ME@example.com", "correct-horse");
        assertThat(login.getStatus()).isEqualTo(200);
        var cookie = login.getCookie("TEACHMI_SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(login.getHeader("Set-Cookie")).contains("SameSite=Lax").contains("Path=/");
        assertThat(login.getHeader("Cache-Control")).contains("no-store");
        var loginBody = body(login);
        assertThat(loginBody.path("email").asText()).isEqualTo("me@example.com");
        assertThat(loginBody.path("csrfToken").asText()).isNotBlank();

        String tokenHash = jdbc.queryForObject("SELECT token_hash FROM teachmi.auth_session", String.class);
        assertThat(tokenHash).isNotEqualTo(cookie.getValue()).hasSize(64);

        var me = mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andReturn().getResponse();
        assertThat(me.getStatus()).isEqualTo(200);
        assertThat(body(me).path("id").asText()).isEqualTo(loginBody.path("id").asText());
        assertThat(body(me).path("csrfToken").asText()).isEqualTo(loginBody.path("csrfToken").asText());

        var anonymous = mvc.perform(get("/api/v1/auth/me")).andReturn().getResponse();
        assertThat(anonymous.getStatus()).isEqualTo(401);
        assertThat(body(anonymous).path("code").asText()).isEqualTo("UNAUTHENTICATED");
        var forged = mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("TEACHMI_SESSION", "forged")))
                .andReturn().getResponse();
        assertThat(forged.getStatus()).isEqualTo(401);
    }

    @Test
    void unknownEmailAndWrongPasswordGiveTheSameFailureAndRepeatedFailuresAreThrottled() throws Exception {
        signup("lock@example.com", "correct-horse");
        var wrongPassword = login("lock@example.com", "wrong-password");
        var unknownEmail = login("nobody@example.com", "wrong-password");
        assertThat(wrongPassword.getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getContentAsString()).isEqualTo(unknownEmail.getContentAsString());

        for (int i = 0; i < 4; i++) {
            assertThat(login("lock@example.com", "wrong-password").getStatus()).isEqualTo(401);
        }
        var blocked = login("lock@example.com", "correct-horse");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(body(blocked).path("code").asText()).isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
    }

    @Test
    void logoutRequiresTheCsrfTokenAndRevokesTheCookie() throws Exception {
        signup("bye@example.com", "correct-horse");
        var login = login("bye@example.com", "correct-horse");
        var cookie = login.getCookie("TEACHMI_SESSION");
        String csrf = body(login).path("csrfToken").asText();

        var noToken = mvc.perform(post("/api/v1/auth/logout").cookie(cookie)).andReturn().getResponse();
        assertThat(noToken.getStatus()).isEqualTo(403);
        assertThat(body(noToken).path("code").asText()).isEqualTo("CSRF_TOKEN_INVALID");
        assertThat(mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andReturn().getResponse().getStatus())
                .as("CSRF 실패는 로그아웃시키지 않는다").isEqualTo(200);

        var logout = mvc.perform(post("/api/v1/auth/logout").cookie(cookie).header(AuthFilter.CSRF_HEADER, csrf))
                .andReturn().getResponse();
        assertThat(logout.getStatus()).isEqualTo(204);
        assertThat(logout.getCookie("TEACHMI_SESSION").getMaxAge()).isZero();

        var reused = mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andReturn().getResponse();
        assertThat(reused.getStatus()).as("폐기한 쿠키는 다시 쓸 수 없다").isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/auth/logout")).andReturn().getResponse().getStatus()).isEqualTo(204);

        var again = login("bye@example.com", "correct-horse");
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/v1/auth/me").cookie(again.getCookie("TEACHMI_SESSION")))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void expiredSessionsAreRejected() throws Exception {
        signup("old@example.com", "correct-horse");
        var cookie = login("old@example.com", "correct-horse").getCookie("TEACHMI_SESSION");
        jdbc.update("UPDATE teachmi.auth_session SET expires_at = now() - interval '1 minute'");
        assertThat(mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    MockHttpServletResponse signup(String email, String password) throws Exception {
        return credentials("/api/v1/auth/signup", email, password);
    }

    MockHttpServletResponse login(String email, String password) throws Exception {
        return credentials("/api/v1/auth/login", email, password);
    }

    private MockHttpServletResponse credentials(String path, String email, String password) throws Exception {
        String content = json.writeValueAsString(java.util.Map.of("email", email, "password", password));
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse();
    }

    JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }
}
