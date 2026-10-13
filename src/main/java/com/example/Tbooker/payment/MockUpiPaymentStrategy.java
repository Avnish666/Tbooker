package com.example.Tbooker.payment;

import com.example.Tbooker.config.MockPaymentProperties;
import com.example.Tbooker.entity.PaymentMethod;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@EnableConfigurationProperties(MockPaymentProperties.class)
@Transactional(propagation = Propagation.NEVER)
public class MockUpiPaymentStrategy implements PaymentStrategy {
	private final MockGatewayStore gateway;
	private final MockPaymentProperties properties;
	public MockUpiPaymentStrategy(MockGatewayStore gateway, MockPaymentProperties properties) {
		this.gateway = gateway;
		this.properties = properties;
	}
	@Override public PaymentMethod method() { return PaymentMethod.UPI; }
	@Override public void charge(PaymentCommand command) { gateway.charge(command, properties.upiOutcome()); }
	@Override public GatewayResult verify(PaymentCommand command) { return gateway.verify(command); }
}
