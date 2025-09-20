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

/**
 * TradePair entity representing aggregated data for token pairs across multiple pools.
 * Used for arbitrage analysis and opportunity detection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("trade_pairs")
public class TradePair {

    @Id
    private Long id;

    /**
     * First token mint address
     */
    @Column("token_a")
    private String tokenA;

    /**
     * Second token mint address
     */
    @Column("token_b")
    private String tokenB;

    /**
     * Number of active pools for this pair
     */
    @Column("pool_count")
    private Integer poolCount;

    /**
     * Average TVL across all pools for this pair
     */
    @Column("avg_tvl")
    private Long avgTvl;

    /**
     * Best (lowest) fee rate available for this pair
     */
    @Column("best_fee_rate")
    private BigDecimal bestFeeRate;

    /**
     * Last observed price (tokenB per tokenA)
     */
    @Column("last_price")
    private BigDecimal lastPrice;

    /**
     * Last time this pair was updated
     */
    @Column("last_updated")
    private LocalDateTime lastUpdated;

    /**
     * Get token symbols for display
     */
    public String getTokenSymbols() {
        return getTokenSymbol(tokenA) + "/" + getTokenSymbol(tokenB);
    }

    /**
     * Get token symbol from mint address
     */
    private String getTokenSymbol(String mintAddress) {
        if (mintAddress == null) return "UNKNOWN";

        return switch (mintAddress) {
            case "So11111111111111111111111111111111111111112" -> "SOL";
            case "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" -> "USDC";
            case "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" -> "USDT";
            case "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So" -> "mSOL";
            case "7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs" -> "ETH";
            case "9n4nbM75f5Ui33ZbPYXn59EwSgE8CGsHtAeTH5YFeJ9E" -> "BTC";
            default -> mintAddress.substring(0, 4) + "...";
        };
    }

    /**
     * Format average TVL for display
     */
    public String getFormattedAvgTvl() {
        if (avgTvl == null) return "0";

        if (avgTvl >= 1_000_000_000) {
            return String.format("%.1fB", avgTvl / 1_000_000_000.0);
        } else if (avgTvl >= 1_000_000) {
            return String.format("%.1fM", avgTvl / 1_000_000.0);
        } else if (avgTvl >= 1_000) {
            return String.format("%.1fK", avgTvl / 1_000.0);
        }
        return avgTvl.toString();
    }

    /**
     * Format fee rate for display
     */
    public String getFormattedFeeRate() {
        if (bestFeeRate == null) return "0.00%";
        return String.format("%.3f%%", bestFeeRate.multiply(BigDecimal.valueOf(100)));
    }

    /**
     * Format price for display
     */
    public String getFormattedPrice() {
        if (lastPrice == null) return "N/A";

        // For very small prices, show more decimals
        if (lastPrice.compareTo(BigDecimal.valueOf(0.001)) < 0) {
            return String.format("%.8f", lastPrice);
        } else if (lastPrice.compareTo(BigDecimal.valueOf(1)) < 0) {
            return String.format("%.6f", lastPrice);
        } else {
            return String.format("%.4f", lastPrice);
        }
    }

    /**
     * Check if this pair has sufficient liquidity for arbitrage
     */
    public boolean hasSufficientLiquidity(long minTvl) {
        return avgTvl != null && avgTvl >= minTvl &&
                poolCount != null && poolCount >= 2;
    }

    /**
     * Check if this pair has multiple pools for potential arbitrage
     */
    public boolean hasMultiplePools() {
        return poolCount != null && poolCount > 1;
    }

    /**
     * Get liquidity score (combination of TVL and pool count)
     */
    public double getLiquidityScore() {
        if (avgTvl == null || poolCount == null) return 0.0;

        // Score = log(TVL) * pool_count * price_stability_factor
        double tvlScore = Math.log(avgTvl.doubleValue() + 1);
        double poolScore = Math.sqrt(poolCount.doubleValue());

        return tvlScore * poolScore;
    }

    /**
     * Check if this pair is suitable for triangle arbitrage
     */
    public boolean isSuitableForTriangleArbitrage() {
        return hasMultiplePools() &&
                hasSufficientLiquidity(100_000) &&
                lastPrice != null &&
                lastPrice.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Get display name for this trading pair
     */
    public String getDisplayName() {
        return String.format("%s (%d pools, avg TVL: %s)",
                getTokenSymbols(),
                poolCount != null ? poolCount : 0,
                getFormattedAvgTvl());
    }

    /**
     * Check if this pair contains the specified token
     */
    public boolean containsToken(String tokenMint) {
        return tokenMint.equals(tokenA) || tokenMint.equals(tokenB);
    }

    /**
     * Get the other token in the pair
     */
    public String getOtherToken(String tokenMint) {
        if (tokenMint.equals(tokenA)) {
            return tokenB;
        } else if (tokenMint.equals(tokenB)) {
            return tokenA;
        }
        return null;
    }

    /**
     * Get pair identifier for deduplication
     */
    public String getPairId() {
        // Ensure consistent ordering for pair identification
        if (tokenA.compareTo(tokenB) < 0) {
            return tokenA + "-" + tokenB;
        } else {
            return tokenB + "-" + tokenA;
        }
    }

    /**
     * Check if data is fresh (updated recently)
     */
    public boolean isFresh(int maxAgeMinutes) {
        if (lastUpdated == null) return false;

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(maxAgeMinutes);
        return lastUpdated.isAfter(cutoff);
    }

    /**
     * Age of the data in minutes
     */
    public long getAgeMinutes() {
        if (lastUpdated == null) return Long.MAX_VALUE;

        return java.time.Duration.between(lastUpdated, LocalDateTime.now()).toMinutes();
    }

    @Override
    public String toString() {
        return String.format("TradePair{%s, pools=%d, avgTvl=%s, price=%s, fee=%s}",
                getTokenSymbols(),
                poolCount != null ? poolCount : 0,
                getFormattedAvgTvl(),
                getFormattedPrice(),
                getFormattedFeeRate());
    }
}