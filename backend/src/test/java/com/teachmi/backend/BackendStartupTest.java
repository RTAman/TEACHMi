package com.teachmi.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendStartupTest {
    @Autowired
    JdbcTemplate jdbc;

    @LocalServerPort
    int port;

    @Test
    void flywayCreatesTheApplicationSchemaInPostgres() {
        assertThat(jdbc.queryForObject("SELECT to_regnamespace('teachmi') IS NOT NULL", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT success FROM public.flyway_schema_history WHERE version = '1'", Boolean.class))
                .isTrue();
    }

    @Test
    void runningServerReportsHealthy() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
