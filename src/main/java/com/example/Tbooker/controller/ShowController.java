package com.example.Tbooker.controller;

import com.example.Tbooker.dto.ShowResponse;
import com.example.Tbooker.dto.ShowSeatResponse;
import com.example.Tbooker.service.ShowCatalogueService;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shows")
public class ShowController {

	private final ShowCatalogueService showCatalogueService;

	public ShowController(ShowCatalogueService showCatalogueService) {
		this.showCatalogueService = showCatalogueService;
	}

	@GetMapping
	public List<ShowResponse> getShows() {
		return showCatalogueService.getShows();
	}

	@GetMapping("/{showId}")
	public ShowResponse getShow(@PathVariable @Positive Long showId) {
		return showCatalogueService.getShow(showId);
	}

	@GetMapping("/{showId}/seats")
	public List<ShowSeatResponse> getShowSeats(@PathVariable @Positive Long showId) {
		return showCatalogueService.getShowSeats(showId);
	}

}
