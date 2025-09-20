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
 * Pool entity representing a liquidity pool from various DEXs on Solana.
 * Stores pools with TVL >= 40k for arbitrage analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("pools")
public class Pool {

    @Id
    private Long id;

    /**
     * Pool account address (44 characters)
     */
    @Column("address")
    private String address;

    /**
     * First token account address in the pool
     */
    @Column("token_a")
    private String tokenA;

    /**
     * Second token account address in the pool
     */
    @Column("token_b")
    private String tokenB;

    /**
     * First token mint address
     */
    @Column("token_a_mint")
    private String tokenAMint;

    /**
     * Second token mint address
     */
    @Column("token_b_mint")
    private String tokenBMint;

    /**
     * Total Value Locked in smallest token units (lamports/wei equivalent)
     */
    @Column("tvl")
    private Long tvl;

    /**
     * DEX name (RAYDIUM, ORCA, etc.)
     */
    @Column("dex_name")
    private String dexName;

    /**
     * Pool trading fee rate (e.g., 0.0025 for 0.25%)
     */
    @Column("fee_rate")
    private BigDecimal feeRate;

    /**
     * Last time this pool was updated
     */
    @Column("last_updated")
    private LocalDateTime lastUpdated;

    /**
     * Whether this pool is currently active and being monitored
     */
    @Column("is_active")
    private Boolean isActive;

    /**
     * Check if this pool meets minimum TVL requirements
     */
    public boolean meetsMinTvl(long minTvl) {
        return tvl != null && tvl >= minTvl;
    }

    /**
     * Get display name for the pool
     */
    public String getDisplayName() {
        return String.format("%s %s/%s (TVL: %s)",
                dexName,
                getTokenSymbol(tokenAMint),
                getTokenSymbol(tokenBMint),
                formatTvl());
    }

    /**
     * Format TVL for display
     */
    public String formatTvl() {
        if (tvl == null) return "0";

        if (tvl >= 1_000_000_000) {
            return String.format("%.1fB", tvl / 1_000_000_000.0);
        } else if (tvl >= 1_000_000) {
            return String.format("%.1fM", tvl / 1_000_000.0);
        } else if (tvl >= 1_000) {
            return String.format("%.1fK", tvl / 1_000.0);
        }
        return tvl.toString();
    }

    /**
     * Get token symbol from mint address (simplified mapping)
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
     * Check if this pool contains the specified token
     */
    public boolean containsToken(String tokenMint) {
        return tokenMint.equals(tokenAMint) || tokenMint.equals(tokenBMint);
    }

    /**
     * Get the other token in the pair
     */
    public String getOtherToken(String tokenMint) {
        if (tokenMint.equals(tokenAMint)) {
            return tokenBMint;
        } else if (tokenMint.equals(tokenBMint)) {
            return tokenAMint;
        }
        return null;
    }

    /**
     * Check if this pool is ready for arbitrage analysis
     */
    public boolean isReadyForArbitrage() {
        return isActive != null && isActive &&
                tvl != null && tvl >= 40_000 &&
                address != null && !address.isEmpty() &&
                tokenAMint != null && !tokenAMint.isEmpty() &&
                tokenBMint != null && !tokenBMint.isEmpty() &&
                dexName != null && !dexName.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("Pool{%s, %s, tvl=%s, dex=%s}",
                address != null ? address.substring(0, 8) + "..." : "null",
                getDisplayName(),
                formatTvl(),
                dexName);
    }
}