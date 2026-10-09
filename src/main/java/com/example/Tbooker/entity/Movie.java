package com.example.Tbooker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "movies", schema = "tbooker")
public class Movie {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	protected Movie() {
	}

	public Movie(String title, int durationMinutes) {
		this.title = title;
		this.durationMinutes = durationMinutes;
	}

	public Long getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public int getDurationMinutes() {
		return durationMinutes;
	}

}
