package com.example.Tbooker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Diagnostic identifier only: never an authentication or idempotency key. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestCorrelationFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		String supplied = request.getHeader("X-Request-ID");
		// NGINX overwrites this header at the edge. Direct callers remain untrusted.
		String id = supplied != null && supplied.matches("[a-f0-9]{32}")
				? supplied : UUID.randomUUID().toString().replace("-", "");
		String previous = MDC.get("correlationId");
		MDC.put("correlationId", id);
		response.setHeader("X-Request-ID", id);
		try {
			chain.doFilter(request, response);
		} finally {
			if (previous == null) MDC.remove("correlationId");
			else MDC.put("correlationId", previous);
		}
	}
}
