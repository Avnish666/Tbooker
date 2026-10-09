package com.example.Tbooker.dto;

import com.example.Tbooker.entity.Role;

public record UserResponse(Long id, String name, String email, Role role) {
}
