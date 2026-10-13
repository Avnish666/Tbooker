package com.example.Tbooker.payment;

import com.example.Tbooker.entity.PaymentMethod;

public interface PaymentStrategy {
	PaymentMethod method();

	/** Retry the same operation identity and parameters; never start a new charge for an uncertain result. */
	void charge(PaymentCommand command);

	/** Independently retrieve the provider's canonical receipt, rather than trusting a notification body. */
	GatewayResult verify(PaymentCommand command);
}
