package com.berrx.service;

import com.berrx.model.Pool;
import com.berrx.repository.PoolRepository;
import com.berrx.service.dto.DexPool;
import com.berrx.service.dto.DexScreenerResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ФИНАЛЬНАЯ ИСПРАВЛЕННАЯ версия DEX Screener сервиса.
 * Исправлены все ошибки JSON десериализации и IndexOutOfBoundsException.
 */
@Service
@ConditionalOnProperty(name = "modules.dex-screener.enabled", havingValue = "true")
@Slf4j
public class DexScreenerService {

    private final WebClient webClient;
    private final PoolRepository poolRepository;

    // Известные токены для поиска основных пар
    private static final List<String> MAIN_TOKENS = List.of(
            "So11111111111111111111111111111111111111112", // SOL
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
            "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"  // USDT
    );

    // Целевые DEX
    private static final Set<String> TARGET_DEX = Set.of(
            "raydium", "orca", "meteora", "jupiter"
    );

    // Счетчики для статистики
    private final AtomicInteger totalPoolsLoaded = new AtomicInteger(0);
    private final AtomicInteger lastScanCount = new AtomicInteger(0);
    private volatile LocalDateTime lastScanTime;

    @Value("${dex-screener.api.base-url:https://api.dexscreener.com/latest}")
    private String baseUrl;

    @Value("${dex-screener.scanner.min-liquidity-usd:40000}")
    private double minLiquidityUsd;

    @Value("${dex-screener.api.timeout-seconds:15}")
    private int timeoutSeconds;

    public DexScreenerService(PoolRepository poolRepository) {
        this.poolRepository = poolRepository;

        this.webClient = WebClient.builder()
                .baseUrl("https://api.dexscreener.com/latest")
                .defaultHeaders(headers -> {
                    headers.add("User-Agent", "SolanaArbitrageBot/1.0");
                    headers.add("Accept", "application/json");
                })
                .build();

        log.info("DEX Screener WebClient initialized");
    }

    /**
     * Основной метод - загружаем пулы асинхронно
     */
    @Scheduled(fixedDelayString = "${dex-screener.scanner.interval-minutes:10}000")
    public void loadAndSavePools() {
        log.info("Starting DEX Screener scan...");
        lastScanTime = LocalDateTime.now();

        // Асинхронная загрузка
        CompletableFuture.supplyAsync(this::fetchAllValidPools)
                .thenAccept(pools -> {
                    int savedCount = savePoolsToDatabase(pools);
                    lastScanCount.set(savedCount);
                    totalPoolsLoaded.addAndGet(savedCount);

                    log.info("DEX Screener scan completed. Saved {} pools", savedCount);
                    deactivateOldPools();
                })
                .exceptionally(error -> {
                    log.error("DEX Screener scan failed", error);
                    return null;
                });
    }

