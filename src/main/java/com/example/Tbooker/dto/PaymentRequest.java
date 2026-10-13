package com.example.Tbooker.dto;

import com.example.Tbooker.entity.PaymentMethod;
import jakarta.validation.constraints.NotNull;

public record PaymentRequest(@NotNull PaymentMethod method) { }
