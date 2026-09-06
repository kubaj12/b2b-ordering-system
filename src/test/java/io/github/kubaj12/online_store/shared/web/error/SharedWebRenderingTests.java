package io.github.kubaj12.online_store.shared.web.error;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ErrorProperties.IncludeAttribute;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SharedWebTestController.class)
class SharedWebRenderingTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private WebProperties webProperties;

	@Test
	void bindsSafeSpringBootErrorSettings() {
		var errorProperties = webProperties.getError();
		assertThat(errorProperties.getIncludeBindingErrors()).isEqualTo(IncludeAttribute.NEVER);
		assertThat(errorProperties.isIncludeException()).isFalse();
		assertThat(errorProperties.getIncludeMessage()).isEqualTo(IncludeAttribute.NEVER);
		assertThat(errorProperties.getIncludePath()).isEqualTo(IncludeAttribute.NEVER);
		assertThat(errorProperties.getIncludeStacktrace()).isEqualTo(IncludeAttribute.NEVER);
		assertThat(errorProperties.getWhitelabel().isEnabled()).isFalse();
	}

	@Test
	@WithMockUser(username = "customer@example.test", roles = "CUSTOMER")
	void rendersPolishResponsiveLayoutWithLocalAssets() throws Exception {
		String html = responseBody(mockMvc.perform(get("/test/layout")
				.locale(Locale.ENGLISH))
				.andExpect(status().isOk())
				.andReturn());

		assertThat(html)
				.contains("<html lang=\"pl\"")
				.contains("<meta name=\"_csrf\"")
				.contains("<meta name=\"_csrf_header\"")
				.contains("hx-history=\"false\"")
				.contains("Przejdź do treści")
				.contains("id=\"main-content\"")
				.contains("/webjars/bootstrap/5.3.8/css/bootstrap.min.css")
				.contains("/webjars/htmx.org/2.0.10/dist/htmx.min.js")
				.contains("/css/application.css")
				.contains("/js/application.js")
				.contains("Witaj w systemie zamówień B2B")
				.contains("name=\"_csrf\"");
	}

	@Test
	@WithMockUser(username = "customer@example.test", roles = "CUSTOMER")
	void customerNavigationContainsOnlyCustomerDestinations() throws Exception {
		String html = layoutHtml();

		assertThat(html)
				.contains("href=\"/catalog\"")
				.contains("href=\"/cart\"")
				.contains("href=\"/orders\"")
				.contains("href=\"/account/password\"")
				.doesNotContain("href=\"/staff/")
				.doesNotContain("href=\"/admin/employees\"");
	}

	@Test
	@WithMockUser(username = "employee@example.test", roles = "EMPLOYEE")
	void employeeNavigationContainsStaffDestinationsButNotAdministratorDestination() throws Exception {
		String html = layoutHtml();

		assertThat(html)
				.contains("href=\"/staff/customers\"")
				.contains("href=\"/staff/catalog\"")
				.contains("href=\"/staff/price-lists\"")
				.contains("href=\"/staff/inventory\"")
				.contains("href=\"/staff/orders\"")
				.doesNotContain("href=\"/cart\"")
				.doesNotContain("href=\"/admin/employees\"");
	}

	@Test
	@WithMockUser(username = "admin@example.test", roles = "ADMIN")
	void administratorNavigationIncludesStaffAndAdministratorDestinations() throws Exception {
		String html = layoutHtml();

		assertThat(html)
				.contains("href=\"/staff/customers\"")
				.contains("href=\"/staff/inventory\"")
				.contains("href=\"/admin/employees\"");
	}

	@Test
	@WithMockUser(username = "customer@example.test", roles = "CUSTOMER")
	void rendersAccessibleLocalizedFormErrorsAndEscapedFlashMessages() throws Exception {
		String html = responseBody(mockMvc.perform(get("/test/components"))
				.andExpect(status().isOk())
				.andReturn());

		assertThat(html)
				.contains("data-validation-error-summary")
				.contains("tabindex=\"-1\"")
				.contains("Popraw błędy w formularzu")
				.contains("Przesłane dane są nieprawidłowe.")
				.contains("href=\"#name\"")
				.contains("Pole jest wymagane.")
				.contains("aria-invalid=\"true\"")
				.contains("aria-describedby=\"name-errors\"")
				.contains("id=\"name-errors\"")
				.contains("role=\"status\" aria-live=\"polite\"")
				.contains("role=\"alert\" aria-live=\"assertive\"")
				.contains("Zapisano &lt;script&gt;alert(&#39;sekret&#39;)&lt;/script&gt;")
				.doesNotContain("Zapisano <script>");
	}

	@Test
	@WithMockUser(username = "customer@example.test", roles = "CUSTOMER")
	void rendersBoundedPaginationAndRetainsWhitelistedFilters() throws Exception {
		String html = responseBody(mockMvc.perform(get("/test/components"))
				.andExpect(status().isOk())
				.andReturn());

		assertThat(html)
				.contains("aria-label=\"Nawigacja po stronach wyników\"")
				.contains("aria-current=\"page\"")
				.contains("aria-label=\"Strona 5, bieżąca\"")
				.contains("name=\"page\" value=\"3\"")
				.contains("name=\"page\" value=\"5\"")
				.contains("name=\"size\" value=\"20\"")
				.contains("name=\"query\" value=\"śruby &amp; nakrętki\"")
				.contains("name=\"category\" value=\"metal\"")
				.contains("aria-label=\"Ostatnia strona\"");
	}

	@Test
	@WithMockUser(username = "customer@example.test", roles = "CUSTOMER")
	void rendersPaginationBoundaryAndSinglePageStates() throws Exception {
		String firstPage = responseBody(mockMvc.perform(get("/test/components").param("page", "0"))
				.andExpect(status().isOk())
				.andReturn());
		String lastPage = responseBody(mockMvc.perform(get("/test/components").param("page", "11"))
				.andExpect(status().isOk())
				.andReturn());
		String singlePage = responseBody(mockMvc.perform(get("/test/components")
						.param("page", "0")
						.param("total", "20"))
				.andExpect(status().isOk())
				.andReturn());

		assertThat(firstPage)
				.contains("aria-disabled=\"true\">Poprzednia")
				.contains("name=\"page\" value=\"1\">Następna")
				.doesNotContain("name=\"page\" value=\"-1\"");
		assertThat(lastPage)
				.contains("aria-label=\"Strona 12, bieżąca\"")
				.contains("aria-disabled=\"true\">Następna")
				.doesNotContain("name=\"page\" value=\"12\"");
		assertThat(singlePage).doesNotContain("aria-label=\"Nawigacja po stronach wyników\"");
	}

	@Test
	@WithMockUser(username = "customer@example.test", roles = "CUSTOMER")
	void boundsAnOutOfRangePaginationWindowBeforeCreatingThePageSequence() throws Exception {
		String html = responseBody(mockMvc.perform(get("/test/components")
						.param("page", String.valueOf(Integer.MAX_VALUE)))
				.andExpect(status().isOk())
				.andReturn());

		assertThat(html)
				.contains("aria-label=\"Strona 12, bieżąca\"")
				.contains("name=\"page\" value=\"10\">Poprzednia")
				.contains("aria-disabled=\"true\">Następna")
				.doesNotContain(String.valueOf(Integer.MAX_VALUE));
	}

	@Test
	@WithMockUser
	void servesApplicationAndVersionIndependentWebJarAssets() throws Exception {
		mockMvc.perform(get("/css/application.css"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/css")));
		MvcResult script = mockMvc.perform(get("/js/application.js"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/javascript"))
				.andReturn();
		mockMvc.perform(get("/webjars/bootstrap/css/bootstrap.min.css"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/css")));
		mockMvc.perform(get("/webjars/htmx.org/dist/htmx.min.js"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/javascript"));

		assertThat(responseBody(script))
				.contains(
						"htmx:configRequest",
						"X-B2B-Handled-Error",
						"htmx:beforeSwap",
						"htmx:historyCacheMissLoadError",
						"window.location.assign(target.href)"
				)
				.contains("event.detail.headers[csrf.header] = csrf.token");
	}

	private String layoutHtml() throws Exception {
		return responseBody(mockMvc.perform(get("/test/layout"))
				.andExpect(status().isOk())
				.andReturn());
	}

	private static String responseBody(MvcResult result) {
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

}
