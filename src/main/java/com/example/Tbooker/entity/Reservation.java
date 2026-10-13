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
@Table(name = "reservations", schema = "tbooker")
public class Reservation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "show_seat_id", nullable = false, updatable = false)
	private ShowSeat showSeat;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReservationStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "booking_id", unique = true)
	private Booking booking;

	@Column(name = "confirmed_payment_id")
	private Long confirmedPaymentId;

	public Long getConfirmedPaymentId() { return confirmedPaymentId; }

	protected Reservation() {
	}

	public Reservation(User user, ShowSeat showSeat, Instant createdAt, Instant expiresAt) {
		this.user = user;
		this.showSeat = showSeat;
		this.status = ReservationStatus.HELD;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
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
	public ReservationStatus getStatus() {
		return status;
	}
	public Instant getCreatedAt() {
		return createdAt;
	}
	public Instant getExpiresAt() {
		return expiresAt;
	}
	public Booking getBooking() {
		return booking;
	}

}
