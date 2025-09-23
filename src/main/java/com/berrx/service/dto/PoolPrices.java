package com.berrx.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;

/**
 * DTO для хранения актуальных данных цен пула из Solana RPC.
 * Содержит цены, балансы токенов и курс обмена.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoolPrices {

    /**
     * Цена первого токена (tokenA) в USD или SOL
     */
    private BigDecimal priceA;

    /**
     * Цена второго токена (tokenB) в USD или SOL
     */
    private BigDecimal priceB;

    /**
     * Текущий баланс первого токена в пуле
     */
    private BigDecimal balanceA;

    /**
     * Текущий баланс второго токена в пуле
     */
    private BigDecimal balanceB;

    /**
     * Курс обмена: сколько tokenB за 1 tokenA
     */
    private BigDecimal exchangeRate;

    /**
     * Обратный курс: сколько tokenA за 1 tokenB
     */
    private BigDecimal reverseExchangeRate;

    /**
     * Время получения данных
     */
    private LocalDateTime timestamp;

    /**
     * Источник данных (RPC endpoint)
     */
    private String source;

    /**
     * Номер слота в блокчейне Solana
     */
    private Long slot;

    /**
     * Качество данных (0-100, где 100 = свежие данные)
     */
    private Integer qualityScore;

    // =================== ВЫЧИСЛЯЕМЫЕ МЕТОДЫ ===================

    /**
     * Рассчитать курс обмена на основе балансов
     */
    public BigDecimal calculateExchangeRate() {
        if (balanceA == null || balanceB == null ||
                balanceA.equals(BigDecimal.ZERO) || balanceB.equals(BigDecimal.ZERO)) {
            return null;
        }

        try {
            // exchangeRate = balanceB / balanceA (сколько B за 1 A)
            return balanceB.divide(balanceA, MathContext.DECIMAL128);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * Рассчитать обратный курс обмена
     */
    public BigDecimal calculateReverseExchangeRate() {
        if (balanceA == null || balanceB == null ||
                balanceA.equals(BigDecimal.ZERO) || balanceB.equals(BigDecimal.ZERO)) {
            return null;
        }

        try {
            // reverseExchangeRate = balanceA / balanceB (сколько A за 1 B)
            return balanceA.divide(balanceB, MathContext.DECIMAL128);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * Автоматически рассчитать курсы на основе балансов
     */
    public void calculateRates() {
        this.exchangeRate = calculateExchangeRate();
        this.reverseExchangeRate = calculateReverseExchangeRate();
    }

    /**
     * Проверить валидность данных
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
     * Возраст данных в секундах
     */
    public long getAgeInSeconds() {
        if (timestamp == null) {
            return Long.MAX_VALUE;
        }

        return java.time.Duration.between(timestamp, LocalDateTime.now()).getSeconds();
    }

    /**
     * Проверить свежесть данных (не старше указанных секунд)
     */
    public boolean isFresh(int maxAgeSeconds) {
        return getAgeInSeconds() <= maxAgeSeconds;
    }

    /**
     * Рассчитать качество данных на основе возраста
     */
    public void calculateQualityScore() {
        long ageSeconds = getAgeInSeconds();

        if (ageSeconds <= 30) {
            qualityScore = 100; // Отличное качество
        } else if (ageSeconds <= 60) {
            qualityScore = 90;  // Очень хорошее
        } else if (ageSeconds <= 300) {
            qualityScore = 70;  // Хорошее (5 минут)
        } else if (ageSeconds <= 600) {
            qualityScore = 50;  // Средне (10 минут)
        } else if (ageSeconds <= 1800) {
            qualityScore = 30;  // Плохое (30 минут)
        } else {
            qualityScore = 0;   // Очень плохое
        }
    }

    // =================== УТИЛИТЫ ===================

    /**
     * Получить TVL пула в USD (если цены в USD)
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
     * Симулировать своп A -> B
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
     * Симулировать своп B -> A
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
     * Форматированное отображение цен
     */
    public String getFormattedPrices() {
        if (priceA == null || priceB == null) {
            return "N/A";
        }

        return String.format("A: $%.6f, B: $%.6f", priceA.doubleValue(), priceB.doubleValue());
    }

    /**
     * Форматированное отображение курса
     */
    public String getFormattedExchangeRate() {
        if (exchangeRate == null) {
            return "N/A";
        }

        return String.format("1A = %.6fB", exchangeRate.doubleValue());
    }

    // =================== BUILDER ДОПОЛНЕНИЯ ===================

    /**
     * Builder с дополнительными утилитами
     */
    public static class PoolPricesBuilder {

        public PoolPricesBuilder withCurrentTimestamp() {
            this.timestamp = LocalDateTime.now();
            return this;
        }

        public PoolPricesBuilder withCalculatedRates() {
            // Рассчитаем курсы после build()
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

        public PoolPrices buildWithCalculations() {
            PoolPrices prices = this.build();
            prices.calculateRates();
            prices.calculateQualityScore();
            return prices;
        }
    }

    // =================== СТАТИЧЕСКИЕ МЕТОДЫ ===================

    /**
     * Создать пустой объект с текущим временем
     */
    public static PoolPrices empty() {
        return PoolPrices.builder()
                .timestamp(LocalDateTime.now())
                .qualityScore(0)
                .build();
    }

    /**
     * Создать из балансов пула
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
     * Проверить возможность арбитража между двумя ценами
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

            // Рассчитываем разницу в процентах
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
}