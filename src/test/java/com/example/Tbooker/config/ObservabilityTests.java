package com.example.Tbooker.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.Tbooker.cache.CatalogueCacheErrors;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityTests {
    @Test
    void cacheOutageCountsEveryFailureButBoundsLogsWithoutKeysOrExceptions() {
        var logger = (Logger) LoggerFactory.getLogger(CatalogueCacheErrors.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        var metrics = new SimpleMeterRegistry();
        try {
            var errors = new CatalogueCacheErrors(metrics);
            for (int i = 0; i < 100; i++) errors.handleCacheGetError(
                    new IllegalStateException("secret-exception-sentinel"), new ConcurrentMapCache("shows"), "private-key");
            assertThat(metrics.get("catalogue.cache.errors").counter().count()).isEqualTo(100);
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("secret-exception-sentinel", "private-key");
            assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
        } finally { logger.detachAppender(logs); logs.stop(); metrics.close(); }
    }

    @Test
    void operationalFailuresAreCountedAndSanitizedWithoutFloodingLogs() {
        var logger = (Logger) LoggerFactory.getLogger(OperationalFailures.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        var metrics = new SimpleMeterRegistry();
        try {
            var failures = new OperationalFailures(metrics);
            for (int i = 0; i < 100; i++) failures.record(OperationalFailures.Operation.PAYMENT_CONFIRMATION,
                    new IllegalStateException("private-row-sentinel"));
            assertThat(metrics.get("tbooker.operation.failures").counter().count()).isEqualTo(100);
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("private-row-sentinel");
            assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
        } finally { logger.detachAppender(logs); logs.stop(); metrics.close(); }
    }

    @Test
    void correlationIsRemovedEvenWhenRequestFailsAndDoesNotLeakToNextRequest() throws Exception {
        var filter = new RequestCorrelationFilter();
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), response, (request, res) -> {
            assertThat(MDC.get("correlationId")).isEqualTo(response.getHeader("X-Request-ID"));
            throw new IllegalStateException("failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("correlationId")).isNull();
        var next = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), next, (request, res) -> { });
        assertThat(next.getHeader("X-Request-ID")).isNotEqualTo(response.getHeader("X-Request-ID"));
        assertThat(MDC.get("correlationId")).isNull();
    }
}
