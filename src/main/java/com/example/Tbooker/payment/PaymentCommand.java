package com.example.Tbooker.payment;

import com.example.Tbooker.entity.PaymentMethod;
import java.util.UUID;

/** Stable provider operation identity and a server-assigned amount snapshot. */
public record PaymentCommand(UUID requestId, PaymentMethod method, long amountMinor, String currency) { }
