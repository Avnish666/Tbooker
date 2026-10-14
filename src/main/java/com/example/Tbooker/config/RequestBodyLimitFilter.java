package com.example.Tbooker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Bounded buffering for the synchronous JSON API, including chunked requests. */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

	private static final int MAX_BYTES = 16 * 1024;
	private final ObjectMapper mapper;

	public RequestBodyLimitFilter(ObjectMapper mapper) { this.mapper = mapper; }

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		if (request.getContentLengthLong() > MAX_BYTES) {
			reject(response);
			return;
		}
		byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
		if (body.length > MAX_BYTES) {
			reject(response);
			return;
		}
		chain.doFilter(new HttpServletRequestWrapper(request) {
			private final ByteArrayInputStream input = new ByteArrayInputStream(body);
			@Override public ServletInputStream getInputStream() {
				return new ServletInputStream() {
					@Override public int read() { return input.read(); }
					@Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
					@Override public boolean isFinished() { return input.available() == 0; }
					@Override public boolean isReady() { return true; }
					@Override public void setReadListener(ReadListener listener) {
						throw new IllegalStateException("Asynchronous request bodies are not supported");
					}
				};
			}
			@Override public BufferedReader getReader() throws IOException {
				return new BufferedReader(new InputStreamReader(getInputStream(),
						getCharacterEncoding() == null ? StandardCharsets.UTF_8.name() : getCharacterEncoding()));
			}
		}, response);
	}

	private void reject(HttpServletResponse response) throws IOException {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
				"Request body must not exceed 16384 bytes");
		problem.setProperty("correlationId", MDC.get("correlationId"));
		response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		mapper.writeValue(response.getOutputStream(), problem);
	}
}
