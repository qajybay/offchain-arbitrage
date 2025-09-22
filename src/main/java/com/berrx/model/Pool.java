package com.berrx.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Pool entity представляет пул ликвидности из различных DEX Solana.
 * Содержит метаданные из DEX Screener и актуальные цены из Solana RPC.
 *
 * Гибридный подход:
 * - Метаданные (символы, названия, базовый TVL) из DEX Screener API
 * - Актуальные цены и балансы из Solana RPC (@Transient поля)
 * - Кеширование в PostgreSQL для быстрого доступа
 */
@Entity
@Table(name = "pools",
        indexes = {
                @Index(name = "idx_pools_active_tvl", columnList = "isActive,tvlUsd"),
                @Index(name = "idx_pools_dex_tokens", columnList = "dexName,tokenAMint,tokenBMint"),
                @Index(name = "idx_pools_token_mints", columnList = "tokenAMint,tokenBMint"),
                @Index(name = "idx_pools_updated", columnList = "lastUpdated"),
                @Index(name = "idx_pools_symbols", columnList = "tokenASymbol,tokenBSymbol")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Pool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Уникальный адрес пула в сети Solana (43-44 символа, Base58)
     */
    @Column(name = "address", unique = true, length = 44, nullable = false)
    private String address;

    // =================== ТОКЕН A ===================

    /**
     * Mint адрес первого токена в пуле
     */
    @Column(name = "token_a_mint", length = 44, nullable = false)
    private String tokenAMint;

    /**
     * Символ первого токена (например, "SOL", "USDC")
     * Получается из DEX Screener API
     */
    @Column(name = "token_a_symbol", length = 20)
    private String tokenASymbol;

    /**
     * Полное название первого токена
     * Получается из DEX Screener API
     */
    @Column(name = "token_a_name", length = 100)
    private String tokenAName;

    // =================== ТОКЕН B ===================

    /**
     * Mint адрес второго токена в пуле
     */
    @Column(name = "token_b_mint", length = 44, nullable = false)
    private String tokenBMint;

    /**
     * Символ второго токена
     * Получается из DEX Screener API
     */
    @Column(name = "token_b_symbol", length = 20)
    private String tokenBSymbol;

    /**
     * Полное название второго токена
     * Получается из DEX Screener API
     */
    @Column(name = "token_b_name", length = 100)
    private String tokenBName;

    // =================== ЛИКВИДНОСТЬ И DEX ===================

    /**
     * Total Value Locked в USD
     * Получается из DEX Screener API, периодически обновляется
     */
    @Column(name = "tvl_usd")
    private Double tvlUsd;

    /**
     * Название DEX (raydium, orca, meteora, jupiter)
     */
    @Column(name = "dex_name", length = 20, nullable = false)
    private String dexName;

    /**
     * Комиссия пула (например, 0.0025 = 0.25%)
     */
    @Column(name = "fee_rate")
    private Double feeRate;

    // =================== ВРЕМЕННЫЕ МЕТКИ ===================

    /**
     * Время последнего обновления данных пула
     */
    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    /**
     * Активен ли пул (используется для мягкого удаления)
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    /**
     * Источник данных (DEX_SCREENER, SOLANA_RPC, MANUAL)
     */
    @Column(name = "source", length = 20)
    private String source;

    // =================== АКТУАЛЬНЫЕ ДАННЫЕ (TRANSIENT) ===================
    // Эти поля НЕ сохраняются в БД, обновляются из Solana RPC в реальном времени

    /**
     * Текущая цена первого токена (из Solana RPC)
     */
    @Transient
    private Double currentPriceA;

    /**
     * Текущая цена второго токена (из Solana RPC)
     */
    @Transient
    private Double currentPriceB;

    /**
     * Время последнего обновления цен
     */
    @Transient
    private LocalDateTime priceUpdatedAt;

    /**
     * Текущий баланс первого токена в пуле
     */
    @Transient
    private Double tokenABalance;

    /**
     * Текущий баланс второго токена в пуле
     */
    @Transient
    private Double tokenBBalance;

    /**
     * Текущий курс (tokenB за tokenA)
     */
    @Transient
    private Double exchangeRate;

    // =================== ОСНОВНЫЕ МЕТОДЫ ===================

    /**
     * Отображаемое имя пула для логов и UI
     */
    public String getDisplayName() {
        String symbolA = tokenASymbol != null ? tokenASymbol : "???";
        String symbolB = tokenBSymbol != null ? tokenBSymbol : "???";
        return String.format("%s %s/%s (TVL: %s)",
                dexName != null ? dexName.toUpperCase() : "UNKNOWN",
                symbolA, symbolB, getFormattedTvl());
    }

    /**
     * Короткое имя пары токенов
     */
    public String getSymbolPair() {
        String symbolA = tokenASymbol != null ? tokenASymbol : "???";
        String symbolB = tokenBSymbol != null ? tokenBSymbol : "???";
        return symbolA + "/" + symbolB;
    }

    /**
     * Форматированный TVL для отображения
     */
    public String getFormattedTvl() {
        if (tvlUsd == null || tvlUsd <= 0) return "$0";

        if (tvlUsd >= 1_000_000_000) {
            return String.format("$%.1fB", tvlUsd / 1_000_000_000);
        } else if (tvlUsd >= 1_000_000) {
            return String.format("$%.1fM", tvlUsd / 1_000_000);
        } else if (tvlUsd >= 1_000) {
            return String.format("$%.1fK", tvlUsd / 1_000);
        }
        return String.format("$%.0f", tvlUsd);
    }

    // =================== ПРОВЕРКИ ВАЛИДНОСТИ ===================

    /**
     * Проверка наличия всех необходимых метаданных
     */
    public boolean hasValidMetadata() {
        return tokenAMint != null && tokenBMint != null &&
                tokenASymbol != null && tokenBSymbol != null &&
                tvlUsd != null && tvlUsd > 0 &&
                dexName != null && !dexName.isEmpty();
    }

    /**
     * Проверка наличия актуальных цен (обновлены недавно)
     */
    public boolean hasCurrentPrices() {
        return currentPriceA != null && currentPriceB != null &&
                priceUpdatedAt != null &&
                priceUpdatedAt.isAfter(LocalDateTime.now().minusMinutes(5));
    }

    /**
     * Проверка пригодности пула для арбитража
     */
    public boolean isSuitableForArbitrage(double minTvlUsd) {
        return isActive != null && isActive &&
                tvlUsd != null && tvlUsd >= minTvlUsd &&
                hasValidMetadata() &&
                !isLikelyScam();
    }

    /**
     * Простая проверка на скам токены
     */
    public boolean isLikelyScam() {
        if (tokenASymbol == null || tokenBSymbol == null) return false;

        // Проверяем подозрительные паттерны
        return tokenASymbol.length() > 15 ||
                tokenBSymbol.length() > 15 ||
                tokenASymbol.toLowerCase().contains("test") ||
                tokenBSymbol.toLowerCase().contains("test") ||
                (tvlUsd != null && tvlUsd > 1_000_000_000); // > $1B подозрительно
    }

    // =================== РАБОТА С ТОКЕНАМИ ===================

    /**
     * Уникальный идентификатор торговой пары (сортированный)
     */
    public String getTokenPairId() {
        if (tokenAMint == null || tokenBMint == null) return null;

        // Сортируем адреса для консистентности
        if (tokenAMint.compareTo(tokenBMint) < 0) {
            return tokenAMint + "-" + tokenBMint;
        } else {
            return tokenBMint + "-" + tokenAMint;
        }
    }

    /**
     * Проверка содержания определенного токена в пуле
     */
    public boolean containsToken(String tokenMint) {
        return tokenMint != null &&
                (tokenMint.equals(tokenAMint) || tokenMint.equals(tokenBMint));
    }

    /**
     * Получить адрес другого токена в паре
     */
    public String getOtherToken(String tokenMint) {
        if (tokenMint == null) return null;

        if (tokenMint.equals(tokenAMint)) {
            return tokenBMint;
        } else if (tokenMint.equals(tokenBMint)) {
            return tokenAMint;
        }
        return null;
    }

    /**
     * Получить символ другого токена в паре
     */
    public String getOtherTokenSymbol(String tokenMint) {
        if (tokenMint == null) return null;

        if (tokenMint.equals(tokenAMint)) {
            return tokenBSymbol;
        } else if (tokenMint.equals(tokenBMint)) {
            return tokenASymbol;
        }
        return null;
    }

    // =================== ВРЕМЕННЫЕ МЕТОДЫ ===================

    /**
     * Возраст данных в минутах
     */
    public long getDataAgeMinutes() {
        if (lastUpdated == null) return Long.MAX_VALUE;
        return java.time.Duration.between(lastUpdated, LocalDateTime.now()).toMinutes();
    }

    /**
     * Проверка устаревания данных
     */
    public boolean isStale(int maxAgeMinutes) {
        return getDataAgeMinutes() > maxAgeMinutes;
    }

    /**
     * Свежесть данных в процентах (100% = только что обновлено)
     */
    public double getFreshnessPercent() {
        long ageMinutes = getDataAgeMinutes();
        if (ageMinutes == 0) return 100.0;
        if (ageMinutes >= 60) return 0.0;

        return Math.max(0, 100.0 - (ageMinutes * 100.0 / 60.0));
    }

    // =================== КАТЕГОРИЗАЦИЯ ПУЛОВ ===================

    /**
     * Является ли основной парой (содержит SOL, USDC, USDT)
     */
    public boolean isMainPair() {
        return containsStablecoin() || containsSol();
    }

    /**
     * Содержит ли пул SOL
     */
    public boolean containsSol() {
        String solMint = "So11111111111111111111111111111111111111112";
        return containsToken(solMint);
    }

    /**
     * Содержит ли пул стейблкоин
     */
    public boolean containsStablecoin() {
        String usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
        String usdtMint = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
        return containsToken(usdcMint) || containsToken(usdtMint);
    }

    /**
     * Является ли стейблкоин парой (USDC/USDT)
     */
    public boolean isStablePair() {
        String usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
        String usdtMint = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
        return containsToken(usdcMint) && containsToken(usdtMint);
    }

    // =================== КАЧЕСТВО И РЕЙТИНГ ===================

    /**
     * Рассчитать качество пула для арбитража (0-100)
     */
    public double getQualityScore() {
        double score = 0.0;

        // TVL влияет на качество (40%)
        if (tvlUsd != null && tvlUsd > 0) {
            score += Math.min(40, Math.log10(tvlUsd) * 5);
        }

        // Основные пары получают бонус (20%)
        if (isMainPair()) {
            score += 20;
        }

        // Свежесть данных (20%)
        score += getFreshnessPercent() * 0.2;

        // Наличие метаданных (10%)
        if (hasValidMetadata()) {
            score += 10;
        }

        // Стабильность DEX (10%)
        if (dexName != null) {
            switch (dexName.toLowerCase()) {
                case "raydium", "orca" -> score += 10;
                case "meteora", "jupiter" -> score += 8;
                default -> score += 5;
            }
        }

        return Math.min(100, score);
    }

    /**
     * Приоритет пула для арбитража (higher = better)
     */
    public int getArbitragePriority() {
        int priority = 0;

        // TVL приоритет
        if (tvlUsd != null) {
            if (tvlUsd >= 10_000_000) priority += 100;      // > $10M
            else if (tvlUsd >= 1_000_000) priority += 80;   // > $1M
            else if (tvlUsd >= 100_000) priority += 60;     // > $100K
            else if (tvlUsd >= 40_000) priority += 40;      // > $40K
        }

        // Тип пары
        if (isStablePair()) priority += 50;        // Стейблкоин пары
        else if (containsSol()) priority += 30;    // SOL пары
        else if (containsStablecoin()) priority += 20; // Стейблкоин пары

        // DEX приоритет
        if (dexName != null) {
            switch (dexName.toLowerCase()) {
                case "raydium" -> priority += 20;
                case "orca" -> priority += 18;
                case "meteora" -> priority += 15;
                case "jupiter" -> priority += 12;
            }
        }

        // Свежесть данных
        long ageMinutes = getDataAgeMinutes();
        if (ageMinutes < 5) priority += 10;
        else if (ageMinutes < 30) priority += 5;

        return priority;
    }

    // =================== СЛУЖЕБНЫЕ МЕТОДЫ ===================

    /**
     * Установить комиссию по умолчанию если не задана
     */
    public void setDefaultFeeRateIfNull() {
        if (feeRate == null && dexName != null) {
            feeRate = getDefaultFeeRate(dexName);
        }
    }

    /**
     * Получить стандартную комиссию для DEX
     */
    public static Double getDefaultFeeRate(String dexName) {
        if (dexName == null) return 0.003; // 0.3% по умолчанию

        return switch (dexName.toLowerCase()) {
            case "raydium" -> 0.0025;   // 0.25%
            case "orca" -> 0.003;       // 0.3%
            case "meteora" -> 0.003;    // 0.3%
            case "jupiter" -> 0.0025;   // 0.25%
            default -> 0.003;           // 0.3%
        };
    }

    /**
     * Обновить из другого пула (для merge операций)
     */
    public void updateFrom(Pool other) {
        if (other == null) return;

        // Обновляем TVL и время
        if (other.getTvlUsd() != null) {
            this.tvlUsd = other.getTvlUsd();
        }
        this.lastUpdated = LocalDateTime.now();
        this.isActive = true;

        // Обновляем метаданные если они были пустые
        if (this.tokenASymbol == null && other.getTokenASymbol() != null) {
            this.tokenASymbol = other.getTokenASymbol();
        }
        if (this.tokenBSymbol == null && other.getTokenBSymbol() != null) {
            this.tokenBSymbol = other.getTokenBSymbol();
        }
        if (this.tokenAName == null && other.getTokenAName() != null) {
            this.tokenAName = other.getTokenAName();
        }
        if (this.tokenBName == null && other.getTokenBName() != null) {
            this.tokenBName = other.getTokenBName();
        }
        if (this.feeRate == null && other.getFeeRate() != null) {
            this.feeRate = other.getFeeRate();
        }
    }

    // =================== ПЕРЕОПРЕДЕЛЕНИЕ МЕТОДОВ ===================

    @Override
    public String toString() {
        return String.format("Pool{%s, %s, %s, active=%s, age=%dm, quality=%.1f}",
                address != null ? address.substring(0, 8) + "..." : "null",
                getSymbolPair(),
                getFormattedTvl(),
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

    // =================== BUILDER ДОПОЛНЕНИЯ ===================

    /**
     * Builder с дополнительными методами
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
    }
}