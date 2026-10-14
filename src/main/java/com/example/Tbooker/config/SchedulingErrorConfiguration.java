package com.example.Tbooker.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SchedulingErrorConfiguration {
	@Bean
	ThreadPoolTaskSchedulerCustomizer safeScheduledFailures(OperationalFailures failures) {
		return scheduler -> scheduler.setErrorHandler(
				failure -> failures.record(OperationalFailures.Operation.SCHEDULED_TASK, failure));
	}
}
