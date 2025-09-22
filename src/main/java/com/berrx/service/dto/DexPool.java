package com.berrx.service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DexPool {
    private String chainId;
    private String dexId;
    private String url;
    private String pairAddress;

    @JsonProperty("baseToken")
    private TokenInfo baseToken;

    @JsonProperty("quoteToken")
    private TokenInfo quoteToken;

    private String priceNative;
    private String priceUsd;

    @JsonProperty("txns")
    private TransactionStats transactions;

    @JsonProperty("volume")
    private VolumeStats volume;

    @JsonProperty("priceChange")
    private PriceChangeStats priceChange;

    @JsonProperty("liquidity")
    private LiquidityStats liquidity;

    private Long fdv; // Fully Diluted Valuation
    private Long marketCap;
    private String pairCreatedAt;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenInfo {
        private String address;
        private String name;
        private String symbol;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransactionStats {
        @JsonProperty("h24")
        private TransactionCount h24;

        @JsonProperty("h6")
        private TransactionCount h6;

        @JsonProperty("h1")
        private TransactionCount h1;

        @JsonProperty("m5")
        private TransactionCount m5;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class TransactionCount {
            private Integer buys;
            private Integer sells;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VolumeStats {
        @JsonProperty("h24")
        private Double h24;

        @JsonProperty("h6")
        private Double h6;

        @JsonProperty("h1")
        private Double h1;

        @JsonProperty("m5")
        private Double m5;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PriceChangeStats {
        @JsonProperty("h24")
        private Double h24;

        @JsonProperty("h6")
        private Double h6;

        @JsonProperty("h1")
        private Double h1;

        @JsonProperty("m5")
        private Double m5;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LiquidityStats {
        private Double usd;
        private Double base;
        private Double quote;
    }

    /**
     * Проверка валидности пула
     */
    public boolean isValid() {
        return pairAddress != null &&
                baseToken != null &&
                quoteToken != null &&
                baseToken.getAddress() != null &&
                quoteToken.getAddress() != null &&
                liquidity != null &&
                liquidity.getUsd() != null &&
                liquidity.getUsd() > 0;
    }

    /**
     * Получить отображаемое имя
     */
    public String getDisplayName() {
        String baseSymbol = baseToken != null && baseToken.getSymbol() != null
                ? baseToken.getSymbol() : "???";
        String quoteSymbol = quoteToken != null && quoteToken.getSymbol() != null
                ? quoteToken.getSymbol() : "???";

        return String.format("%s/%s (%s)", baseSymbol, quoteSymbol,
                dexId != null ? dexId.toUpperCase() : "UNKNOWN");
    }

    /**
     * Получить TVL в форматированном виде
     */
    public String getFormattedTvl() {
        if (liquidity == null || liquidity.getUsd() == null) {
            return "$0";
        }

        double usd = liquidity.getUsd();
        if (usd >= 1_000_000) {
            return String.format("$%.1fM", usd / 1_000_000);
        } else if (usd >= 1_000) {
            return String.format("$%.1fK", usd / 1_000);
        }
        return String.format("$%.0f", usd);
    }

    /**
     * Получить объем торгов за 24ч
     */
    public Double getVolume24h() {
        return volume != null ? volume.getH24() : null;
    }

    /**
     * Получить количество транзакций за 24ч
     */
    public Integer getTransactions24h() {
        if (transactions == null || transactions.getH24() == null) {
            return null;
        }

        Integer buys = transactions.getH24().getBuys();
        Integer sells = transactions.getH24().getSells();

        return (buys != null ? buys : 0) + (sells != null ? sells : 0);
    }

    /**
     * Изменение цены за 24ч в процентах
     */
    public Double getPriceChange24h() {
        return priceChange != null ? priceChange.getH24() : null;
    }

    /**
     * Проверка активности пула (есть ли торги)
     */
    public boolean isActive() {
        Integer txns = getTransactions24h();
        return txns != null && txns > 0;
    }

    /**
     * Оценка качества пула для арбитража
     */
    public double getQualityScore() {
        double score = 0.0;

        // TVL влияет на качество
        if (liquidity != null && liquidity.getUsd() != null) {
            score += Math.log10(liquidity.getUsd() + 1) * 10;
        }

        // Объем торгов
        Double volume24h = getVolume24h();
        if (volume24h != null && volume24h > 0) {
            score += Math.log10(volume24h + 1) * 5;
        }

        // Количество транзакций
        Integer txns24h = getTransactions24h();
        if (txns24h != null && txns24h > 0) {
            score += Math.log10(txns24h + 1) * 3;
        }

        // Бонус за стабильность цены (небольшая волатильность)
        Double priceChange = getPriceChange24h();
        if (priceChange != null) {
            double absChange = Math.abs(priceChange);
            if (absChange < 5) { // < 5% изменение
                score += 10;
            } else if (absChange < 20) { // < 20% изменение
                score += 5;
            }
        }

        return score;
    }

    @Override
    public String toString() {
        return String.format("DexPool{%s, %s, TVL: %s, Vol24h: %.0f}",
                getDisplayName(),
                pairAddress != null ? pairAddress.substring(0, 8) + "..." : "null",
                getFormattedTvl(),
                getVolume24h() != null ? getVolume24h() : 0.0);
    }
}