package com.berrx.config;

import lombok.extern.slf4j.Slf4j;
import org.p2p.solanaj.rpc.RpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Конфигурация Solana RPC клиентов для получения актуальных данных из блокчейна.
 * Поддерживает primary + fallback архитектуру для надежности.
 */
@Configuration
@ConditionalOnProperty(name = "modules.solana-rpc.enabled", havingValue = "true")
@Slf4j
public class SolanaConfig {

    @Value("${solana.rpc.primary.url:https://api.mainnet-beta.solana.com}")
    private String primaryRpcUrl;

    @Value("${solana.rpc.fallback.enabled:false}")
    private boolean fallbackEnabled;

    @Value("${solana.rpc.fallback.url:}")
    private String fallbackRpcUrl;

    @Value("${solana.rpc.fallback.api-key:}")
    private String fallbackApiKey;

    @Value("${solana.rpc.timeout-ms:10000}")
    private int timeoutMs;

    /**
     * Основной RPC клиент - бесплатный публичный endpoint
     */
    @Bean
    @Primary
    public RpcClient primaryRpcClient() {
        log.info("Initializing primary Solana RPC client: {}", primaryRpcUrl);

        try {
            RpcClient client = new RpcClient(primaryRpcUrl);

            // Проверяем подключение
            testConnection(client, "primary");

            return client;
        } catch (Exception e) {
            log.error("Failed to initialize primary RPC client: {}", e.getMessage());
            throw new RuntimeException("Primary RPC client initialization failed", e);
        }
    }

    /**
     * Fallback RPC клиент - Helius или другой premium провайдер
     */
    @Bean
    @ConditionalOnProperty(name = "solana.rpc.fallback.enabled", havingValue = "true")
    public RpcClient fallbackRpcClient() {
        if (!fallbackEnabled || fallbackRpcUrl == null || fallbackRpcUrl.isEmpty()) {
            log.warn("Fallback RPC not configured properly");
            return null;
        }

        String finalUrl = addApiKeyToUrl(fallbackRpcUrl, fallbackApiKey);
        log.info("Initializing fallback Solana RPC client: {}",
                fallbackRpcUrl.replaceAll("api-key=.*", "api-key=***"));

        try {
            RpcClient client = new RpcClient(finalUrl);

            // Проверяем подключение
            testConnection(client, "fallback");

            return client;
        } catch (Exception e) {
            log.error("Failed to initialize fallback RPC client: {}", e.getMessage());
            return null; // Не бросаем исключение - fallback опционален
        }
    }

    /**
     * Добавляет API ключ к URL если необходимо
     */
    private String addApiKeyToUrl(String url, String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return url;
        }

        // Для Helius API
        if (url.contains("helius-rpc.com") || url.contains("rpc.helius.xyz")) {
            return url.endsWith("/") ? url + "?api-key=" + apiKey : url + "/?api-key=" + apiKey;
        }

        // Для других провайдеров с параметрами
        if (url.contains("?")) {
            return url + "&api-key=" + apiKey;
        } else {
            return url + "?api-key=" + apiKey;
        }
    }

    /**
     * Тестирует подключение к RPC
     */
    private void testConnection(RpcClient client, String clientName) {
        try {
            // Простой вызов для проверки соединения
            long slot = client.getApi().getSlot();
            log.info("{} RPC client connected successfully, current slot: {}",
                    clientName, slot);
        } catch (Exception e) {
            log.warn("{} RPC client connection test failed: {}", clientName, e.getMessage());
            // Не бросаем исключение - клиент может работать даже если тест не прошел
        }
    }

    /**
     * Конфигурация параметров для RPC вызовов
     */
    @Bean
    public SolanaRpcConfig rpcConfig() {
        return SolanaRpcConfig.builder()
                .primaryRpcUrl(primaryRpcUrl)
                .fallbackRpcUrl(fallbackRpcUrl)
                .fallbackEnabled(fallbackEnabled)
                .timeoutMs(timeoutMs)
                .maxRetries(3)
                .retryDelayMs(20000)
                .build();
    }

    /**
     * Конфигурационные параметры для RPC клиентов
     */
    public static class SolanaRpcConfig {
        private final String primaryRpcUrl;
        private final String fallbackRpcUrl;
        private final boolean fallbackEnabled;
        private final int timeoutMs;
        private final int maxRetries;
        private final int retryDelayMs;

        private SolanaRpcConfig(String primaryRpcUrl, String fallbackRpcUrl,
                                boolean fallbackEnabled, int timeoutMs,
                                int maxRetries, int retryDelayMs) {
            this.primaryRpcUrl = primaryRpcUrl;
            this.fallbackRpcUrl = fallbackRpcUrl;
            this.fallbackEnabled = fallbackEnabled;
            this.timeoutMs = timeoutMs;
            this.maxRetries = maxRetries;
            this.retryDelayMs = retryDelayMs;
        }

        public static SolanaRpcConfigBuilder builder() {
            return new SolanaRpcConfigBuilder();
        }

        // Getters
        public String getPrimaryRpcUrl() { return primaryRpcUrl; }
        public String getFallbackRpcUrl() { return fallbackRpcUrl; }
        public boolean isFallbackEnabled() { return fallbackEnabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public int getMaxRetries() { return maxRetries; }
        public int getRetryDelayMs() { return retryDelayMs; }

        public static class SolanaRpcConfigBuilder {
            private String primaryRpcUrl;
            private String fallbackRpcUrl;
            private boolean fallbackEnabled;
            private int timeoutMs;
            private int maxRetries;
            private int retryDelayMs;

            public SolanaRpcConfigBuilder primaryRpcUrl(String primaryRpcUrl) {
                this.primaryRpcUrl = primaryRpcUrl;
                return this;
            }

            public SolanaRpcConfigBuilder fallbackRpcUrl(String fallbackRpcUrl) {
                this.fallbackRpcUrl = fallbackRpcUrl;
                return this;
            }

            public SolanaRpcConfigBuilder fallbackEnabled(boolean fallbackEnabled) {
                this.fallbackEnabled = fallbackEnabled;
                return this;
            }

            public SolanaRpcConfigBuilder timeoutMs(int timeoutMs) {
                this.timeoutMs = timeoutMs;
                return this;
            }

            public SolanaRpcConfigBuilder maxRetries(int maxRetries) {
                this.maxRetries = maxRetries;
                return this;
            }

            public SolanaRpcConfigBuilder retryDelayMs(int retryDelayMs) {
                this.retryDelayMs = retryDelayMs;
                return this;
            }

            public SolanaRpcConfig build() {
                return new SolanaRpcConfig(primaryRpcUrl, fallbackRpcUrl,
                        fallbackEnabled, timeoutMs, maxRetries, retryDelayMs);
            }
        }
    }
}