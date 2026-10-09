package com.example.Tbooker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "show_seats", schema = "tbooker")
public class ShowSeat {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "show_id", nullable = false)
	private MovieShow movieShow;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "seat_id", nullable = false)
	private Seat seat;

	// Both database foreign keys use this to enforce matching screen membership.
	@Column(name = "screen_id", nullable = false)
	private Long screenId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ShowSeatStatus status;

	protected ShowSeat() {
	}

	public ShowSeat(MovieShow movieShow, Seat seat) {
		Long showScreenId = movieShow.getScreen().getId();
		if (showScreenId == null || !showScreenId.equals(seat.getScreen().getId())) {
			throw new IllegalArgumentException("Show and seat must belong to the same persisted screen");
		}
		this.movieShow = movieShow;
		this.seat = seat;
		this.screenId = showScreenId;
		this.status = ShowSeatStatus.AVAILABLE;
	}

	public Long getId() {
		return id;
	}

	public MovieShow getMovieShow() {
		return movieShow;
	}

	public Seat getSeat() {
		return seat;
	}

	public ShowSeatStatus getStatus() {
		return status;
	}

}
