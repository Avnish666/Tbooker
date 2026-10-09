package com.example.Tbooker.service;

import com.example.Tbooker.dto.ShowResponse;
import com.example.Tbooker.dto.ShowSeatResponse;
import com.example.Tbooker.entity.MovieShow;
import com.example.Tbooker.exception.ShowNotFoundException;
import com.example.Tbooker.repository.MovieShowRepository;
import com.example.Tbooker.repository.ShowSeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ShowCatalogueService {

	private final MovieShowRepository movieShowRepository;
	private final ShowSeatRepository showSeatRepository;

	public ShowCatalogueService(MovieShowRepository movieShowRepository, ShowSeatRepository showSeatRepository) {
		this.movieShowRepository = movieShowRepository;
		this.showSeatRepository = showSeatRepository;
	}

	public List<ShowResponse> getShows() {
		return movieShowRepository.findAllByOrderByStartTimeAscIdAsc().stream()
				.map(this::toResponse)
				.toList();
	}

	public ShowResponse getShow(Long showId) {
		return movieShowRepository.findById(showId)
				.map(this::toResponse)
				.orElseThrow(() -> new ShowNotFoundException(showId));
	}

	public List<ShowSeatResponse> getShowSeats(Long showId) {
		if (!movieShowRepository.existsById(showId)) {
			throw new ShowNotFoundException(showId);
		}
		return showSeatRepository.findByMovieShowIdOrderBySeatSeatNumberAscIdAsc(showId).stream()
				.map(showSeat -> new ShowSeatResponse(showSeat.getId(), showId,
						showSeat.getSeat().getId(), showSeat.getSeat().getSeatNumber(), showSeat.getStatus()))
				.toList();
	}

	private ShowResponse toResponse(MovieShow show) {
		var movie = show.getMovie();
		var screen = show.getScreen();
		var theatre = screen.getTheatre();
		return new ShowResponse(show.getId(), movie.getId(), movie.getTitle(), movie.getDurationMinutes(),
				screen.getId(), screen.getName(), theatre.getId(), theatre.getName(), theatre.getCity(),
				show.getStartTime());
	}

}
