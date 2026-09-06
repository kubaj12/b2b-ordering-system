package io.github.kubaj12.online_store.shared.web.request;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

final class HtmxRequestArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType().equals(HtmxRequest.class);
	}

	@Override
	public HtmxRequest resolveArgument(
			MethodParameter parameter,
			ModelAndViewContainer modelAndViewContainer,
			NativeWebRequest webRequest,
			WebDataBinderFactory binderFactory
	) {
		HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
		if (request == null) {
			throw new IllegalStateException("HTMX request context requires an HTTP servlet request");
		}
		return HtmxRequest.from(request);
	}

}
