package com.example.Tbooker.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record BookingRequest(@NotNull @Positive Long showSeatId) {
}
