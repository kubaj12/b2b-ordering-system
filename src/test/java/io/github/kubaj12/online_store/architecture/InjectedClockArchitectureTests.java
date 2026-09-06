package io.github.kubaj12.online_store.architecture;

import java.time.Clock;
import java.time.Year;
import java.time.ZoneId;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.importer.ClassFileImporter;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("architecture")
class InjectedClockArchitectureTests {

	@Test
	void rejectsNowWithAZoneId() {
		var importedClasses = new ClassFileImporter().importClasses(UsesZoneId.class);
		var rule = classes()
				.that().haveSimpleName(UsesZoneId.class.getSimpleName())
				.should(ModuleArchitectureTests.USE_INJECTED_CLOCK);

		assertThatThrownBy(() -> rule.check(importedClasses))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("java.time.Year.now(java.time.ZoneId)");
	}

	@Test
	void permitsNowWithAnInjectedClock() {
		var importedClasses = new ClassFileImporter().importClasses(UsesClock.class);
		var rule = classes()
				.that().haveSimpleName(UsesClock.class.getSimpleName())
				.should(ModuleArchitectureTests.USE_INJECTED_CLOCK);

		assertThatCode(() -> rule.check(importedClasses)).doesNotThrowAnyException();
	}

	private static final class UsesZoneId {

		private final ZoneId zoneId;

		private UsesZoneId(ZoneId zoneId) {
			this.zoneId = zoneId;
		}

		Year currentYear() {
			return Year.now(zoneId);
		}

	}

	private static final class UsesClock {

		private final Clock clock;

		private UsesClock(Clock clock) {
			this.clock = clock;
		}

		Year currentYear() {
			return Year.now(clock);
		}

	}

}
