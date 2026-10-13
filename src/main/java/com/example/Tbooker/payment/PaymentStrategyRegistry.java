package com.example.Tbooker.payment;

import com.example.Tbooker.entity.PaymentMethod;
import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentStrategyRegistry {
	private final Map<PaymentMethod, PaymentStrategy> strategies;

	public PaymentStrategyRegistry(List<PaymentStrategy> implementations) {
		var registry = new EnumMap<PaymentMethod, PaymentStrategy>(PaymentMethod.class);
		for (var strategy : implementations) {
			if (registry.putIfAbsent(strategy.method(), strategy) != null) {
				throw new IllegalStateException("Duplicate payment strategy: " + strategy.method());
			}
		}
		for (var method : PaymentMethod.values()) {
			if (!registry.containsKey(method)) { throw new IllegalStateException("Missing payment strategy: " + method); }
		}
		strategies = Map.copyOf(registry);
	}

	public PaymentStrategy get(PaymentMethod method) { return strategies.get(method); }
}
