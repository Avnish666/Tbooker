package com.example.Tbooker.payment;

import com.example.Tbooker.entity.PaymentStatus;
import java.util.UUID;

/** Internal provider data, never accepted as a client API body. */
public record GatewayResult(UUID requestId, String providerReference, long amountMinor,
		String currency, PaymentStatus status, long version) { }
