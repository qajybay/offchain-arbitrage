package com.berrx.service;

import com.berrx.model.Pool;
import com.berrx.repository.PoolRepository;
import com.berrx.service.dto.DexPool;
import com.berrx.service.dto.DexScreenerResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * DexScreenerService оптимизированный для работы с rate limiting Solana RPC.
 * Уменьшено количество RPC вызовов и добавлена интеллектуальная приоритизация пулов.
 */
@Service
@ConditionalOnProperty(name = "modules.dex-screener.enabled", havingValue = "true")
@Slf4j
public class DexScreenerService {

    private final WebClient webClient;
    private final PoolRepository poolRepository;
    private final SolanaPriceService solanaPriceService;

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
    private final AtomicInteger poolsWithPrices = new AtomicInteger(0);
    private volatile LocalDateTime lastScanTime;

    @Value("${dex-screener.scanner.min-liquidity-usd:40000}")
    private double minLiquidityUsd;

    @Value("${dex-screener.api.timeout-seconds:15}")
    private int timeoutSeconds;

    @Value("${dex-screener.price-updates.enabled:true}")
    private boolean priceUpdatesEnabled;

    // НОВОЕ: Настройки для RPC rate limiting
    @Value("${dex-screener.price-updates.max-pools:3}")
    private int maxPoolsForPriceUpdate;

    @Value("${dex-screener.price-updates.min-tvl-multiplier:5}")
    private double minTvlMultiplier;

    public DexScreenerService(PoolRepository poolRepository,
                              @Autowired(required = false) SolanaPriceService solanaPriceService) {
        this.poolRepository = poolRepository;
        this.solanaPriceService = solanaPriceService;

        this.webClient = WebClient.builder()
                .baseUrl("https://api.dexscreener.com/latest")
                .defaultHeaders(headers -> {
                    headers.add("User-Agent", "SolanaArbitrageBot/1.0");
                    headers.add("Accept", "application/json");
                })
                .build();

        log.info("DEX Screener Service initialized with RPC integration: {}",
                solanaPriceService != null ? "ENABLED" : "DISABLED");
        log.info("RPC settings: max {} pools, min TVL multiplier: {}x",
                maxPoolsForPriceUpdate, minTvlMultiplier);
    }

    /**
     * ГЛАВНЫЙ МЕТОД: Загрузка метаданных + обновление цен (ОПТИМИЗИРОВАННЫЙ)
     */
    @Scheduled(fixedDelayString = "${dex-screener.scanner.interval-minutes:15}000") // Увеличен интервал
    public void loadAndSavePools() {
        log.info("Starting DEX Screener scan with conservative RPC updates...");
        lastScanTime = LocalDateTime.now();

        CompletableFuture.supplyAsync(this::fetchAllValidPools)
                .thenCompose(pools -> {
                    // Сохраняем метаданные
                    int savedCount = savePoolsToDatabase(pools);
                    lastScanCount.set(savedCount);
                    totalPoolsLoaded.addAndGet(savedCount);

                    log.info("Saved {} pools metadata from DEX Screener", savedCount);

                    // ОБНОВЛЕНО: Консервативное обновление цен
                    if (priceUpdatesEnabled && solanaPriceService != null && !pools.isEmpty()) {
                        return updatePoolPricesConservative(pools);
                    } else {
                        return CompletableFuture.completedFuture(pools);
                    }
                })
                .thenAccept(poolsWithUpdatedPrices -> {
                    if (poolsWithUpdatedPrices != null) {
                        long withPrices = poolsWithUpdatedPrices.stream()
                                .mapToLong(pool -> pool.hasCurrentPrices() ? 1 : 0)
                                .sum();

                        poolsWithPrices.set((int) withPrices);
                        log.info("Updated prices for {}/{} pools via Solana RPC",
                                withPrices, poolsWithUpdatedPrices.size());
                    }

                    // Деактивируем старые пулы
                    deactivateOldPools();

                    log.info("DEX Screener scan completed successfully");
                })
                .exceptionally(error -> {
                    log.error("DEX Screener scan failed", error);
                    return null;
                });
    }

    /**
     * НОВОЕ: Консервативное обновление цен с минимальным количеством RPC вызовов
     */
    private CompletableFuture<List<Pool>> updatePoolPricesConservative(List<Pool> pools) {
        if (solanaPriceService == null) {
            return CompletableFuture.completedFuture(pools);
        }

        return CompletableFuture.supplyAsync(() -> {
            log.debug("Starting conservative price updates for {} pools", pools.size());

            // ОПТИМИЗИРОВАНО: Выбираем ТОЛЬКО самые крупные и важные пулы
            List<Pool> prioritizedPools = pools.stream()
                    .filter(pool -> pool.getTvlUsd() != null &&
                            pool.getTvlUsd() >= minLiquidityUsd * minTvlMultiplier) // Только очень крупные
                    .filter(pool -> isHighPriorityPool(pool)) // Только приоритетные
                    .sorted((p1, p2) -> {
                        // Сортируем по приоритету: TVL + тип пары
                        double priority1 = calculatePoolPriority(p1);
                        double priority2 = calculatePoolPriority(p2);
                        return Double.compare(priority2, priority1);
                    })
                    .limit(maxPoolsForPriceUpdate) // ЖЁСТКИЙ лимит
                    .collect(Collectors.toList());

            log.info("Selected {} high-priority pools for price updates (from {} total)",
                    prioritizedPools.size(), pools.size());

            // Логируем выбранные пулы
            prioritizedPools.forEach(pool ->
                    log.debug("Updating prices for priority pool: {} (TVL: {})",
                            pool.getDisplayName(), pool.getFormattedTvl()));

            List<Pool> updatedPools = solanaPriceService.updatePoolPrices(prioritizedPools);

            // Возвращаем ВСЕ пулы (обновленные + остальные)
            return pools;
        });
    }

