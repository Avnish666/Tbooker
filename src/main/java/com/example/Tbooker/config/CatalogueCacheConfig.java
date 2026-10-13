package com.example.Tbooker.config;

import com.example.Tbooker.cache.CatalogueCacheErrors;
import com.example.Tbooker.dto.MovieResponse;
import com.example.Tbooker.dto.TheatreResponse;
import com.example.Tbooker.dto.ShowResponse;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static com.example.Tbooker.cache.CatalogueCaches.*;

@Configuration
@EnableCaching
@EnableConfigurationProperties(CatalogueCacheProperties.class)
public class CatalogueCacheConfig implements CachingConfigurer {
	private final CatalogueCacheErrors errors;
	public CatalogueCacheConfig(CatalogueCacheErrors errors) { this.errors = errors; }
	@Override public CacheErrorHandler errorHandler() { return errors; }

	@Bean
	@ConditionalOnProperty(name = "catalogue.cache.enabled", havingValue = "true", matchIfMissing = true)
	CacheManager redisCatalogueCacheManager(RedisConnectionFactory connections, ObjectMapper mapper, CatalogueCacheProperties properties) {
		var types = mapper.getTypeFactory();
		return RedisCacheManager.builder(connections).disableCreateOnMissingCache().enableStatistics().transactionAware()
				.withInitialCacheConfigurations(Map.of(
						MOVIES, configuration(mapper, types.constructCollectionType(List.class, MovieResponse.class), properties.metadataListTtl()),
						MOVIE, configuration(mapper, types.constructType(MovieResponse.class), properties.metadataTtl()),
						THEATRES, configuration(mapper, types.constructCollectionType(List.class, TheatreResponse.class), properties.metadataListTtl()),
						THEATRE, configuration(mapper, types.constructType(TheatreResponse.class), properties.metadataTtl()),
						SHOWS, configuration(mapper, types.constructCollectionType(List.class, ShowResponse.class), properties.showListTtl()),
						SHOW, configuration(mapper, types.constructType(ShowResponse.class), properties.showTtl())))
				.build();
	}

	@Bean
	@ConditionalOnProperty(name = "catalogue.cache.enabled", havingValue = "false")
	CacheManager disabledCatalogueCacheManager() { return new NoOpCacheManager(); }

	private RedisCacheConfiguration configuration(ObjectMapper mapper, JavaType type, Duration ttl) {
		// Explicit DTO types: no JDK serialization, entity graphs, or polymorphic class-name payloads.
		var serializer = new Jackson2JsonRedisSerializer<Object>(mapper.copy(), type);
		return RedisCacheConfiguration.defaultCacheConfig().entryTtl(ttl).disableCachingNullValues()
				.computePrefixWith(name -> "tbooker:catalogue:v1:" + name + "::")
				.serializeValuesWith(SerializationPair.fromSerializer(serializer));
	}
}
