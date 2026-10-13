package com.example.Tbooker.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReservationRequest(@NotNull @Positive Long showSeatId) {
}
