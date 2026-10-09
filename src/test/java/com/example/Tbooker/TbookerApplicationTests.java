package com.example.Tbooker;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TbookerApplicationTests extends SecurityTestSupport {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm");

	private final Flyway flyway;
	private final JdbcTemplate jdbcTemplate;
	private final MockMvc mockMvc;

	@Autowired
	TbookerApplicationTests(Flyway flyway, JdbcTemplate jdbcTemplate, MockMvc mockMvc) {
		this.flyway = flyway;
		this.jdbcTemplate = jdbcTemplate;
		this.mockMvc = mockMvc;
	}

	@Test
	void contextLoads() {
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
		assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'tbooker'",
				Integer.class)).isEqualTo(1);
	}

	@Test
	void defaultProfileDoesNotLoadDevelopmentData() throws Exception {
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tbooker.users", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tbooker.movies", Integer.class)).isZero();
		mockMvc.perform(get("/api/shows"))
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));
	}

	@Test
	void repeatedFlywayMigrationsLeaveDefaultSchemaHistoryUnchanged() {
		var historyBefore = jdbcTemplate.queryForList("SELECT * FROM public.flyway_schema_history ORDER BY installed_rank");
		assertThat(flyway.info().applied()).hasSize(4);
		for (int run = 0; run < 3; run++) {
			assertThat(flyway.migrate().migrationsExecuted).isZero();
			assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
			assertThat(flyway.info().pending()).isEmpty();
			assertThat(jdbcTemplate.queryForList("SELECT * FROM public.flyway_schema_history ORDER BY installed_rank"))
					.isEqualTo(historyBefore);
		}
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tbooker.users", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tbooker.bookings", Integer.class)).isZero();
	}

	@Test
	void healthAndDatabaseReadinessAreUp() throws Exception {
		for (String endpoint : new String[] {"/api/v1/health", "/actuator/health", "/actuator/health/readiness"}) {
			mockMvc.perform(get(endpoint))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("UP"));
		}
	}

}
