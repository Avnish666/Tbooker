package com.example.Tbooker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users", schema = "tbooker")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(nullable = false, length = 254)
	private String email;

	@Column(name = "password_hash", length = 60)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Role role = Role.USER;

	protected User() {
	}

	public User(String name, String email) {
		this.name = name;
		this.email = email;
	}

	public Long getId() {
		return id;
	}

	public User(String name, String email, String passwordHash) {
		this(name, email);
		this.passwordHash = passwordHash;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Role getRole() {
		return role;
	}

	public String getName() {
		return name;
	}

	public String getEmail() {
		return email;
	}

}
