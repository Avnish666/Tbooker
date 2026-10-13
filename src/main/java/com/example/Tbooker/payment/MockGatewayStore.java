package com.example.Tbooker.payment;

import com.example.Tbooker.entity.PaymentStatus;
import com.example.Tbooker.exception.GatewayVerificationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Simulates durable provider storage; it is not an actual external payment system. */
@Service
public class MockGatewayStore {
	private final JdbcTemplate jdbc;
	public MockGatewayStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void charge(PaymentCommand command, PaymentStatus outcome) {
		jdbc.update("""
				INSERT INTO tbooker.mock_gateway_receipts
					(request_id, method, amount_minor, currency, provider_reference, status, version)
				VALUES (?, ?, ?, ?, ?, ?, 1) ON CONFLICT (request_id) DO NOTHING
				""", command.requestId(), command.method().name(), command.amountMinor(), command.currency(),
				"mock-" + command.method().name().toLowerCase(java.util.Locale.ROOT) + "-" + command.requestId(), outcome.name());
		read(command); // A reused request ID must retain the original request parameters.
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public GatewayResult verify(PaymentCommand command) { return read(command); }

	private GatewayResult read(PaymentCommand command) {
		var rows = jdbc.query("""
				SELECT * FROM tbooker.mock_gateway_receipts WHERE request_id = ? AND method = ?
				AND amount_minor = ? AND currency = ?
				""", (rs, row) -> new GatewayResult(command.requestId(), rs.getString("provider_reference"),
				rs.getLong("amount_minor"), rs.getString("currency"), PaymentStatus.valueOf(rs.getString("status")),
				rs.getLong("version")), command.requestId(), command.method().name(), command.amountMinor(), command.currency());
		if (rows.size() != 1) { throw new GatewayVerificationException("Mock provider receipt is absent or mismatched"); }
		return rows.getFirst();
	}
}
