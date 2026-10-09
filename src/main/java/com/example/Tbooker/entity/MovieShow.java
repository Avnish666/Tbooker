package com.example.Tbooker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "movie_shows", schema = "tbooker")
public class MovieShow {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "movie_id", nullable = false)
	private Movie movie;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "screen_id", nullable = false)
	private Screen screen;

	@Column(name = "start_time", nullable = false)
	private Instant startTime;

	protected MovieShow() {
	}

	public MovieShow(Movie movie, Screen screen, Instant startTime) {
		this.movie = movie;
		this.screen = screen;
		this.startTime = startTime;
	}

	public Long getId() {
		return id;
	}

	public Movie getMovie() {
		return movie;
	}

	public Screen getScreen() {
		return screen;
	}

	public Instant getStartTime() {
		return startTime;
	}

}
