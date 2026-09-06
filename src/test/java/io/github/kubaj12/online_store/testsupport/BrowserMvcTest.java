package io.github.kubaj12.online_store.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import io.github.kubaj12.online_store.identityaccess.web.BrowserSecurityConfiguration;
import io.github.kubaj12.online_store.shared.web.request.BrowserWebConfiguration;

/** MVC slice that always exercises the application's actual browser security and HTMX setup. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Tag("mvc")
@WebMvcTest
@Import({BrowserWebConfiguration.class, BrowserSecurityConfiguration.class})
public @interface BrowserMvcTest {

	@AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
	Class<?>[] controllers() default {};

}
