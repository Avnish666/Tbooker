package com.example.Tbooker.config;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("catalogue.cache")
public record CatalogueCacheProperties(@DefaultValue("PT10M") Duration metadataTtl,
		@DefaultValue("PT5M") Duration metadataListTtl, @DefaultValue("PT1M") Duration showTtl,
		@DefaultValue("PT30S") Duration showListTtl) {
	@AssertTrue(message = "Catalogue cache TTLs must be positive and at most one day")
	public boolean isTtlValid() {
		return java.util.stream.Stream.of(metadataTtl, metadataListTtl, showTtl, showListTtl)
				.allMatch(ttl -> ttl != null && !ttl.isNegative() && !ttl.isZero() && ttl.compareTo(Duration.ofDays(1)) <= 0);
	}
}
