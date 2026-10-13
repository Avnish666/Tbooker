package com.example.Tbooker.cache;

import com.example.Tbooker.service.CatalogueCacheDependencies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import static com.example.Tbooker.cache.CatalogueCaches.*;

@Component
@ConditionalOnProperty(name = "catalogue.cache.enabled", havingValue = "true", matchIfMissing = true)
public class CatalogueCacheInvalidator {
	private static final Logger log = LoggerFactory.getLogger(CatalogueCacheInvalidator.class);
	private final CacheManager caches;
	private final CatalogueCacheDependencies dependencies;
	private final CatalogueCacheErrors errors;
	public CatalogueCacheInvalidator(CacheManager caches, CatalogueCacheDependencies dependencies, CatalogueCacheErrors errors) {
		this.caches = caches; this.dependencies = dependencies; this.errors = errors;
	}
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void invalidate(CatalogueChanged change) {
		// The database change has already committed. Failure here cannot turn it into a rollback.
		if (change.kind() == CatalogueChanged.Kind.MOVIE) { evict(MOVIE, change.id()); evict(MOVIES, "all"); }
		if (change.kind() == CatalogueChanged.Kind.THEATRE) { evict(THEATRE, change.id()); evict(THEATRES, "all"); }
		evict(SHOWS, "all");
		try { dependencies.affectedShows(change).forEach(id -> evict(SHOW, id)); }
		catch (RuntimeException failure) {
			log.warn("Catalogue dependency invalidation failed ({}); finite TTL bounds remaining cached entries",
					failure.getClass().getSimpleName());
		}
	}
	private void evict(String name, Object key) {
		var cache = caches.getCache(name);
		if (cache == null) { return; }
		try { cache.evictIfPresent(key); }
		catch (RuntimeException failure) { errors.handleCacheEvictError(failure, cache, key); }
	}
}
