package com.example.Tbooker.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.stereotype.Component;

/** Redis/serialization failure is a cache miss or best-effort write, never a domain success. */
@Component
public class CatalogueCacheErrors implements CacheErrorHandler {
	private static final Logger log = LoggerFactory.getLogger(CatalogueCacheErrors.class);
	private final MeterRegistry metrics;
	public CatalogueCacheErrors(MeterRegistry metrics) { this.metrics = metrics; }
	@Override public void handleCacheGetError(RuntimeException error, Cache cache, Object key) { report("get", cache, error); }
	@Override public void handleCachePutError(RuntimeException error, Cache cache, Object key, Object value) { report("put", cache, error); }
	@Override public void handleCacheEvictError(RuntimeException error, Cache cache, Object key) { report("evict", cache, error); }
	@Override public void handleCacheClearError(RuntimeException error, Cache cache) { report("clear", cache, error); }
	private void report(String operation, Cache cache, RuntimeException error) {
		metrics.counter("catalogue.cache.errors", "operation", operation, "cache", cache.getName()).increment();
		log.warn("Catalogue cache {} {} failed ({}); PostgreSQL remains authoritative", cache.getName(), operation,
				error.getClass().getSimpleName());
	}
}
