package com.example.Tbooker.service;

import com.example.Tbooker.dto.ShowResponse;
import com.example.Tbooker.dto.ShowSeatResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import java.util.List;
import static com.example.Tbooker.cache.CatalogueCaches.*;

@Service
public class ShowCatalogueService {
	private final CatalogueReader reader;
	public ShowCatalogueService(CatalogueReader reader) { this.reader = reader; }

	@Cacheable(cacheNames = SHOWS, key = "'all'", condition = OUTSIDE_TRANSACTION)
	public List<ShowResponse> getShows() { return reader.getShows(); }

	@Cacheable(cacheNames = SHOW, key = "#p0", condition = OUTSIDE_TRANSACTION)
	public ShowResponse getShow(Long showId) { return reader.getShow(showId); }

	// Expiry-aware inventory is deliberately never cached.
	public List<ShowSeatResponse> getShowSeats(Long showId) { return reader.getShowSeats(showId); }
}
