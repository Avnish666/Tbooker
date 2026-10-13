package com.example.Tbooker.service;

import com.example.Tbooker.dto.ShowResponse;
import com.example.Tbooker.dto.MovieResponse;
import com.example.Tbooker.dto.TheatreResponse;
import com.example.Tbooker.repository.MovieRepository;
import com.example.Tbooker.repository.TheatreRepository;
import com.example.Tbooker.exception.ResourceNotFoundException;
import com.example.Tbooker.dto.ShowSeatResponse;
import com.example.Tbooker.entity.MovieShow;
import com.example.Tbooker.entity.ShowSeatStatus;
import com.example.Tbooker.exception.ShowNotFoundException;
import com.example.Tbooker.repository.MovieShowRepository;
import com.example.Tbooker.repository.ShowSeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class CatalogueReader {

	private final MovieShowRepository movieShowRepository;
	private final ShowSeatRepository showSeatRepository;
	private final MovieRepository movies;
	private final TheatreRepository theatres;

	public CatalogueReader(MovieShowRepository movieShowRepository, ShowSeatRepository showSeatRepository,
			MovieRepository movies, TheatreRepository theatres) {
		this.movieShowRepository = movieShowRepository;
		this.showSeatRepository = showSeatRepository;
		this.movies = movies;
		this.theatres = theatres;
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
		return showSeatRepository.findAvailabilityByShowId(showId).stream()
				.map(showSeat -> new ShowSeatResponse(showSeat.getId(), showId,
						showSeat.getSeatId(), showSeat.getSeatNumber(), ShowSeatStatus.valueOf(showSeat.getStatus())))
				.toList();
	}

	public List<MovieResponse> getMovies() {
		return movies.findAllByOrderByIdAsc().stream().map(this::movieResponse).toList();
	}
	public MovieResponse getMovie(Long id) {
		return movies.findById(id).map(this::movieResponse).orElseThrow(() -> new ResourceNotFoundException("Movie", id));
	}
	public List<TheatreResponse> getTheatres() {
		return theatres.findAllByOrderByIdAsc().stream().map(this::theatreResponse).toList();
	}
	public TheatreResponse getTheatre(Long id) {
		return theatres.findById(id).map(this::theatreResponse).orElseThrow(() -> new ResourceNotFoundException("Theatre", id));
	}
	private MovieResponse movieResponse(com.example.Tbooker.entity.Movie movie) {
		return new MovieResponse(movie.getId(), movie.getTitle(), movie.getDurationMinutes());
	}
	private TheatreResponse theatreResponse(com.example.Tbooker.entity.Theatre theatre) {
		return new TheatreResponse(theatre.getId(), theatre.getName(), theatre.getCity());
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
