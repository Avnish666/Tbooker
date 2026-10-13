package com.example.Tbooker.config;

import com.example.Tbooker.entity.PaymentStatus;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payments.mock")
public record MockPaymentProperties(@DefaultValue("10000") @Positive long amountMinor,
		@DefaultValue("SUCCESS") PaymentStatus upiOutcome,
		@DefaultValue("SUCCESS") PaymentStatus cardOutcome) { }