    /**
     * ИСПРАВЛЕННЫЙ подход - используем только стабильные методы
     */
    private List<Pool> fetchAllValidPools() {
        List<Pool> allPools = new ArrayList<>();

        try {
            // Метод 1: Поиск топ пулов Solana (стабильно работает)
            List<Pool> topPools = fetchTopSolanaPools();
            allPools.addAll(topPools);
            log.debug("Found {} pools via top search", topPools.size());

            // Метод 2: ИСПРАВЛЕННЫЙ token-pairs endpoint
            List<Pool> tokenPools = fetchPoolsByTokenPairsFixed();
            allPools.addAll(tokenPools);
            log.debug("Found {} pools via token pairs", tokenPools.size());

            // Метод 3: Поисковые запросы (стабильно работают)
            List<Pool> searchPools = fetchPoolsBySearchQueries();
            allPools.addAll(searchPools);
            log.debug("Found {} pools via search queries", searchPools.size());

        } catch (Exception e) {
            log.error("Error fetching pools", e);
        }

        // Дедупликация и фильтрация
        return allPools.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        Pool::getAddress,
                        pool -> pool,
                        (existing, replacement) -> existing
                ))
                .values()
                .stream()
                .filter(pool -> pool.getTvlUsd() != null && pool.getTvlUsd() >= minLiquidityUsd)
                .filter(pool -> TARGET_DEX.contains(pool.getDexName().toLowerCase()))
                .collect(Collectors.toList());
    }

    /**
     * ИСПРАВЛЕНО: /token-pairs/v1/ возвращает массив напрямую, не объект с полем pairs
     */
    private List<Pool> fetchPoolsByTokenPairsFixed() {
        List<Pool> pools = new ArrayList<>();

        for (String tokenAddress : MAIN_TOKENS) {
            try {
                // API возвращает массив DexPool[], НЕ DexScreenerResponse
                String fullUrl = "https://api.dexscreener.com/token-pairs/v1/solana/" + tokenAddress;

                List<DexPool> response = WebClient.create()
                        .get()
                        .uri(fullUrl)
                        .retrieve()
                        .bodyToFlux(DexPool.class)  // ИСПРАВЛЕНО: bodyToFlux для массива
                        .collectList()
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .doOnError(error -> log.debug("Token pairs API error for {}: {}", tokenAddress, error.getMessage()))
                        .onErrorReturn(Collections.emptyList())
                        .block();

                if (response != null && !response.isEmpty()) {
                    List<Pool> tokenPools = response.stream()
                            .filter(this::isValidDexPool)
                            .map(this::convertDexPoolToPoolSafe)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

                    pools.addAll(tokenPools);
                    log.debug("Found {} pools for token {}", tokenPools.size(), tokenAddress);
                }

                Thread.sleep(500); // Rate limiting

            } catch (Exception e) {
                log.debug("Failed to fetch token pairs for {}: {}", tokenAddress, e.getMessage());
            }
        }

        return pools;
    }

    /**
     * Поисковые запросы (работают стабильно)
     */
    private List<Pool> fetchPoolsBySearchQueries() {
        List<Pool> pools = new ArrayList<>();

        String[] searchQueries = {
                "solana trending",  // Популярные пулы
                "SOL", "USDC",     // Основные токены
                "raydium", "orca"  // Основные DEX
        };

        for (String query : searchQueries) {
            try {
                String endpoint = "/dex/search?q=" +
                        java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);

                DexScreenerResponse response = webClient
                        .get()
                        .uri(endpoint)
                        .retrieve()
                        .bodyToMono(DexScreenerResponse.class)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .doOnError(error -> log.debug("Search failed for '{}': {}", query, error.getMessage()))
                        .onErrorReturn(new DexScreenerResponse())
                        .block();

                if (response != null && response.getPairs() != null) {
                    List<Pool> queryPools = response.getPairs().stream()
                            .filter(pool -> "solana".equalsIgnoreCase(pool.getChainId()))
                            .filter(this::isValidDexPool)
                            .limit(15)
                            .map(this::convertDexPoolToPoolSafe)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

                    pools.addAll(queryPools);
                    log.debug("Search '{}' found {} pools", query, queryPools.size());
                }

                Thread.sleep(600); // Rate limiting

            } catch (Exception e) {
                log.debug("Search failed for '{}': {}", query, e.getMessage());
            }
        }

        return pools;
    }

    /**
     * Топ пулы Solana (работает стабильно)
     */
    private List<Pool> fetchTopSolanaPools() {
        try {
            DexScreenerResponse response = webClient
                    .get()
                    .uri("/dex/search?q=solana")
                    .retrieve()
                    .bodyToMono(DexScreenerResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .doOnError(error -> log.debug("Failed to fetch top Solana pools: {}", error.getMessage()))
                    .onErrorReturn(new DexScreenerResponse())
                    .block();

            if (response != null && response.getPairs() != null) {
                return response.getPairs().stream()
                        .filter(pool -> "solana".equalsIgnoreCase(pool.getChainId()))
                        .filter(this::isValidDexPool)
                        .limit(25)
                        .map(this::convertDexPoolToPoolSafe)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }

        } catch (Exception e) {
            log.debug("Failed to fetch top Solana pools: {}", e.getMessage());
        }

        return List.of();
    }

    /**
     * Валидация пулов
     */
    private boolean isValidDexPool(DexPool dexPool) {
        if (dexPool == null) return false;

        // Базовая валидация
        if (dexPool.getPairAddress() == null ||
                dexPool.getBaseToken() == null ||
                dexPool.getQuoteToken() == null ||
                dexPool.getBaseToken().getAddress() == null ||
                dexPool.getQuoteToken().getAddress() == null) {
            return false;
        }

        // Проверяем что это Solana
        if (!"solana".equalsIgnoreCase(dexPool.getChainId())) {
            return false;
        }

        // Проверяем ликвидность
        if (dexPool.getLiquidity() != null && dexPool.getLiquidity().getUsd() != null) {
            return dexPool.getLiquidity().getUsd() >= minLiquidityUsd;
        }

        return false; // Нет данных о ликвидности
    }

    /**
     * ИСПРАВЛЕННАЯ конвертация с полной защитой от ошибок
     */
    private Pool convertDexPoolToPoolSafe(DexPool dexPool) {
        try {
            String dexName = dexPool.getDexId() != null ?
                    dexPool.getDexId().toLowerCase() : "unknown";

            Double tvl = null;
            if (dexPool.getLiquidity() != null && dexPool.getLiquidity().getUsd() != null) {
                tvl = dexPool.getLiquidity().getUsd();
            }

            return Pool.builder()
                    .address(dexPool.getPairAddress())
                    .tokenAMint(dexPool.getBaseToken().getAddress())
                    .tokenBMint(dexPool.getQuoteToken().getAddress())
                    .tokenASymbol(safeCleanSymbol(dexPool.getBaseToken().getSymbol()))
                    .tokenBSymbol(safeCleanSymbol(dexPool.getQuoteToken().getSymbol()))
                    .tokenAName(safeCleanName(dexPool.getBaseToken().getName()))
                    .tokenBName(safeCleanName(dexPool.getQuoteToken().getName()))
                    .tvlUsd(tvl)
                    .dexName(dexName)
                    .feeRate(Pool.getDefaultFeeRate(dexName))
                    .lastUpdated(LocalDateTime.now())
                    .isActive(true)
                    .source("DEX_SCREENER")
                    .build();

        } catch (Exception e) {
            log.debug("Failed to convert pool {}: {}", dexPool.getPairAddress(), e.getMessage());
            return null;
        }
    }

    /**
     * ИСПРАВЛЕНО: Безопасная очистка символа - НЕ БРОСАЕТ IndexOutOfBoundsException
     */
    private String safeCleanSymbol(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            return null;
        }

        try {
            String cleaned = symbol.trim().replaceAll("[^a-zA-Z0-9]", "");
            if (cleaned.isEmpty()) {
                return null;
            }

            // КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: проверяем длину ДО substring
            if (cleaned.length() == 0) {
                return null;
            }

            int maxLength = Math.min(cleaned.length(), 20);
            return cleaned.substring(0, maxLength);

        } catch (Exception e) {
            log.debug("Error cleaning symbol '{}': {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * ИСПРАВЛЕНО: Безопасная очистка названия - НЕ БРОСАЕТ IndexOutOfBoundsException
     */
    private String safeCleanName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }

        try {
            String cleaned = name.trim();

            // КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: проверяем длину ДО substring
            if (cleaned.length() == 0) {
                return null;
            }

            int maxLength = Math.min(cleaned.length(), 100);
            return cleaned.substring(0, maxLength);

        } catch (Exception e) {
            log.debug("Error cleaning name '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Сохранение пулов в БД
     */
    private int savePoolsToDatabase(List<Pool> pools) {
        int savedCount = 0;

        for (Pool pool : pools) {
            try {
                Optional<Pool> existing = poolRepository.findByAddress(pool.getAddress());

                if (existing.isPresent()) {
                    Pool existingPool = existing.get();
                    existingPool.updateFrom(pool);
                    poolRepository.save(existingPool);
                } else {
                    poolRepository.save(pool);
                }

                savedCount++;

            } catch (Exception e) {
                log.error("Failed to save pool {}: {}", pool.getAddress(), e.getMessage());
            }
        }

        return savedCount;
    }

    /**
     * Деактивация старых пулов
     */
    private void deactivateOldPools() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
            int deactivated = poolRepository.deactivateOldPools(cutoff);

            if (deactivated > 0) {
                log.info("Deactivated {} old pools", deactivated);
            }
        } catch (Exception e) {
            log.error("Failed to deactivate old pools", e);
        }
    }

    // === ПУБЛИЧНЫЕ МЕТОДЫ ===

    public void triggerManualScan() {
        log.info("Manual DEX Screener scan triggered");
        loadAndSavePools();
    }

    public ScanStats getScanStats() {
        return new ScanStats(
                totalPoolsLoaded.get(),
                lastScanCount.get(),
                lastScanTime,
                poolRepository.countActivePools(),
                getDexPoolCounts()
        );
    }

    private Map<String, Long> getDexPoolCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (String dex : TARGET_DEX) {
            counts.put(dex, poolRepository.countActivePoolsByDex(dex));
        }
        return counts;
    }

    public record ScanStats(
            int totalPoolsLoaded,
            int lastScanCount,
            LocalDateTime lastScanTime,
            Long activePoolsInDb,
            Map<String, Long> dexPoolCounts
    ) {}
}