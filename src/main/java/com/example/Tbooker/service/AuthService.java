package com.example.Tbooker.service;

import com.example.Tbooker.dto.AuthResponse;
import com.example.Tbooker.dto.LoginRequest;
import com.example.Tbooker.dto.RegisterRequest;
import com.example.Tbooker.dto.UserResponse;
import com.example.Tbooker.entity.User;
import com.example.Tbooker.exception.DuplicateEmailException;
import com.example.Tbooker.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final TokenService tokenService;
	private final String dummyPasswordHash;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenService = tokenService;
		// Unknown/legacy accounts still pay the BCrypt verification cost.
		this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	@Transactional
	public UserResponse register(RegisterRequest request) {
		String email = request.email().strip().toLowerCase(Locale.ROOT);
		if (userRepository.existsByEmailIgnoreCase(email)) {
			throw new DuplicateEmailException();
		}
		var user = new User(request.name().strip(), email, passwordEncoder.encode(request.password()));
		userRepository.saveAndFlush(user);
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
	}

	public AuthResponse login(LoginRequest request) {
		var user = userRepository.findByEmailIgnoreCase(request.email().strip()).orElse(null);
		String hash = user == null || user.getPasswordHash() == null ? dummyPasswordHash : user.getPasswordHash();
		boolean matches = passwordEncoder.matches(request.password(), hash);
		if (!matches || user == null || user.getPasswordHash() == null) {
			throw new BadCredentialsException("Invalid email or password");
		}
		return tokenService.issue(user);
	}
}
