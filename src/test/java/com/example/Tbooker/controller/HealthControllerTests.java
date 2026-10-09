package com.example.Tbooker.controller;

import com.example.Tbooker.SecurityTestSupport;
import com.example.Tbooker.config.SecurityConfig;
import com.example.Tbooker.config.SecurityProblemHandler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, SecurityProblemHandler.class})
class HealthControllerTests extends SecurityTestSupport {

	private final MockMvc mockMvc;

	@Autowired
	HealthControllerTests(MockMvc mockMvc) {
		this.mockMvc = mockMvc;
	}

	@Test
	void healthReturnsJsonWithoutAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/health"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(content().json("{\"status\":\"UP\"}"));
	}

	@Test
	void unsupportedMethodReturnsProblemDetail() throws Exception {
		mockMvc.perform(post("/api/v1/health"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(405))
				.andExpect(jsonPath("$.title").value("Method Not Allowed"));
	}

}
