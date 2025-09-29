package com.berrx.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Pool entity represents liquidity pool from various Solana DEX.
 * Contains metadata from DEX Screener and current prices from Solana RPC.
 *
 * UPDATED: Changed @Transient price fields to persistent fields to support JPA queries
 *
 * Hybrid approach:
 * - Metadata (symbols, names, basic TVL) from DEX Screener API
 * - Current prices and balances from Solana RPC (now stored in DB for queries)
 * - PostgreSQL caching for fast access
 *
 * ARCHITECTURAL CHANGE:
 * Jupiter removed from all methods since it's an aggregator, not a DEX.
 * Supported DEX: raydium, orca, meteora
 */
@Entity
@Table(name = "pools", indexes = {
        @Index(name = "idx_pool_address", columnList = "address"),
        @Index(name = "idx_pool_active_tvl", columnList = "is_active, tvl_usd"),
        @Index(name = "idx_pool_dex", columnList = "dex_name"),
        @Index(name = "idx_pool_tokens", columnList = "token_a_mint, token_b_mint"),
        @Index(name = "idx_pool_last_updated", columnList = "last_updated"),
        @Index(name = "idx_pool_price_updated", columnList = "price_updated_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Pool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =================== POOL IDENTIFICATION ===================

    /**
     * Unique pool address on Solana blockchain
     */
    @Column(name = "address", unique = true, nullable = false, length = 44)
    private String address;

    // =================== TOKEN A METADATA ===================

    /**
     * First token mint address
     */
    @Column(name = "token_a_mint", nullable = false, length = 44)
    private String tokenAMint;

    /**
     * First token symbol (from DEX Screener)
     */
    @Column(name = "token_a_symbol", length = 20)
    private String tokenASymbol;

    /**
     * First token name (from DEX Screener)
     */
    @Column(name = "token_a_name", length = 100)
    private String tokenAName;

    // =================== TOKEN B METADATA ===================

    /**
     * Second token mint address
     */
    @Column(name = "token_b_mint", nullable = false, length = 44)
    private String tokenBMint;

    /**
     * Second token symbol (from DEX Screener)
     */
    @Column(name = "token_b_symbol", length = 20)
    private String tokenBSymbol;

    /**
     * Second token name (from DEX Screener)
     */
    @Column(name = "token_b_name", length = 100)
    private String tokenBName;

    // =================== POOL LIQUIDITY ===================

    /**
     * Total Value Locked in USD (from DEX Screener)
     */
    @Column(name = "tvl_usd")
    private Double tvlUsd;

    /**
     * DEX name (raydium, orca, meteora)
     * Jupiter excluded - it's an aggregator
     */
    @Column(name = "dex_name", length = 20, nullable = false)
    private String dexName;

    /**
     * Pool fee rate (e.g., 0.0025 = 0.25%)
     */
    @Column(name = "fee_rate")
    private Double feeRate;

    // =================== TIMESTAMPS ===================

    /**
     * Last update time for pool metadata
     */
    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    /**
     * Active status (for soft deletion)
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    /**
     * Data source (DEX_SCREENER, SOLANA_RPC, MANUAL)
     */
    @Column(name = "source", length = 20)
    private String source;

    // =================== CURRENT PRICES (FIXED: NOW PERSISTENT) ===================
    // These fields are NOW stored in DB to support JPA queries for arbitrage detection

    /**
     * FIXED: Current price of first token (from Solana RPC) - NOW PERSISTENT
     */
    @Column(name = "current_price_a")
    private Double currentPriceA;

    /**
     * FIXED: Current price of second token (from Solana RPC) - NOW PERSISTENT
     */
    @Column(name = "current_price_b")
    private Double currentPriceB;

    /**
     * FIXED: Last price update time - NOW PERSISTENT
     */
    @Column(name = "price_updated_at")
    private LocalDateTime priceUpdatedAt;

    /**
     * Current balance of first token in pool (updated from RPC)
     */
    @Column(name = "token_a_balance")
    private Double tokenABalance;

    /**
     * Current balance of second token in pool (updated from RPC)
     */
    @Column(name = "token_b_balance")
    private Double tokenBBalance;

    /**
     * Current exchange rate (tokenB per tokenA)
     */
    @Column(name = "exchange_rate")
    private Double exchangeRate;

    // =================== CORE METHODS ===================

    /**
     * Display name for logs and UI
     */
    public String getDisplayName() {
        String symbolA = tokenASymbol != null ? tokenASymbol : "???";
        String symbolB = tokenBSymbol != null ? tokenBSymbol : "???";
        return String.format("%s %s/%s (TVL: %s)",
                dexName != null ? dexName.toUpperCase() : "UNKNOWN",
                symbolA, symbolB, getFormattedTvl());
    }

    /**
     * Short token pair name
     */
    public String getSymbolPair() {
        String symbolA = tokenASymbol != null ? tokenASymbol : "???";
        String symbolB = tokenBSymbol != null ? tokenBSymbol : "???";
        return symbolA + "/" + symbolB;
    }

    /**
     * Formatted TVL for display
     */
    public String getFormattedTvl() {
        if (tvlUsd == null) return "N/A";
        if (tvlUsd >= 1_000_000) {
            return String.format("$%.1fM", tvlUsd / 1_000_000);
        } else if (tvlUsd >= 1_000) {
            return String.format("$%.0fK", tvlUsd / 1_000);
        } else {
            return String.format("$%.0f", tvlUsd);
        }
    }

    // =================== VALIDATION METHODS ===================

    /**
     * Check if pool has valid metadata
     */
    public boolean hasValidMetadata() {
        return address != null && !address.isBlank() &&
                tokenAMint != null && !tokenAMint.isBlank() &&
                tokenBMint != null && !tokenBMint.isBlank() &&
                dexName != null && !dexName.isBlank() &&
                tvlUsd != null && tvlUsd > 0;
    }

    /**
     * FIXED: Check if pool has current prices (now works with persistent fields)
     */
    public boolean hasCurrentPrices() {
        return currentPriceA != null && currentPriceB != null &&
                currentPriceA > 0 && currentPriceB > 0 &&
                priceUpdatedAt != null &&
                priceUpdatedAt.isAfter(LocalDateTime.now().minusMinutes(30)); // 30 minutes max age
    }

    /**
     * Check if pool is suitable for arbitrage
     */
    public boolean isSuitableForArbitrage(double minTvlUsd) {
        return isActive != null && isActive &&
                tvlUsd != null && tvlUsd >= minTvlUsd &&
                hasValidMetadata() &&
                hasCurrentPrices() &&
                !isLikelyScam();
    }

    /**
     * Simple check for scam tokens
     */
    public boolean isLikelyScam() {
        if (tokenASymbol == null || tokenBSymbol == null) return false;

        // Check suspicious patterns
        return tokenASymbol.length() > 15 ||
                tokenBSymbol.length() > 15 ||
                tokenASymbol.toLowerCase().contains("test") ||
                tokenBSymbol.toLowerCase().contains("test") ||
                (tvlUsd != null && tvlUsd > 1_000_000_000); // > $1B suspicious
    }

    // =================== TOKEN METHODS ===================

    /**
     * Unique token pair ID (sorted for consistency)
     */
    public String getTokenPairId() {
        if (tokenAMint == null || tokenBMint == null) return null;

        // Sort addresses for consistency
        if (tokenAMint.compareTo(tokenBMint) < 0) {
            return tokenAMint + "-" + tokenBMint;
        } else {
            return tokenBMint + "-" + tokenAMint;
        }
    }

    /**
     * Check if pool contains specific token
     */
    public boolean containsToken(String tokenMint) {
        return tokenMint != null &&
                (tokenMint.equals(tokenAMint) || tokenMint.equals(tokenBMint));
    }

    /**
     * Get the other token in the pair
     */
    public String getOtherToken(String tokenMint) {
        if (tokenMint == null) return null;
        if (tokenMint.equals(tokenAMint)) return tokenBMint;
        if (tokenMint.equals(tokenBMint)) return tokenAMint;
        return null;
    }

    // =================== PRICE AND ARBITRAGE METHODS ===================

    /**
     * Get age of price data in minutes
     */
    public long getPriceAgeMinutes() {
        if (priceUpdatedAt == null) return Long.MAX_VALUE;
        return java.time.Duration.between(priceUpdatedAt, LocalDateTime.now()).toMinutes();
    }

    /**
     * Calculate price ratio (A/B)
     */
    public Double getPriceRatio() {
        if (currentPriceA == null || currentPriceB == null || currentPriceB == 0) {
            return null;
        }
        return currentPriceA / currentPriceB;
    }

    /**
     * Calculate reverse price ratio (B/A)
     */
    public Double getReversePriceRatio() {
        if (currentPriceA == null || currentPriceB == null || currentPriceA == 0) {
            return null;
        }
        return currentPriceB / currentPriceA;
    }

    /**
     * Check if prices are fresh (updated recently)
     */
    public boolean hasFreshPrices(int maxAgeMinutes) {
        return getPriceAgeMinutes() <= maxAgeMinutes;
    }

    // =================== UTILITY METHODS ===================

    /**
     * Data age in minutes
     */
    public long getDataAgeMinutes() {
        if (lastUpdated == null) return Long.MAX_VALUE;
        return java.time.Duration.between(lastUpdated, LocalDateTime.now()).toMinutes();
    }

    /**
     * Quality score based on data freshness
     */
    public int getQualityScore() {
        long ageMinutes = getDataAgeMinutes();
        long priceAgeMinutes = getPriceAgeMinutes();

        if (!hasValidMetadata()) return 0;

        int metadataScore = ageMinutes <= 60 ? 50 : (ageMinutes <= 180 ? 30 : 10);
        int priceScore = hasFreshPrices(5) ? 50 : (hasFreshPrices(15) ? 30 : 0);

        return metadataScore + priceScore;
    }

    /**
     * Default fee rate by DEX
     */
    public static Double getDefaultFeeRate(String dexName) {
        if (dexName == null) return null;
        return switch (dexName.toLowerCase()) {
            case "raydium" -> 0.0025; // 0.25%
            case "orca" -> 0.003;     // 0.3%
            case "meteora" -> 0.002;  // 0.2%
            default -> 0.003;         // Default 0.3%
        };
    }

    // =================== DISPLAY METHODS ===================

    /**
     * Comprehensive string representation for debugging
     */
    @Override
    public String toString() {
        return String.format("Pool{id=%d, addr=%s, pair=%s, tvl=%s, dex=%s, active=%s, age=%dm, quality=%d}",
                id,
                address != null ? address.substring(0, 8) + "..." : "null",
                getSymbolPair(),
                getFormattedTvl(),
                dexName,
                isActive,
                getDataAgeMinutes(),
                getQualityScore());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Pool pool)) return false;
        return address != null && address.equals(pool.address);
    }

    @Override
    public int hashCode() {
        return address != null ? address.hashCode() : 0;
    }

    // =================== BUILDER ENHANCEMENTS ===================

    /**
     * Builder with additional utilities
     */
    public static class PoolBuilder {

        public PoolBuilder withDefaultFeeRate() {
            if (this.dexName != null && this.feeRate == null) {
                this.feeRate = getDefaultFeeRate(this.dexName);
            }
            return this;
        }

        public PoolBuilder asActive() {
            this.isActive = true;
            this.lastUpdated = LocalDateTime.now();
            return this;
        }

        public PoolBuilder fromDexScreener() {
            this.source = "DEX_SCREENER";
            return this;
        }

        public PoolBuilder withCurrentPrices(Double priceA, Double priceB) {
            this.currentPriceA = priceA;
            this.currentPriceB = priceB;
            this.priceUpdatedAt = LocalDateTime.now();
            return this;
        }

        public PoolBuilder withFreshTimestamp() {
            this.lastUpdated = LocalDateTime.now();
            return this;
        }
    }
}