package com.example.Tbooker.service;

import com.example.Tbooker.dto.MovieResponse;
import com.example.Tbooker.dto.TheatreResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import java.util.List;
import static com.example.Tbooker.cache.CatalogueCaches.*;

@Service
public class CatalogueMetadataService {
	private final CatalogueReader reader;
	public CatalogueMetadataService(CatalogueReader reader) { this.reader = reader; }
	@Cacheable(cacheNames = MOVIES, key = "'all'", condition = OUTSIDE_TRANSACTION)
	public List<MovieResponse> getMovies() { return reader.getMovies(); }
	@Cacheable(cacheNames = MOVIE, key = "#p0", condition = OUTSIDE_TRANSACTION)
	public MovieResponse getMovie(Long id) { return reader.getMovie(id); }
	@Cacheable(cacheNames = THEATRES, key = "'all'", condition = OUTSIDE_TRANSACTION)
	public List<TheatreResponse> getTheatres() { return reader.getTheatres(); }
	@Cacheable(cacheNames = THEATRE, key = "#p0", condition = OUTSIDE_TRANSACTION)
	public TheatreResponse getTheatre(Long id) { return reader.getTheatre(id); }
}
