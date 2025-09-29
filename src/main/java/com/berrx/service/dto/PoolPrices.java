package com.berrx.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;

/**
 * DTO for storing current pool price data from Solana RPC.
 * Contains prices, token balances, and exchange rates.
 *
 * UPDATED: Added missing fields required by other services
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoolPrices {

    /**
     * Pool address (ADDED - required by SolanaPriceService)
     */
    private String poolAddress;

    /**
     * Whether this data was verified via RPC (ADDED - required by ArbitrageDetectorService)
     */
    private boolean verified;

    /**
     * Price of first token (tokenA) in USD or SOL
     */
    private BigDecimal priceA;

    /**
     * Price of second token (tokenB) in USD or SOL
     */
    private BigDecimal priceB;

    /**
     * Current balance of first token in pool
     */
    private BigDecimal balanceA;

    /**
     * Current balance of second token in pool
     */
    private BigDecimal balanceB;

    /**
     * Exchange rate: how much tokenB for 1 tokenA
     */
    private BigDecimal exchangeRate;

    /**
     * Reverse exchange rate: how much tokenA for 1 tokenB
     */
    private BigDecimal reverseExchangeRate;

    /**
     * Time when data was fetched
     */
    private LocalDateTime timestamp;

    /**
     * Data source (RPC endpoint)
     */
    private String source;

    /**
     * Solana blockchain slot number
     */
    private Long slot;

    /**
     * Data quality score (0-100, where 100 = fresh data)
     */
    private Integer qualityScore;

    // =================== CALCULATED METHODS ===================

    /**
     * Calculate exchange rate based on balances
     */
    public BigDecimal calculateExchangeRate() {
        if (balanceA == null || balanceB == null ||
                balanceA.equals(BigDecimal.ZERO) || balanceB.equals(BigDecimal.ZERO)) {
            return null;
        }

        try {
            // exchangeRate = balanceB / balanceA (how much B for 1 A)
            return balanceB.divide(balanceA, MathContext.DECIMAL128);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * Calculate reverse exchange rate
     */
    public BigDecimal calculateReverseExchangeRate() {
        if (balanceA == null || balanceB == null ||
                balanceA.equals(BigDecimal.ZERO) || balanceB.equals(BigDecimal.ZERO)) {
            return null;
        }

        try {
            // reverseExchangeRate = balanceA / balanceB (how much A for 1 B)
            return balanceA.divide(balanceB, MathContext.DECIMAL128);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * Automatically calculate rates based on balances
     */
    public void calculateRates() {
        this.exchangeRate = calculateExchangeRate();
        this.reverseExchangeRate = calculateReverseExchangeRate();
    }

    /**
     * Check data validity
     */
    public boolean isValid() {
        return priceA != null && priceB != null &&
                balanceA != null && balanceB != null &&
                priceA.compareTo(BigDecimal.ZERO) > 0 &&
                priceB.compareTo(BigDecimal.ZERO) > 0 &&
                balanceA.compareTo(BigDecimal.ZERO) > 0 &&
                balanceB.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Data age in seconds
     */
    public long getAgeInSeconds() {
        if (timestamp == null) {
            return Long.MAX_VALUE;
        }

        return java.time.Duration.between(timestamp, LocalDateTime.now()).getSeconds();
    }

    /**
     * Check if data is fresh (not older than specified seconds)
     */
    public boolean isFresh(int maxAgeSeconds) {
        return getAgeInSeconds() <= maxAgeSeconds;
    }

    /**
     * Calculate data quality score based on age
     */
    public void calculateQualityScore() {
        long ageSeconds = getAgeInSeconds();

        if (ageSeconds <= 30) {
            qualityScore = 100; // Excellent quality
        } else if (ageSeconds <= 60) {
            qualityScore = 90;  // Very good
        } else if (ageSeconds <= 300) {
            qualityScore = 70;  // Good (5 minutes)
        } else if (ageSeconds <= 600) {
            qualityScore = 50;  // Average (10 minutes)
        } else if (ageSeconds <= 1800) {
            qualityScore = 30;  // Poor (30 minutes)
        } else {
            qualityScore = 0;   // Very poor
        }
    }

    // =================== UTILITIES ===================

    /**
     * Get pool TVL in USD (if prices are in USD)
     */
    public BigDecimal getTvlUsd() {
        if (priceA == null || priceB == null || balanceA == null || balanceB == null) {
            return null;
        }

        try {
            BigDecimal valueA = priceA.multiply(balanceA);
            BigDecimal valueB = priceB.multiply(balanceB);
            return valueA.add(valueB);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Simulate swap A -> B
     */
    public BigDecimal simulateSwapAtoB(BigDecimal amountA) {
        if (exchangeRate == null || amountA == null) {
            return null;
        }

        try {
            return amountA.multiply(exchangeRate);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Simulate swap B -> A
     */
    public BigDecimal simulateSwapBtoA(BigDecimal amountB) {
        if (reverseExchangeRate == null || amountB == null) {
            return null;
        }

        try {
            return amountB.multiply(reverseExchangeRate);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Formatted price display
     */
    public String getFormattedPrices() {
        if (priceA == null || priceB == null) {
            return "N/A";
        }

        return String.format("A: $%.6f, B: $%.6f", priceA.doubleValue(), priceB.doubleValue());
    }

    /**
     * Formatted exchange rate display
     */
    public String getFormattedExchangeRate() {
        if (exchangeRate == null) {
            return "N/A";
        }

        return String.format("1A = %.6fB", exchangeRate.doubleValue());
    }

    // =================== BUILDER ENHANCEMENTS ===================

    /**
     * Builder with additional utilities
     */
    public static class PoolPricesBuilder {

        public PoolPricesBuilder withCurrentTimestamp() {
            this.timestamp = LocalDateTime.now();
            return this;
        }

        public PoolPricesBuilder withCalculatedRates() {
            // Calculate rates after build()
            return this;
        }

        public PoolPricesBuilder fromBalances(BigDecimal balanceA, BigDecimal balanceB) {
            this.balanceA = balanceA;
            this.balanceB = balanceB;
            return this;
        }

        public PoolPricesBuilder fromSlot(long slot) {
            this.slot = slot;
            return this;
        }

        public PoolPricesBuilder fromSource(String source) {
            this.source = source;
            return this;
        }

        public PoolPricesBuilder asVerified() {
            this.verified = true;
            return this;
        }

        public PoolPricesBuilder forPool(String poolAddress) {
            this.poolAddress = poolAddress;
            return this;
        }

        public PoolPrices buildWithCalculations() {
            PoolPrices prices = this.build();
            prices.calculateRates();
            prices.calculateQualityScore();
            return prices;
        }
    }

    // =================== STATIC METHODS ===================

    /**
     * Create empty object with current time
     */
    public static PoolPrices empty() {
        return PoolPrices.builder()
                .timestamp(LocalDateTime.now())
                .qualityScore(0)
                .verified(false)
                .build();
    }

    /**
     * Create empty object for specific pool
     */
    public static PoolPrices emptyForPool(String poolAddress) {
        return PoolPrices.builder()
                .poolAddress(poolAddress)
                .timestamp(LocalDateTime.now())
                .qualityScore(0)
                .verified(false)
                .build();
    }

    /**
     * Create from pool balances
     */
    public static PoolPrices fromBalances(BigDecimal balanceA, BigDecimal balanceB, String source) {
        return PoolPrices.builder()
                .balanceA(balanceA)
                .balanceB(balanceB)
                .source(source)
                .withCurrentTimestamp()
                .buildWithCalculations();
    }

    /**
     * Create verified prices for specific pool
     */
    public static PoolPrices verifiedForPool(String poolAddress, BigDecimal priceA, BigDecimal priceB, String source) {
        return PoolPrices.builder()
                .poolAddress(poolAddress)
                .priceA(priceA)
                .priceB(priceB)
                .source(source)
                .verified(true)
                .withCurrentTimestamp()
                .buildWithCalculations();
    }

    /**
     * Check arbitrage opportunity between two prices
     */
    public static boolean hasArbitrageOpportunity(PoolPrices pool1, PoolPrices pool2,
                                                  double minProfitPercent) {
        if (!pool1.isValid() || !pool2.isValid() ||
                pool1.exchangeRate == null || pool2.exchangeRate == null) {
            return false;
        }

        try {
            BigDecimal rate1 = pool1.exchangeRate;
            BigDecimal rate2 = pool2.exchangeRate;

            // Calculate difference in percent
            BigDecimal diff = rate1.subtract(rate2).abs();
            BigDecimal maxRate = rate1.max(rate2);

            if (maxRate.equals(BigDecimal.ZERO)) {
                return false;
            }

            BigDecimal profitPercent = diff.divide(maxRate, MathContext.DECIMAL128)
                    .multiply(BigDecimal.valueOf(100));

            return profitPercent.doubleValue() >= minProfitPercent;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Compare two pool prices for arbitrage calculation
     */
    public static ArbitrageCalculation calculateArbitrage(PoolPrices pool1, PoolPrices pool2) {
        if (!pool1.isValid() || !pool2.isValid()) {
            return ArbitrageCalculation.noOpportunity();
        }

        try {
            BigDecimal rate1 = pool1.exchangeRate;
            BigDecimal rate2 = pool2.exchangeRate;

            if (rate1 == null || rate2 == null) {
                return ArbitrageCalculation.noOpportunity();
            }

            // Determine direction of arbitrage
            boolean buyFromPool1 = rate1.compareTo(rate2) < 0; // Pool1 is cheaper
            BigDecimal profitRate = buyFromPool1 ?
                    rate2.subtract(rate1).divide(rate1, MathContext.DECIMAL128) :
                    rate1.subtract(rate2).divide(rate2, MathContext.DECIMAL128);

            return ArbitrageCalculation.builder()
                    .hasOpportunity(profitRate.compareTo(BigDecimal.ZERO) > 0)
                    .profitPercent(profitRate.multiply(BigDecimal.valueOf(100)).doubleValue())
                    .buyFromPool1(buyFromPool1)
                    .pool1Rate(rate1)
                    .pool2Rate(rate2)
                    .build();

        } catch (Exception e) {
            return ArbitrageCalculation.noOpportunity();
        }
    }

    /**
     * Helper class for arbitrage calculations
     */
    @lombok.Builder
    @lombok.Data
    public static class ArbitrageCalculation {
        private boolean hasOpportunity;
        private double profitPercent;
        private boolean buyFromPool1;
        private BigDecimal pool1Rate;
        private BigDecimal pool2Rate;

        public static ArbitrageCalculation noOpportunity() {
            return ArbitrageCalculation.builder()
                    .hasOpportunity(false)
                    .profitPercent(0.0)
                    .build();
        }
    }
}