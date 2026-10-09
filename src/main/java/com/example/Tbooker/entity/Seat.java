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

@Entity
@Table(name = "seats", schema = "tbooker")
public class Seat {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "screen_id", nullable = false)
	private Screen screen;

	@Column(name = "seat_number", nullable = false, length = 20)
	private String seatNumber;

	protected Seat() {
	}

	public Seat(Screen screen, String seatNumber) {
		this.screen = screen;
		this.seatNumber = seatNumber;
	}

	public Long getId() {
		return id;
	}

	public Screen getScreen() {
		return screen;
	}

	public String getSeatNumber() {
		return seatNumber;
	}

}
