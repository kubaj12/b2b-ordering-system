package io.github.kubaj12.online_store;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class UtcTimestampPersistenceTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void databaseSessionsUseUtc() {
		String sessionTimeZone = jdbcTemplate.queryForObject("SHOW TIME ZONE", String.class);

		assertThat(sessionTimeZone).isEqualTo("UTC");
	}

	@Test
	void timestampWithTimeZoneRoundTripsAsTheSameInstant() {
		OffsetDateTime supplied = OffsetDateTime.parse("2026-07-15T14:34:56.123456+02:00");
		jdbcTemplate.execute("""
				CREATE TEMPORARY TABLE timestamp_round_trip (
					recorded_at TIMESTAMPTZ(6) NOT NULL
				) ON COMMIT DROP
				""");
		jdbcTemplate.update("INSERT INTO timestamp_round_trip (recorded_at) VALUES (?)", supplied);

		OffsetDateTime persisted = jdbcTemplate.queryForObject(
				"SELECT recorded_at FROM timestamp_round_trip",
				(rs, rowNum) -> rs.getObject("recorded_at", OffsetDateTime.class)
		);

		assertThat(persisted).isNotNull();
		assertThat(persisted.toInstant()).isEqualTo(supplied.toInstant());
		assertThat(persisted.getOffset()).isEqualTo(ZoneOffset.UTC);
	}

	@Test
	void applicationSchemaContainsNoTimestampsWithoutTimeZone() {
		List<String> invalidColumns = jdbcTemplate.queryForList("""
				SELECT table_name || '.' || column_name
				FROM information_schema.columns
				WHERE table_schema = 'public'
				  AND table_name <> 'flyway_schema_history'
				  AND data_type = 'timestamp without time zone'
				ORDER BY table_name, ordinal_position
				""", String.class);

		assertThat(invalidColumns)
				.as("persisted application timestamps must use TIMESTAMPTZ")
				.isEmpty();
	}

}
