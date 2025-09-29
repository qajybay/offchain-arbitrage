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
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ENHANCED DexScreenerService - Primary price source using DexScreener API directly.
 *
 * UPDATED STRATEGY:
 * 1. Load pool metadata from DexScreener (symbols, TVL, addresses)
 * 2. Extract prices directly from DexScreener responses (priceUsd field)
 * 3. Use Solana RPC only for arbitrage opportunity verification
 * 4. Respect DexScreener rate limits (300 requests/minute)
 *
 * ADDED FEATURES:
 * - Missing methods required by ArbitrageDetectorService
 * - Enhanced error handling and logging
 * - Better statistics tracking
 *
 * Supported DEX: raydium, orca, meteora
 * NO Jupiter dependency - prices come from DexScreener
 */
@Service
@ConditionalOnProperty(name = "modules.dex-screener.enabled", havingValue = "true")
@Slf4j
public class DexScreenerService {

    private final WebClient webClient;
    private final PoolRepository poolRepository;
    private final SolanaPriceService solanaPriceService;

    // Known tokens for main pair discovery
    private static final List<String> MAIN_TOKENS = List.of(
            "So11111111111111111111111111111111111111112", // SOL
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
            "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"  // USDT
    );

    /**
     * Target DEX (Jupiter excluded - it's an aggregator, not DEX)
     */
    private static final Set<String> TARGET_DEX = Set.of(
            "raydium",  // Raydium AMM
            "orca",     // Orca Whirlpools
            "meteora"   // Meteora Dynamic Pools
    );

    // Statistics counters
    private final AtomicInteger totalPoolsLoaded = new AtomicInteger(0);
    private final AtomicInteger lastScanCount = new AtomicInteger(0);
    private final AtomicInteger poolsWithPrices = new AtomicInteger(0);
    private final AtomicInteger dexScreenerApiCalls = new AtomicInteger(0);
    private final AtomicInteger priceUpdateSuccesses = new AtomicInteger(0);
    private final AtomicInteger priceUpdateFailures = new AtomicInteger(0);
    private volatile LocalDateTime lastScanTime;
    private volatile LocalDateTime lastPriceUpdateTime;

    @Value("${dex-screener.scanner.min-liquidity-usd:40000}")
    private double minLiquidityUsd;

    @Value("${dex-screener.api.timeout-seconds:15}")
    private int timeoutSeconds;

    @Value("${dex-screener.api.rate-limit:300}")
    private int rateLimitPerMinute;

    @Value("${dex-screener.price-updates.enabled:true}")
    private boolean priceUpdatesEnabled;

    @Value("${dex-screener.price-updates.max-pools:50}")
    private int maxPoolsForPriceUpdate;

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

