package com.berrx.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * ArbitrageOpportunity entity representing a detected arbitrage opportunity.
 * Includes expiration time and execution tracking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("arbitrage_opportunities")
public class ArbitrageOpportunity {

    @Id
    private Long id;

    /**
     * Type of arbitrage opportunity
     */
    @Column("opportunity_type")
    private OpportunityType opportunityType;

    /**
     * Profit percentage (e.g., 0.015 for 1.5%)
     */
    @Column("profit_percentage")
    private BigDecimal profitPercentage;

    /**
     * Estimated profit amount in SOL
     */
    @Column("profit_amount_sol")
    private BigDecimal profitAmountSol;

    /**
     * Trading path description (e.g., SOL->USDC->USDT->SOL)
     */
    @Column("path")
    private String path;

    /**
     * Input amount for the arbitrage
     */
    @Column("input_amount")
    private BigDecimal inputAmount;

    /**
     * Expected output amount
     */
    @Column("output_amount")
    private BigDecimal outputAmount;

    /**
     * JSON array of pool addresses involved in arbitrage
     */
    @Column("pools_involved")
    private String poolsInvolved;

    /**
     * When this opportunity was created
     */
    @Column("created_at")
    private LocalDateTime createdAt;

    /**
     * When this opportunity expires
     */
    @Column("expires_at")
    private LocalDateTime expiresAt;

    /**
     * Whether this opportunity has been executed
     */
    @Column("executed")
    private Boolean executed;

    /**
     * Transaction signature if executed
     */
    @Column("execution_tx")
    private String executionTx;

    /**
     * Execution status
     */
    @Column("execution_status")
    private String executionStatus;

    /**
     * Actual profit achieved (if executed)
     */
    @Column("execution_profit_actual")
    private BigDecimal executionProfitActual;

    /**
     * Auto-calculated priority score (read-only, computed by DB)
     */
    @Column("priority_score")
    private BigDecimal priorityScore;

    /**
     * Enum for opportunity types
     */
    public enum OpportunityType {
        TRIANGLE,   // Three-way arbitrage (A->B->C->A)
        TWO_WAY     // Two-way arbitrage (A->B->A via different routes)
    }

    /**
     * Check if this opportunity is still valid (not expired and not executed)
     */
    public boolean isValid() {
        return !isExpired() && !isExecuted();
    }

    /**
     * Check if this opportunity has expired
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if this opportunity has been executed
     */
    public boolean isExecuted() {
        return executed != null && executed;
    }

    /**
     * Get remaining time until expiration in seconds
     */
    public long getSecondsUntilExpiration() {
        if (expiresAt == null) return 0;

        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(expiresAt)) return 0;

        return ChronoUnit.SECONDS.between(now, expiresAt);
    }

    /**
     * Get lifetime of this opportunity in seconds
     */
    public long getLifetimeSeconds() {
        if (createdAt == null || expiresAt == null) return 0;
        return ChronoUnit.SECONDS.between(createdAt, expiresAt);
    }

    /**
     * Format profit percentage for display
     */
    public String getFormattedProfitPercentage() {
        if (profitPercentage == null) return "0.00%";
        return String.format("%.3f%%", profitPercentage.multiply(BigDecimal.valueOf(100)));
    }

    /**
     * Format profit amount for display
     */
    public String getFormattedProfitAmount() {
        if (profitAmountSol == null) return "0.00 SOL";
        return String.format("%.6f SOL", profitAmountSol);
    }

    /**
     * Get short description of the opportunity
     */
    public String getShortDescription() {
        return String.format("%s arbitrage: %s profit, %ds left",
                opportunityType.name().toLowerCase(),
                getFormattedProfitPercentage(),
                getSecondsUntilExpiration());
    }

    /**
     * Parse pools involved from JSON string
     */
    public List<String> getPoolAddresses() {
        if (poolsInvolved == null || poolsInvolved.isEmpty()) {
            return List.of();
        }

        try {
            // Simple JSON array parsing - expecting ["addr1","addr2","addr3"]
            String cleaned = poolsInvolved.replaceAll("[\\[\\]\"]", "");
            if (cleaned.isEmpty()) return List.of();

            return List.of(cleaned.split(","));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Set pools involved from list of addresses
     */
    public void setPoolAddresses(List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            this.poolsInvolved = "[]";
        } else {
            // Simple JSON array formatting
            this.poolsInvolved = "[\"" + String.join("\",\"", addresses) + "\"]";
        }
    }

    /**
     * Mark as executed with transaction signature
     */
    public void markExecuted(String txSignature, String status) {
        this.executed = true;
        this.executionTx = txSignature;
        this.executionStatus = status;
    }

    /**
     * Calculate expected profit in percentage points
     */
    public double getProfitBasisPoints() {
        if (profitPercentage == null) return 0;
        return profitPercentage.multiply(BigDecimal.valueOf(10000)).doubleValue();
    }

    /**
     * Check if this is a high-value opportunity (>1% profit)
     */
    public boolean isHighValue() {
        return profitPercentage != null &&
                profitPercentage.compareTo(BigDecimal.valueOf(0.01)) > 0;
    }

    /**
     * Get execution result summary
     */
    public String getExecutionSummary() {
        if (!isExecuted()) {
            return "Not executed";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Executed: ").append(executionStatus != null ? executionStatus : "UNKNOWN");

        if (executionTx != null) {
            summary.append(" (").append(executionTx.substring(0, 8)).append("...)");
        }

        if (executionProfitActual != null) {
            summary.append(", Actual profit: ").append(String.format("%.6f SOL", executionProfitActual));
        }

        return summary.toString();
    }

    @Override
    public String toString() {
        return String.format("ArbitrageOpportunity{id=%d, type=%s, profit=%s, path='%s', valid=%s}",
                id, opportunityType, getFormattedProfitPercentage(), path, isValid());
    }
}