package com.example.Tbooker.controller;

import com.example.Tbooker.dto.MovieResponse;
import com.example.Tbooker.dto.TheatreResponse;
import com.example.Tbooker.service.CatalogueMetadataService;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api")
public class CatalogueMetadataController {
	private final CatalogueMetadataService service;
	public CatalogueMetadataController(CatalogueMetadataService service) { this.service = service; }
	@GetMapping("/movies")
	public List<MovieResponse> movies() { return service.getMovies(); }
	@GetMapping("/movies/{id}")
	public MovieResponse movie(@PathVariable @Positive Long id) { return service.getMovie(id); }
	@GetMapping("/theatres")
	public List<TheatreResponse> theatres() { return service.getTheatres(); }
	@GetMapping("/theatres/{id}")
	public TheatreResponse theatre(@PathVariable @Positive Long id) { return service.getTheatre(id); }
}