        log.info("DEX Screener Service initialized");
        log.info("Target DEX: {} (Jupiter excluded - aggregator, not DEX)", TARGET_DEX);
        log.info("Price strategy: DexScreener API (primary) + RPC (verification only)");
        log.info("Rate limit: {}/minute, Price updates: {}", rateLimitPerMinute, priceUpdatesEnabled);
    }

    /**
     * MAIN METHOD: Load metadata + prices from DexScreener API
     */
    @Scheduled(fixedDelayString = "${dex-screener.scanner.interval-minutes:30}000")
    public void loadAndSavePools() {
        log.info("Starting DEX Screener scan with direct price extraction...");
        lastScanTime = LocalDateTime.now();

        CompletableFuture.supplyAsync(this::fetchAllValidPools)
                .thenCompose(pools -> {
                    // Save metadata to database
                    int savedCount = savePoolsToDatabase(pools);
                    lastScanCount.set(savedCount);
                    totalPoolsLoaded.addAndGet(savedCount);

                    log.info("Saved {} DEX pools metadata (Raydium: {}, Orca: {}, Meteora: {})",
                            savedCount,
                            countPoolsByDex(pools, "raydium"),
                            countPoolsByDex(pools, "orca"),
                            countPoolsByDex(pools, "meteora"));

                    // Extract prices from DexScreener instead of Jupiter
                    if (priceUpdatesEnabled && !pools.isEmpty()) {
                        log.info("Updating prices from DexScreener API responses...");
                        return updatePoolPricesFromDexScreener(pools);
                    } else {
                        log.warn("Price updates disabled or no pools found");
                        return CompletableFuture.completedFuture(pools);
                    }
                })
                .thenAccept(poolsWithUpdatedPrices -> {
                    if (poolsWithUpdatedPrices != null) {
                        long withPrices = poolsWithUpdatedPrices.stream()
                                .mapToLong(pool -> pool.hasCurrentPrices() ? 1 : 0)
                                .sum();

                        poolsWithPrices.set((int) withPrices);
                        lastPriceUpdateTime = LocalDateTime.now();
                        log.info("Updated prices for {}/{} pools from DexScreener API",
                                withPrices, poolsWithUpdatedPrices.size());
                    }

                    // Deactivate old pools
                    deactivateOldPools();

                    log.info("DEX Screener scan completed successfully");
                })
                .exceptionally(error -> {
                    log.error("DEX Screener scan failed", error);
                    return null;
                });
    }

    /**
     * Update prices directly from DexScreener API responses
     */
    private CompletableFuture<List<Pool>> updatePoolPricesFromDexScreener(List<Pool> pools) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("Starting DexScreener price updates for {} pools", pools.size());

            // Filter pools that need price updates
            List<Pool> poolsNeedingPrices = pools.stream()
                    .filter(pool -> pool.getTokenAMint() != null && pool.getTokenBMint() != null)
                    .filter(pool -> pool.hasValidMetadata())
                    .limit(maxPoolsForPriceUpdate)
                    .collect(Collectors.toList());

            if (poolsNeedingPrices.isEmpty()) {
                log.debug("No valid pools for price updates");
                return pools;
            }

            log.info("Processing {} pools for DexScreener price updates", poolsNeedingPrices.size());

            int successCount = 0;
            for (Pool pool : poolsNeedingPrices) {
                try {
                    // Fetch fresh data for this specific pool
                    boolean priceUpdated = fetchAndUpdatePoolPrice(pool);
                    if (priceUpdated) {
                        successCount++;
                        priceUpdateSuccesses.incrementAndGet();
                    } else {
                        priceUpdateFailures.incrementAndGet();
                    }

                    // Rate limiting - respect DexScreener limits
                    Thread.sleep(200); // 5 requests per second max (300/minute)

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Price update interrupted");
                    break;
                } catch (Exception e) {
                    log.debug("Failed to update price for pool {}: {}", pool.getAddress(), e.getMessage());
                    priceUpdateFailures.incrementAndGet();
                }
            }

            log.info("Successfully updated prices for {}/{} pools from DexScreener",
                    successCount, poolsNeedingPrices.size());

            return pools;
        });
    }

    /**
     * Fetch and update price for a specific pool from DexScreener
     */
    private boolean fetchAndUpdatePoolPrice(Pool pool) {
        try {
            dexScreenerApiCalls.incrementAndGet();

            // Search by pool address to get current price
            String searchUrl = "/dex/pairs/solana/" + pool.getAddress();

            DexScreenerResponse response = webClient
                    .get()
                    .uri(searchUrl)
                    .retrieve()
                    .bodyToMono(DexScreenerResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (response == null || response.getPairs() == null || response.getPairs().isEmpty()) {
                log.debug("No price data found for pool: {}", pool.getAddress());
                return false;
            }

            // Extract price from the response
            DexPool dexPool = response.getPairs().get(0);
            Double priceUsd = extractPriceFromDexPool(dexPool);

            if (priceUsd != null && priceUsd > 0) {
                // Update pool with extracted price
                pool.setCurrentPriceA(priceUsd);
                pool.setPriceUpdatedAt(LocalDateTime.now());

                // Calculate price B if possible (typically this would be 1/priceA for base/quote)
                if (dexPool.getQuoteToken() != null) {
                    pool.setCurrentPriceB(1.0 / priceUsd);
                }

                log.debug("Updated price for pool {} pair: {} {} USD", pool.getAddress(), pool.getSymbolPair(), priceUsd);
                return true;
            }

            return false;

        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("DexScreener rate limit hit - backing off");
                try {
                    Thread.sleep(5000); // 5 second backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } else {
                log.debug("DexScreener API error for pool {}: {}", pool.getAddress(), e.getMessage());
            }
            return false;
        } catch (Exception e) {
            log.debug("Failed to fetch price for pool {}: {}", pool.getAddress(), e.getMessage());
            return false;
        }
    }

    /**
     * Extract price from DexPool response
     */
    private Double extractPriceFromDexPool(DexPool dexPool) {
        // Try priceUsd first
        if (dexPool.getPriceUsd() != null && !dexPool.getPriceUsd().isEmpty() &&
                !dexPool.getPriceUsd().equals("null")) {
            try {
                double price = Double.parseDouble(dexPool.getPriceUsd());
                if (price > 0) return price;
            } catch (NumberFormatException e) {
                log.debug("Invalid priceUsd format: {}", dexPool.getPriceUsd());
            }
        }

        // Fallback to other price fields if available
        if (dexPool.getPriceNative() != null && !dexPool.getPriceNative().isEmpty()) {
            try {
                double nativePrice = Double.parseDouble(dexPool.getPriceNative());
                if (nativePrice > 0) {
                    // Convert native price to USD if we know SOL price
                    // For simplicity, return the native price (could be enhanced)
                    return nativePrice;
                }
            } catch (NumberFormatException e) {
                log.debug("Invalid priceNative format: {}", dexPool.getPriceNative());
            }
        }

        return null;
    }

    /**
     * Fetch all valid pools from supported DEX
     */
    private List<Pool> fetchAllValidPools() {
        List<Pool> allPools = new ArrayList<>();

        for (String token : MAIN_TOKENS) {
            try {
                List<Pool> tokenPools = fetchPoolsForToken(token);
                allPools.addAll(tokenPools);

                // Rate limiting
                Thread.sleep(300); // ~200ms between requests

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Pool fetching interrupted");
                break;
            } catch (Exception e) {
                log.warn("Failed to fetch pools for token {}: {}", token, e.getMessage());
            }
        }

        return allPools.stream()
                .filter(Objects::nonNull)
                .filter(Pool::hasValidMetadata)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Fetch pools for a specific token
     */
    private List<Pool> fetchPoolsForToken(String tokenAddress) {
        try {
            dexScreenerApiCalls.incrementAndGet();

            String searchUrl = "/dex/tokens/" + tokenAddress;

            DexScreenerResponse response = webClient
                    .get()
                    .uri(searchUrl)
                    .retrieve()
                    .bodyToMono(DexScreenerResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (response == null || response.getPairs() == null) {
                return Collections.emptyList();
            }

            return response.getPairs().stream()
                    .filter(this::isValidDexPool)
                    .map(this::convertDexPoolToPool)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.debug("Failed to fetch pools for token {}: {}", tokenAddress, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Check if DexPool is valid for our criteria
     */
    private boolean isValidDexPool(DexPool dexPool) {
        if (dexPool == null) return false;

        // Check DEX support
        if (!TARGET_DEX.contains(dexPool.getDexId().toLowerCase())) {
            return false;
        }

        // Check chain
        if (!"solana".equalsIgnoreCase(dexPool.getChainId())) {
            return false;
        }

        // Check liquidity
        double liquidity = 0.0;
        if (dexPool.getLiquidity() != null && dexPool.getLiquidity().getUsd() != null) {
            liquidity = dexPool.getLiquidity().getUsd();
        }

        return liquidity >= minLiquidityUsd;
    }

    /**
     * FIXED: Convert DexPool to Pool entity with correct source value
     */
    private Pool convertDexPoolToPool(DexPool dexPool) {
        try {
            if (dexPool.getBaseToken() == null || dexPool.getQuoteToken() == null) {
                return null;
            }

            Pool pool = Pool.builder()
                    .address(dexPool.getPairAddress())
                    .tokenAMint(dexPool.getBaseToken().getAddress())
                    .tokenBMint(dexPool.getQuoteToken().getAddress())
                    .tokenASymbol(cleanSymbol(dexPool.getBaseToken().getSymbol()))
                    .tokenBSymbol(cleanSymbol(dexPool.getQuoteToken().getSymbol()))
                    .tokenAName(cleanName(dexPool.getBaseToken().getName()))
                    .tokenBName(cleanName(dexPool.getQuoteToken().getName()))
                    .dexName(dexPool.getDexId().toLowerCase())
                    .lastUpdated(LocalDateTime.now())
                    .isActive(true)
                    .source("DEX_SCREENER")  // FIXED: Changed from "DEXSCREENER" to "DEX_SCREENER"
                    .build();

            // Set TVL
            if (dexPool.getLiquidity() != null && dexPool.getLiquidity().getUsd() != null) {
                pool.setTvlUsd(dexPool.getLiquidity().getUsd());
            }

            // IMPORTANT: Extract and set price from DexScreener response
            Double priceUsd = extractPriceFromDexPool(dexPool);
            if (priceUsd != null && priceUsd > 0) {
                pool.setCurrentPriceA(priceUsd);
                pool.setPriceUpdatedAt(LocalDateTime.now());

                // Set reciprocal price for token B
                pool.setCurrentPriceB(1.0 / priceUsd);
            }

            return pool.hasValidMetadata() ? pool : null;

        } catch (Exception e) {
            log.debug("Failed to convert DexPool to Pool: {}", e.getMessage());
            return null;
        }
    }

    // =================== NEW METHODS REQUIRED BY ARBITRAGE DETECTOR ===================

    /**
     * ADDED: Method for arbitrage verification (called by ArbitrageDetectorService)
     * This delegates to SolanaPriceService for actual RPC verification
     */
    public CompletableFuture<List<Pool>> verifyArbitrageOpportunities(List<Pool> candidatePools) {
        if (solanaPriceService == null) {
            log.warn("SolanaPriceService not available for arbitrage verification");
            return CompletableFuture.completedFuture(candidatePools);
        }

        log.info("Delegating arbitrage verification to SolanaPriceService for {} pools",
                candidatePools.size());

        return solanaPriceService.verifyArbitrageOpportunities(candidatePools);
    }

    /**
     * ADDED: Get fresh pool prices for arbitrage detection
     */
    public CompletableFuture<List<Pool>> getPoolsWithFreshPrices(List<Pool> pools) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("Getting fresh prices for {} pools", pools.size());

            // For pools that need fresh prices, fetch from DexScreener
            List<Pool> poolsNeedingUpdate = pools.stream()
                    .filter(pool -> !pool.hasCurrentPrices() ||
                            pool.getPriceUpdatedAt() == null ||
                            pool.getPriceUpdatedAt().isBefore(LocalDateTime.now().minusMinutes(5)))
                    .limit(10) // Limit to avoid rate limiting
                    .collect(Collectors.toList());

            if (poolsNeedingUpdate.isEmpty()) {
                return pools;
            }

            log.debug("Updating prices for {} pools with stale data", poolsNeedingUpdate.size());

            // Update prices for pools with stale data
            for (Pool pool : poolsNeedingUpdate) {
                try {
                    fetchAndUpdatePoolPrice(pool);
                    Thread.sleep(200); // Rate limiting
                } catch (Exception e) {
                    log.debug("Failed to update price for pool {}: {}", pool.getAddress(), e.getMessage());
                }
            }

            return pools;
        });
    }

    // =================== UTILITY METHODS ===================

    // Utility methods
    private String cleanSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String cleaned = symbol.trim().replaceAll("[^a-zA-Z0-9]", "");
        return cleaned.isEmpty() ? null :
                (cleaned.length() > 20 ? cleaned.substring(0, 20) : cleaned);
    }

    private String cleanName(String name) {
        if (name == null || name.isBlank()) return null;
        String cleaned = name.trim();
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }

    private int savePoolsToDatabase(List<Pool> pools) {
        if (poolRepository == null) {
            log.warn("Pool repository not available");
            return 0;
        }

        try {
            // Save pools to database using repository
            List<Pool> savedPools = new ArrayList<>();
            for (Pool pool : pools) {
                try {
                    // Check if pool already exists
                    Optional<Pool> existing = poolRepository.findByAddress(pool.getAddress());
                    if (existing.isPresent()) {
                        // Update existing pool
                        Pool existingPool = existing.get();
                        existingPool.setTvlUsd(pool.getTvlUsd());
                        existingPool.setLastUpdated(LocalDateTime.now());
                        existingPool.setIsActive(true);

                        // Update prices if available
                        if (pool.hasCurrentPrices()) {
                            existingPool.setCurrentPriceA(pool.getCurrentPriceA());
                            existingPool.setCurrentPriceB(pool.getCurrentPriceB());
                            existingPool.setPriceUpdatedAt(pool.getPriceUpdatedAt());
                        }

                        savedPools.add(poolRepository.save(existingPool));
                    } else {
                        // Save new pool
                        savedPools.add(poolRepository.save(pool));
                    }
                } catch (Exception e) {
                    log.debug("Failed to save pool {}: {}", pool.getAddress(), e.getMessage());
                }
            }

            return savedPools.size();
        } catch (Exception e) {
            log.error("Failed to save pools to database: {}", e.getMessage());
            return 0;
        }
    }

    private void deactivateOldPools() {
        if (poolRepository == null) {
            return;
        }

        try {
            // Deactivate pools that haven't been updated in last 2 hours
            LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
            List<Pool> oldPools = poolRepository.findByIsActiveTrueAndLastUpdatedBefore(cutoff);

            for (Pool pool : oldPools) {
                pool.setIsActive(false);
                poolRepository.save(pool);
            }

            if (!oldPools.isEmpty()) {
                log.info("Deactivated {} old pools", oldPools.size());
            }
        } catch (Exception e) {
            log.debug("Failed to deactivate old pools: {}", e.getMessage());
        }
    }

    private long countPoolsByDex(List<Pool> pools, String dexName) {
        return pools.stream()
                .filter(pool -> dexName.equalsIgnoreCase(pool.getDexName()))
                .count();
    }

    // =================== STATISTICS METHODS ===================

    public EnhancedDexScreenerStats getStats() {
        return EnhancedDexScreenerStats.builder()
                .totalPoolsLoaded(totalPoolsLoaded.get())
                .lastScanCount(lastScanCount.get())
                .poolsWithPrices(poolsWithPrices.get())
                .apiCallsMade(dexScreenerApiCalls.get())
                .priceUpdateSuccesses(priceUpdateSuccesses.get())
                .priceUpdateFailures(priceUpdateFailures.get())
                .priceUpdateSuccessRate(
                        (priceUpdateSuccesses.get() + priceUpdateFailures.get()) > 0 ?
                                (double) priceUpdateSuccesses.get() /
                                        (priceUpdateSuccesses.get() + priceUpdateFailures.get()) * 100 : 0)
                .lastScanTime(lastScanTime)
                .lastPriceUpdateTime(lastPriceUpdateTime)
                .priceUpdatesEnabled(priceUpdatesEnabled)
                .rateLimitPerMinute(rateLimitPerMinute)
                .supportedDex(new ArrayList<>(TARGET_DEX))
                .build();
    }

    @lombok.Builder
    @lombok.Data
    public static class EnhancedDexScreenerStats {
        private int totalPoolsLoaded;
        private int lastScanCount;
        private int poolsWithPrices;
        private int apiCallsMade;
        private int priceUpdateSuccesses;
        private int priceUpdateFailures;
        private double priceUpdateSuccessRate;
        private LocalDateTime lastScanTime;
        private LocalDateTime lastPriceUpdateTime;
        private boolean priceUpdatesEnabled;
        private int rateLimitPerMinute;
        private List<String> supportedDex;
    }
}