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
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments", schema = "tbooker")
public class Payment {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reservation_id", nullable = false, updatable = false)
	private Reservation reservation;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false)
	private PaymentMethod method;
	@Column(name = "amount_minor", nullable = false, updatable = false)
	private long amountMinor;
	@Column(nullable = false, length = 3, updatable = false)
	private String currency;
	@Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
	private String idempotencyKey;
	@Column(name = "gateway_request_id", nullable = false, updatable = false)
	private UUID gatewayRequestId;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
	private PaymentStatus status;
	@Column(name = "provider_reference", length = 100)
	private String providerReference;
	@Column(name = "provider_version", nullable = false)
	private long providerVersion;
	@Enumerated(EnumType.STRING) @Column(name = "confirmation_status", nullable = false, length = 30)
	private PaymentConfirmationStatus confirmationStatus;
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Payment() { }
	public Long getId() { return id; }
	public User getUser() { return user; }
	public Reservation getReservation() { return reservation; }
	public PaymentMethod getMethod() { return method; }
	public long getAmountMinor() { return amountMinor; }
	public String getCurrency() { return currency; }
	public String getIdempotencyKey() { return idempotencyKey; }
	public UUID getGatewayRequestId() { return gatewayRequestId; }
	public PaymentStatus getStatus() { return status; }
	public String getProviderReference() { return providerReference; }
	public long getProviderVersion() { return providerVersion; }
	public PaymentConfirmationStatus getConfirmationStatus() { return confirmationStatus; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
