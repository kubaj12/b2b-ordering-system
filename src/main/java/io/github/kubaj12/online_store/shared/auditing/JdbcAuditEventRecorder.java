package io.github.kubaj12.online_store.shared.auditing;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class JdbcAuditEventRecorder implements AuditEventRecorder {

	private static final int MAX_METADATA_BYTES = 16_384;

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	JdbcAuditEventRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(AuditEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		String metadata = serialize(event);

		jdbcTemplate.update("""
				INSERT INTO audit_event (
					event_type,
					target_type,
					target_id,
					acting_user_id,
					occurred_at,
					change_metadata
				) VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
				""",
				event.type().value(),
				event.targetType(),
				event.targetId(),
				event.actor().userId(),
				OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
				metadata
		);
	}

	private String serialize(AuditEvent event) {
		try {
			String metadata = objectMapper.writeValueAsString(event.changeMetadata());
			if (metadata.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
				throw new IllegalArgumentException("audit metadata exceeds the maximum encoded size");
			}
			return metadata;
		}
		catch (JacksonException exception) {
			throw new IllegalArgumentException("audit metadata could not be serialized", exception);
		}
	}

}
