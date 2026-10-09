package com.example.Tbooker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "theatres", schema = "tbooker")
public class Theatre {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(nullable = false, length = 120)
	private String city;

	protected Theatre() {
	}

	public Theatre(String name, String city) {
		this.name = name;
		this.city = city;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getCity() {
		return city;
	}

}
