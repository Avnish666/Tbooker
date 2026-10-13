package com.example.Tbooker;

import com.example.Tbooker.entity.PaymentMethod;
import com.example.Tbooker.payment.PaymentStrategyRegistry;
import com.example.Tbooker.service.PaymentTransactions;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/** Uses the real mock gateway and verification boundary, stopping before booking confirmation. */
public class PaymentTestFixture {
    private final PaymentTransactions transactions;
    private final PaymentStrategyRegistry registry;
    private final JdbcTemplate jdbc;
    public PaymentTestFixture(PaymentTransactions transactions, PaymentStrategyRegistry registry, JdbcTemplate jdbc) {
        this.transactions = transactions;
        this.registry = registry;
        this.jdbc = jdbc;
    }
    public Long verifyOnly(Long userId, Long reservationId) {
        var attempt = transactions.prepare(userId, reservationId, PaymentMethod.UPI, "test-" + UUID.randomUUID()).payment();
        var gateway = registry.get(PaymentMethod.UPI);
        gateway.charge(attempt.command());
        transactions.recordVerified(attempt.id(), gateway.verify(attempt.command()));
        return attempt.id();
    }
    public void clear() {
        jdbc.update("UPDATE tbooker.reservations SET confirmed_payment_id = NULL");
        jdbc.update("DELETE FROM tbooker.payments");
        jdbc.update("DELETE FROM tbooker.mock_gateway_receipts");
    }
}
