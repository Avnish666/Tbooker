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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "bookings", schema = "tbooker")
public class Booking {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "show_seat_id", nullable = false, unique = true, updatable = false)
	private ShowSeat showSeat;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private BookingStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Booking() {
	}

	public Booking(User user, ShowSeat showSeat, Instant createdAt) {
		this.user = user;
		this.showSeat = showSeat;
		this.status = BookingStatus.CONFIRMED;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public User getUser() {
		return user;
	}

	public ShowSeat getShowSeat() {
		return showSeat;
	}

	public BookingStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