    /**
     * НОВОЕ: Определение высокоприоритетных пулов
     */
    private boolean isHighPriorityPool(Pool pool) {
        // Только SOL, USDC, USDT пары
        if (!pool.containsSol() && !pool.containsStablecoin()) {
            return false;
        }

        // Только основные DEX
        if (pool.getDexName() == null) return false;
        String dex = pool.getDexName().toLowerCase();
        if (!Set.of("raydium", "orca").contains(dex)) {
            return false;
        }

        // Только пулы с метаданными
        return pool.hasValidMetadata();
    }

    /**
     * НОВОЕ: Расчёт приоритета пула
     */
    private double calculatePoolPriority(Pool pool) {
        double priority = 0;

        // TVL вес (основной фактор)
        if (pool.getTvlUsd() != null) {
            priority += Math.log10(pool.getTvlUsd()) * 1000;
        }

        // Тип пары бонус
        if (pool.isStablePair()) {
            priority += 10000; // USDC/USDT - высший приоритет
        } else if (pool.containsSol()) {
            priority += 5000;  // SOL пары - высокий приоритет
        } else if (pool.containsStablecoin()) {
            priority += 2000;  // Другие стейблкоин пары
        }

        // DEX бонус
        if (pool.getDexName() != null) {
            switch (pool.getDexName().toLowerCase()) {
                case "raydium" -> priority += 1000;
                case "orca" -> priority += 800;
                case "meteora" -> priority += 600;
                case "jupiter" -> priority += 400;
            }
        }

        return priority;
    }

    // Остальные методы без изменений (fetchAllValidPools, validation, conversion, etc.)

    private List<Pool> fetchAllValidPools() {
        List<Pool> allPools = new ArrayList<>();

        try {
            List<Pool> topPools = fetchTopSolanaPools();
            allPools.addAll(topPools);
            log.debug("Found {} pools via top search", topPools.size());

            List<Pool> tokenPools = fetchPoolsByTokenPairsFixed();
            allPools.addAll(tokenPools);
            log.debug("Found {} pools via token pairs", tokenPools.size());

            List<Pool> searchPools = fetchPoolsBySearchQueries();
            allPools.addAll(searchPools);
            log.debug("Found {} pools via search queries", searchPools.size());

        } catch (Exception e) {
            log.error("Error fetching pools", e);
        }

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

    private List<Pool> fetchPoolsByTokenPairsFixed() {
        List<Pool> pools = new ArrayList<>();

        for (String tokenAddress : MAIN_TOKENS) {
            try {
                String fullUrl = "https://api.dexscreener.com/token-pairs/v1/solana/" + tokenAddress;

                List<DexPool> response = WebClient.create()
                        .get()
                        .uri(fullUrl)
                        .retrieve()
                        .bodyToFlux(DexPool.class)
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

                Thread.sleep(500);

            } catch (Exception e) {
                log.debug("Failed to fetch token pairs for {}: {}", tokenAddress, e.getMessage());
            }
        }

        return pools;
    }

    private List<Pool> fetchPoolsBySearchQueries() {
        List<Pool> pools = new ArrayList<>();

        String[] searchQueries = {
                "solana trending",
                "SOL", "USDC",
                "raydium", "orca"
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

                Thread.sleep(600);

            } catch (Exception e) {
                log.debug("Search failed for '{}': {}", query, e.getMessage());
            }
        }

        return pools;
    }

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

    // Остальные методы остаются без изменений
    private boolean isValidDexPool(DexPool dexPool) {
        if (dexPool == null) return false;

        if (dexPool.getPairAddress() == null ||
                dexPool.getBaseToken() == null ||
                dexPool.getQuoteToken() == null ||
                dexPool.getBaseToken().getAddress() == null ||
                dexPool.getQuoteToken().getAddress() == null) {
            return false;
        }

        if (!"solana".equalsIgnoreCase(dexPool.getChainId())) {
            return false;
        }

        if (dexPool.getLiquidity() != null && dexPool.getLiquidity().getUsd() != null) {
            return dexPool.getLiquidity().getUsd() >= minLiquidityUsd;
        }

        return false;
    }

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

    private String safeCleanSymbol(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            return null;
        }

        try {
            String cleaned = symbol.trim().replaceAll("[^a-zA-Z0-9]", "");
            if (cleaned.isEmpty()) {
                return null;
            }

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

    private String safeCleanName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }

        try {
            String cleaned = name.trim();

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

    // Публичные методы

    public void triggerManualScan() {
        log.info("Manual DEX Screener scan triggered");
        loadAndSavePools();
    }

    public ScanStats getScanStats() {
        return new ScanStats(
                totalPoolsLoaded.get(),
                lastScanCount.get(),
                poolsWithPrices.get(),
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
            int poolsWithPrices,
            LocalDateTime lastScanTime,
            Long activePoolsInDb,
            Map<String, Long> dexPoolCounts
    ) {}
}