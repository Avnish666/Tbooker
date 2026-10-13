package com.example.Tbooker.config;

import com.example.Tbooker.service.SeatInventoryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "reservations.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationCleanup {

	private final SeatInventoryService inventory;

	public ReservationCleanup(SeatInventoryService inventory) {
		this.inventory = inventory;
	}

	@Scheduled(fixedDelayString = "${reservations.cleanup.delay-ms:30000}",
			initialDelayString = "${reservations.cleanup.delay-ms:30000}")
	public void expireReservations() {
		// Bounded transaction; another tick handles backlog. Replicas skip each other's locked rows.
		inventory.expireBatch();
	}

}
