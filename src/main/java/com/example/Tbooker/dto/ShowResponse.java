package com.example.Tbooker.dto;

import java.time.Instant;

public record ShowResponse(
		Long id,
		Long movieId,
		String movieTitle,
		int durationMinutes,
		Long screenId,
		String screenName,
		Long theatreId,
		String theatreName,
		String city,
		Instant startTime) {
}
