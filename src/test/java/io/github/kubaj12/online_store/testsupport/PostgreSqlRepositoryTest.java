package io.github.kubaj12.online_store.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import io.github.kubaj12.online_store.TestcontainersConfiguration;

/** Transactional repository slice backed by the same PostgreSQL release as production tests. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public @interface PostgreSqlRepositoryTest {
}
