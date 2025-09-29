package com.berrx.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ArbitrageOpportunity entity for storing arbitrage opportunities with lifecycle tracking.
 *
 * FEATURES:
 * - DEX addresses and pair information
 * - Profit calculations and estimates
 * - Lifecycle tracking (created, verified, expired, executed)
 * - Time-based expiration management
 * - RPC verification status
 *
 * STATUSES:
 * - DISCOVERED: Found via DexScreener price analysis
 * - VERIFIED: Confirmed via Solana RPC calls
 * - EXPIRED: Opportunity no longer profitable/valid
 * - EXECUTED: Trade executed successfully
 * - FAILED: Execution failed
 */
@Entity
@Table(name = "arbitrage_opportunities", indexes = {
        @Index(name = "idx_arb_status_created", columnList = "status, created_at"),
        @Index(name = "idx_arb_expires", columnList = "expires_at"),
        @Index(name = "idx_arb_profit", columnList = "profit_percentage DESC"),
        @Index(name = "idx_arb_active", columnList = "status, expires_at"),
        @Index(name = "idx_arb_dex_pair", columnList = "dex_1, dex_2, token_pair_id"),
        @Index(name = "idx_arb_verification", columnList = "rpc_verified, last_verification_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArbitrageOpportunity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =================== OPPORTUNITY IDENTIFICATION ===================

    /**
     * Type of arbitrage (TWO_WAY, TRIANGLE, CROSS_DEX)
     */
    @Column(name = "opportunity_type", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private OpportunityType opportunityType;

    /**
     * Current status of the opportunity
     */
    @Column(name = "status", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private OpportunityStatus status;

    /**
     * Unique identifier for token pair (tokenA-tokenB sorted)
     */
    @Column(name = "token_pair_id", length = 89, nullable = false)
    private String tokenPairId;

    // =================== DEX AND POOL INFORMATION ===================

    /**
     * First DEX name (raydium, orca, meteora)
     */
    @Column(name = "dex_1", length = 20, nullable = false)
    private String dex1;

    /**
     * Second DEX name
     */
    @Column(name = "dex_2", length = 20, nullable = false)
    private String dex2;

    /**
     * First pool address
     */
    @Column(name = "pool_1_address", length = 44, nullable = false)
    private String pool1Address;

    /**
     * Second pool address
     */
    @Column(name = "pool_2_address", length = 44, nullable = false)
    private String pool2Address;

    /**
     * Token A mint address
     */
    @Column(name = "token_a_mint", length = 44, nullable = false)
    private String tokenAMint;

    /**
     * Token B mint address
     */
    @Column(name = "token_b_mint", length = 44, nullable = false)
    private String tokenBMint;

    /**
     * Token symbols for display (A/B)
     */
    @Column(name = "token_symbols", length = 50)
    private String tokenSymbols;

    // =================== PROFIT CALCULATIONS ===================

    /**
     * Profit percentage (e.g., 1.5 for 1.5%)
     */
    @Column(name = "profit_percentage", nullable = false, precision = 10, scale = 6)
    private BigDecimal profitPercentage;

    /**
     * Estimated profit in USD
     */
    @Column(name = "estimated_profit_usd", precision = 15, scale = 6)
    private BigDecimal estimatedProfitUsd;

    /**
     * Input amount for arbitrage
     */
    @Column(name = "input_amount", precision = 15, scale = 6)
    private BigDecimal inputAmount;

    /**
     * Expected output amount
     */
    @Column(name = "output_amount", precision = 15, scale = 6)
    private BigDecimal outputAmount;

    /**
     * Trading path description (DEX1 -> DEX2)
     */
    @Column(name = "trading_path", length = 200)
    private String tradingPath;

    // =================== PRICE INFORMATION ===================

    /**
     * Price on first DEX
     */
    @Column(name = "price_dex_1", precision = 20, scale = 10)
    private BigDecimal priceDex1;

    /**
     * Price on second DEX
     */
    @Column(name = "price_dex_2", precision = 20, scale = 10)
    private BigDecimal priceDex2;

    /**
     * Price difference percentage
     */
    @Column(name = "price_difference_percent", precision = 10, scale = 6)
    private BigDecimal priceDifferencePercent;

    // =================== LIFECYCLE TIMESTAMPS ===================

    /**
     * When opportunity was discovered
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * When opportunity expires
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * Last update time
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * When opportunity was closed (expired/executed)
     */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    // =================== RPC VERIFICATION ===================

    /**
     * Whether opportunity was verified via RPC
     */
    @Column(name = "rpc_verified", nullable = false)
    private Boolean rpcVerified;

    /**
     * Last RPC verification time
     */
    @Column(name = "last_verification_at")
    private LocalDateTime lastVerificationAt;

    /**
     * Number of RPC verification attempts
     */
    @Column(name = "verification_attempts")
    private Integer verificationAttempts;

    /**
     * RPC verification result details
     */
    @Column(name = "verification_notes", length = 500)
    private String verificationNotes;

    // =================== EXECUTION TRACKING ===================

    /**
     * Transaction signature if executed
     */
    @Column(name = "execution_tx", length = 88)
    private String executionTx;

    /**
     * Actual profit achieved (if executed)
     */
    @Column(name = "actual_profit_usd", precision = 15, scale = 6)
    private BigDecimal actualProfitUsd;

    /**
     * Slippage experienced during execution
     */
    @Column(name = "execution_slippage", precision = 10, scale = 6)
    private BigDecimal executionSlippage;

    /**
     * Execution error message (if failed)
     */
    @Column(name = "execution_error", length = 1000)
    private String executionError;

    // =================== QUALITY METRICS ===================

    /**
     * Priority score for execution order
     */
    @Column(name = "priority_score", precision = 10, scale = 2)
    private BigDecimal priorityScore;

    /**
     * Combined TVL of both pools
     */
    @Column(name = "total_tvl_usd", precision = 15, scale = 2)
    private BigDecimal totalTvlUsd;

    /**
     * Data source (DEXSCREENER, RPC_VERIFIED)
     */
    @Column(name = "data_source", length = 20)
    private String dataSource;

    // =================== ENUMS ===================

    public enum OpportunityType {
        TWO_WAY,        // Simple DEX A -> DEX B arbitrage
        TRIANGLE,       // Three-token arbitrage
        CROSS_DEX       // Multi-DEX arbitrage
    }

    public enum OpportunityStatus {
        DISCOVERED,     // Found via DexScreener analysis
        VERIFIED,       // Confirmed via RPC
        EXPIRED,        // No longer profitable
        EXECUTED,       // Successfully executed
        FAILED          // Execution failed
    }

    // =================== BUSINESS METHODS ===================

    /**
     * Check if opportunity is still active
     */
    public boolean isActive() {
        return status == OpportunityStatus.DISCOVERED ||
                status == OpportunityStatus.VERIFIED;
    }

    /**
     * Check if opportunity has expired
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt) ||
                status == OpportunityStatus.EXPIRED;
    }

    /**
     * Get lifetime in seconds
     */
    public long getLifetimeSeconds() {
        LocalDateTime endTime = closedAt != null ? closedAt : LocalDateTime.now();
        return java.time.Duration.between(createdAt, endTime).getSeconds();
    }

    /**
     * Get remaining time in seconds
     */
    public long getRemainingSeconds() {
        if (isExpired()) return 0;
        return java.time.Duration.between(LocalDateTime.now(), expiresAt).getSeconds();
    }

    /**
     * Calculate success rate of RPC verifications
     */
    public double getVerificationSuccessRate() {
        if (verificationAttempts == null || verificationAttempts == 0) return 0.0;
        return rpcVerified ? 100.0 : 0.0;
    }

    /**
     * Get display name for logging
     */
    public String getDisplayName() {
        return String.format("%s %s->%s (%.2f%%)",
                tokenSymbols != null ? tokenSymbols : "???/???",
                dex1, dex2,
                profitPercentage != null ? profitPercentage.doubleValue() : 0.0);
    }

    /**
     * Get execution status description
     */
    public String getStatusDescription() {
        return switch (status) {
            case DISCOVERED -> "Found via DexScreener";
            case VERIFIED -> "RPC Verified ✅";
            case EXPIRED -> "Expired ⏰";
            case EXECUTED -> "Executed ✅";
            case FAILED -> "Failed ❌";
        };
    }

    /**
     * Mark as verified
     */
    public void markAsVerified(String notes) {
        this.status = OpportunityStatus.VERIFIED;
        this.rpcVerified = true;
        this.lastVerificationAt = LocalDateTime.now();
        this.verificationNotes = notes;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Mark as expired
     */
    public void markAsExpired(String reason) {
        this.status = OpportunityStatus.EXPIRED;
        this.closedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.verificationNotes = reason;
    }

    /**
     * Mark as executed
     */
    public void markAsExecuted(String txSignature, BigDecimal actualProfit) {
        this.status = OpportunityStatus.EXECUTED;
        this.executionTx = txSignature;
        this.actualProfitUsd = actualProfit;
        this.closedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Mark as failed
     */
    public void markAsFailed(String errorMessage) {
        this.status = OpportunityStatus.FAILED;
        this.executionError = errorMessage;
        this.closedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Increment verification attempts
     */
    public void incrementVerificationAttempts() {
        this.verificationAttempts = (verificationAttempts != null ? verificationAttempts : 0) + 1;
        this.lastVerificationAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // =================== BUILDER ENHANCEMENTS ===================

    public static class ArbitrageOpportunityBuilder {

        public ArbitrageOpportunityBuilder withDefaults() {
            this.createdAt = LocalDateTime.now();
            this.updatedAt = LocalDateTime.now();
            this.status = OpportunityStatus.DISCOVERED;
            this.rpcVerified = false;
            this.verificationAttempts = 0;
            this.dataSource = "DEXSCREENER";
            return this;
        }

        public ArbitrageOpportunityBuilder withExpiration(int minutes) {
            this.expiresAt = LocalDateTime.now().plusMinutes(minutes);
            return this;
        }

        public ArbitrageOpportunityBuilder fromPools(Pool pool1, Pool pool2) {
            this.dex1 = pool1.getDexName();
            this.dex2 = pool2.getDexName();
            this.pool1Address = pool1.getAddress();
            this.pool2Address = pool2.getAddress();
            this.tokenAMint = pool1.getTokenAMint();
            this.tokenBMint = pool1.getTokenBMint();
            this.tokenSymbols = pool1.getSymbolPair();
            this.tokenPairId = pool1.getTokenPairId();

            // Calculate combined TVL
            double tvl1 = pool1.getTvlUsd() != null ? pool1.getTvlUsd() : 0.0;
            double tvl2 = pool2.getTvlUsd() != null ? pool2.getTvlUsd() : 0.0;
            this.totalTvlUsd = BigDecimal.valueOf(tvl1 + tvl2);

            return this;
        }

        public ArbitrageOpportunityBuilder twoWayArbitrage() {
            this.opportunityType = OpportunityType.TWO_WAY;
            return this;
        }

        public ArbitrageOpportunityBuilder withProfitCalculation(double profitPercent, double profitUsd) {
            this.profitPercentage = BigDecimal.valueOf(profitPercent);
            this.estimatedProfitUsd = BigDecimal.valueOf(profitUsd);
            return this;
        }

        public ArbitrageOpportunityBuilder withPrices(double price1, double price2) {
            this.priceDex1 = BigDecimal.valueOf(price1);
            this.priceDex2 = BigDecimal.valueOf(price2);

            // Calculate price difference
            double maxPrice = Math.max(price1, price2);
            if (maxPrice > 0) {
                double diffPercent = Math.abs(price1 - price2) / maxPrice * 100;
                this.priceDifferencePercent = BigDecimal.valueOf(diffPercent);
            }

            return this;
        }

        public ArbitrageOpportunityBuilder withTradingPath(String path) {
            this.tradingPath = path;
            return this;
        }

        public ArbitrageOpportunityBuilder calculatePriority() {
            // Simple priority calculation: profit% * TVL factor
            double profit = this.profitPercentage != null ? this.profitPercentage.doubleValue() : 0;
            double tvl = this.totalTvlUsd != null ? this.totalTvlUsd.doubleValue() : 0;
            double tvlFactor = Math.log10(tvl + 1);
            this.priorityScore = BigDecimal.valueOf(profit * tvlFactor);
            return this;
        }
    }

    // =================== STATIC FACTORY METHODS ===================

    /**
     * Create opportunity from two pools
     */
    public static ArbitrageOpportunity fromPools(Pool pool1, Pool pool2,
                                                 double profitPercent, double profitUsd) {
        return ArbitrageOpportunity.builder()
                .withDefaults()
                .fromPools(pool1, pool2)
                .twoWayArbitrage()
                .withProfitCalculation(profitPercent, profitUsd)
                .withExpiration(5) // 5 minutes default
                .calculatePriority()
                .build();
    }

    @Override
    public String toString() {
        return String.format("ArbitrageOpportunity{id=%d, %s, %s, profit=%.2f%%, status=%s, remaining=%ds}",
                id, getDisplayName(), tradingPath,
                profitPercentage != null ? profitPercentage.doubleValue() : 0.0,
                status, getRemainingSeconds());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ArbitrageOpportunity that)) return false;
        return pool1Address != null && pool1Address.equals(that.pool1Address) &&
                pool2Address != null && pool2Address.equals(that.pool2Address);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(pool1Address, pool2Address);
    }
}