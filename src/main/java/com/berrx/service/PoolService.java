package com.berrx.service;

import com.berrx.config.MetricsConfig;
import com.berrx.config.SolanaConfig;
import com.berrx.model.Pool;
import com.berrx.repository.PoolRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.p2p.solanaj.core.PublicKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for scanning and managing liquidity pools from various Solana DEXs.
 * Focuses on pools with TVL >= 40k for arbitrage opportunities.
 */
@Slf4j
@Service
public class PoolService {

    private final PoolRepository poolRepository;
    private final SolanaConfig.ReactiveRpcClient rpcClient;
    private final SolanaConfig.SolanaPrograms solanaPrograms;
    private final MetricsConfig.ArbitrageMetrics metrics;
    private final ExecutorService virtualThreadExecutor;

    @Value("${arbitrage.thresholds.min-tvl:40000}")
    private long minTvl;

    @Value("${arbitrage.scanner.enabled:true}")
    private boolean scannerEnabled;

    @Value("${arbitrage.scanner.batch-size:20}")
    private int batchSize;

    private final AtomicInteger scannedPoolsCount = new AtomicInteger(0);
    private final AtomicLong lastScanTime = new AtomicLong(0);

    @Autowired
    public PoolService(PoolRepository poolRepository,
                       SolanaConfig.ReactiveRpcClient rpcClient,
                       SolanaConfig.SolanaPrograms solanaPrograms,
                       MetricsConfig.ArbitrageMetrics metrics,
                       @Qualifier("virtualThreadExecutor") ExecutorService virtualThreadExecutor) {
        this.poolRepository = poolRepository;
        this.rpcClient = rpcClient;
        this.solanaPrograms = solanaPrograms;
        this.metrics = metrics;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    @PostConstruct
    public void init() {
        if (scannerEnabled) {
            log.info("Pool scanner initialized with minTvl={}, batchSize={}", minTvl, batchSize);
        } else {
            log.info("Pool scanner is DISABLED");
        }
    }

    /**
     * Scheduled task to scan pools every few minutes
     */
    @Scheduled(fixedDelayString = "${arbitrage.scanner.interval-ms:300000}") // 5 minutes default
    @Async("virtualThreadExecutor")
    public void scheduledPoolScan() {
        if (!scannerEnabled) {
            return;
        }

        log.info("Starting scheduled pool scan...");
        var startTime = System.currentTimeMillis();

        scanAllDexPools()
                .doFinally(signalType -> {
                    lastScanTime.set(System.currentTimeMillis());
                    var duration = System.currentTimeMillis() - startTime;

                    if (signalType == reactor.core.publisher.SignalType.ON_COMPLETE) {
                        log.info("Pool scan completed in {}ms. Total pools scanned: {}",
                                duration, scannedPoolsCount.get());
                    } else if (signalType == reactor.core.publisher.SignalType.ON_ERROR) {
                        log.warn("Pool scan finished with error after {}ms", duration);
                    }
                })
                .doOnError(error -> log.error("Pool scan failed", error))
                .subscribe();
    }

    /**
     * Scan pools from all supported DEXs
     */
    public Mono<Void> scanAllDexPools() {
        return Flux.merge(
                        scanRaydiumPools(),
                        scanOrcaPools(),
                        scanJupiterPools()
                )
                .collectList()
                .doOnSuccess(results -> {
                    int totalFound = results.size();
                    metrics.updateActivePools(totalFound);
                    log.info("Scan completed. Found {} pools across all DEXs", totalFound);
                })
                .then();
    }

    /**
     * Scan Raydium pools
     */
    private Flux<Pool> scanRaydiumPools() {
        log.debug("Scanning Raydium pools...");

        return Mono.fromCallable(() -> scanRaydiumPoolsSync())
                .subscribeOn(Schedulers.fromExecutor(virtualThreadExecutor))
                .flatMapMany(Flux::fromIterable)
                .doOnComplete(() -> log.debug("Raydium scan completed"));
    }

    /**
     * Scan Orca pools
     */
    private Flux<Pool> scanOrcaPools() {
        log.debug("Scanning Orca pools...");

        return Mono.fromCallable(() -> scanOrcaPoolsSync())
                .subscribeOn(Schedulers.fromExecutor(virtualThreadExecutor))
                .flatMapMany(Flux::fromIterable)
                .doOnComplete(() -> log.debug("Orca scan completed"));
    }

    /**
     * Scan Jupiter aggregated pools
     */
    private Flux<Pool> scanJupiterPools() {
        log.debug("Scanning Jupiter pools...");

        return Mono.fromCallable(() -> scanJupiterPoolsSync())
                .subscribeOn(Schedulers.fromExecutor(virtualThreadExecutor))
                .flatMapMany(Flux::fromIterable)
                .doOnComplete(() -> log.debug("Jupiter scan completed"));
    }

    /**
     * Synchronous Raydium pool scanning using getProgramAccounts with filters
     */
    private List<Pool> scanRaydiumPoolsSync() {
        try {
            log.debug("Scanning Raydium pools on-chain with TVL >= {}", minTvl);

            // Raydium V4 Program ID
            PublicKey raydiumProgram = new PublicKey("675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8");

            // Используем метод с DataSize фильтром (Raydium pools = 752 bytes)
            var accounts = rpcClient.getRpcClient().getApi()
                    .getProgramAccounts(raydiumProgram, List.of(), 752);

            return accounts.stream()
                    .map(account -> parseRaydiumPool(account))
                    .filter(pool -> pool != null && pool.getTvl() >= minTvl)
                    .peek(pool -> log.debug("Found Raydium pool: {}", pool.getDisplayName()))
                    .peek(pool -> saveOrUpdatePool(pool).subscribe())
                    .toList();

        } catch (Exception e) {
            log.error("Error scanning Raydium pools on-chain", e);
            return List.of();
        }
    }

    /**
     * Synchronous Orca pool scanning using getProgramAccounts with filters
     */
    private List<Pool> scanOrcaPoolsSync() {
        try {
            log.debug("Scanning Orca Whirlpools on-chain with TVL >= {}", minTvl);

            // Orca Whirlpool Program ID
            PublicKey orcaProgram = new PublicKey("whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc");

            // Whirlpool account size = 653 bytes
            var accounts = rpcClient.getRpcClient().getApi()
                    .getProgramAccounts(orcaProgram, List.of(), 653);

            return accounts.stream()
                    .map(account -> parseOrcaPool(account))
                    .filter(pool -> pool != null && pool.getTvl() >= minTvl)
                    .peek(pool -> log.debug("Found Orca pool: {}", pool.getDisplayName()))
                    .peek(pool -> saveOrUpdatePool(pool).subscribe())
                    .toList();

        } catch (Exception e) {
            log.error("Error scanning Orca pools on-chain", e);
            return List.of();
        }
    }

    /**
     * Scan other DEX programs using simple getProgramAccounts
     */
    private List<Pool> scanJupiterPoolsSync() {
        try {
            log.debug("Scanning other DEX programs");

            List<Pool> allPools = new ArrayList<>();

            // Raydium CLMM
            try {
                PublicKey clmmProgram = new PublicKey("CAMMCzo5YL8w4VFF8KVHrK22GGUQpMAS7M8n78SYq82Y");
                var accounts = rpcClient.getRpcClient().getApi().getProgramAccounts(clmmProgram);

                var pools = accounts.stream()
                        .map(account -> parseGenericPool(account, "CLMM"))
                        .filter(pool -> pool != null && pool.getTvl() >= minTvl)
                        .toList();

                allPools.addAll(pools);
                log.debug("Found {} CLMM pools", pools.size());

            } catch (Exception e) {
                log.warn("Failed to scan CLMM pools: {}", e.getMessage());
            }

            allPools.forEach(pool -> saveOrUpdatePool(pool).subscribe());

            return allPools;

        } catch (Exception e) {
            log.error("Error scanning other DEX programs", e);
            return List.of();
        }
    }

    /**
     * Parse Raydium pool from ProgramAccount
     */
    private Pool parseRaydiumPool(org.p2p.solanaj.rpc.types.ProgramAccount programAccount) {
        try {
            // ProgramAccount содержит account и pubkey
            var accountInfo = programAccount.getAccount();
            byte[] data = accountInfo.getDecodedData();

            if (data.length < 200) return null;

            // Raydium pool layout parsing
            String baseMint = parsePublicKeyFromBytes(data, 40);
            String quoteMint = parsePublicKeyFromBytes(data, 72);

            if (baseMint == null || quoteMint == null) return null;

            long mockTvl = 50_000_000L + (Math.abs(baseMint.hashCode()) % 100_000_000L);

            return Pool.builder()
                    .address(programAccount.getPubkey())
                    .tokenAMint(baseMint)
                    .tokenBMint(quoteMint)
                    .tokenA("token_" + baseMint.substring(0, 4))
                    .tokenB("token_" + quoteMint.substring(0, 4))
                    .tvl(mockTvl)
                    .dexName("RAYDIUM")
                    .feeRate(BigDecimal.valueOf(0.0025))
                    .lastUpdated(LocalDateTime.now())
                    .isActive(true)
                    .build();

        } catch (Exception e) {
            log.debug("Failed to parse Raydium pool: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse Orca pool from ProgramAccount
     */
    private Pool parseOrcaPool(org.p2p.solanaj.rpc.types.ProgramAccount programAccount) {
        try {
            var accountInfo = programAccount.getAccount();
            byte[] data = accountInfo.getDecodedData();

            if (data.length < 200) return null;

            String tokenA = parsePublicKeyFromBytes(data, 101);
            String tokenB = parsePublicKeyFromBytes(data, 133);

            if (tokenA == null || tokenB == null) return null;

            long mockTvl = 30_000_000L + (Math.abs(tokenA.hashCode()) % 150_000_000L);

            return Pool.builder()
                    .address(programAccount.getPubkey())
                    .tokenAMint(tokenA)
                    .tokenBMint(tokenB)
                    .tokenA("token_" + tokenA.substring(0, 4))
                    .tokenB("token_" + tokenB.substring(0, 4))
                    .tvl(mockTvl)
                    .dexName("ORCA")
                    .feeRate(BigDecimal.valueOf(0.003))
                    .lastUpdated(LocalDateTime.now())
                    .isActive(true)
                    .build();

        } catch (Exception e) {
            log.debug("Failed to parse Orca pool: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generic pool parser
     */
    private Pool parseGenericPool(org.p2p.solanaj.rpc.types.ProgramAccount programAccount, String dexName) {
        try {
            var accountInfo = programAccount.getAccount();
            byte[] data = accountInfo.getDecodedData();

            if (data.length < 100) return null;

            String tokenA = findTokenInData(data, 0, 100);
            String tokenB = findTokenInData(data, 32, 132);

            if (tokenA == null || tokenB == null || tokenA.equals(tokenB)) return null;

            long mockTvl = 25_000_000L + (Math.abs(dexName.hashCode()) % 200_000_000L);

            return Pool.builder()
                    .address(programAccount.getPubkey())
                    .tokenAMint(tokenA)
                    .tokenBMint(tokenB)
                    .tokenA("token_" + tokenA.substring(0, 4))
                    .tokenB("token_" + tokenB.substring(0, 4))
                    .tvl(mockTvl)
                    .dexName(dexName)
                    .feeRate(BigDecimal.valueOf(0.0025))
                    .lastUpdated(LocalDateTime.now())
                    .isActive(true)
                    .build();

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse PublicKey from byte array at specific offset
     */
    private String parsePublicKeyFromBytes(byte[] data, int offset) {
        if (data.length < offset + 32) return null;

        byte[] keyBytes = new byte[32];
        System.arraycopy(data, offset, keyBytes, 0, 32);

        return new PublicKey(keyBytes).toBase58();
    }

    /**
     * Heuristic to find token addresses in account data
     */
    private String findTokenInData(byte[] data, int start, int end) {
        try {
            if (data.length < end) return null;

            // Ищем паттерны похожие на PublicKey токенов
            for (int i = start; i <= end - 32; i += 4) {
                byte[] candidate = new byte[32];
                System.arraycopy(data, i, candidate, 0, 32);

                String address = new PublicKey(candidate).toBase58();

                // Проверяем что это похоже на токен (простая эвристика)
                if (isLikelyTokenAddress(address)) {
                    return address;
                }
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }

        return null;
    }

    /**
     * Simple heuristic to check if address looks like a token
     */
    private boolean isLikelyTokenAddress(String address) {
        if (address == null || address.length() != 44) return false;

        // Известные токены или паттерны
        return address.startsWith("So1111") || // WSOL
                address.startsWith("EPjF") ||   // USDC
                address.startsWith("Es9v") ||   // USDT
                (!address.equals("11111111111111111111111111111111") && // Не system program
                        !address.startsWith("1111111111111111111111111111111")); // Не нулевой аккаунт
    }

    /**
     * Create mock pool for testing (replace with real API parsing)
     */
    private Pool createMockPool(String address, String dexName, long tvl, String tokenASymbol, String tokenBSymbol) {
        return Pool.builder()
                .address(address)
                .tokenA("token_" + tokenASymbol.toLowerCase())
                .tokenB("token_" + tokenBSymbol.toLowerCase())
                .tokenAMint(getTokenMint(tokenASymbol))
                .tokenBMint(getTokenMint(tokenBSymbol))
                .tvl(tvl)
                .dexName(dexName)
                .feeRate(BigDecimal.valueOf(0.0025)) // 0.25% default
                .lastUpdated(LocalDateTime.now())
                .isActive(true)
                .build();
    }

    /**
     * Get token mint address by symbol (simplified mapping)
     */
    private String getTokenMint(String symbol) {
        return switch (symbol) {
            case "SOL" -> "So11111111111111111111111111111111111111112";
            case "USDC" -> "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
            case "USDT" -> "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
            case "mSOL" -> "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So";
            case "ETH" -> "7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs";
            case "BTC" -> "9n4nbM75f5Ui33ZbPYXn59EwSgE8CGsHtAeTH5YFeJ9E";
            default -> "11111111111111111111111111111111111111111"; // default
        };
    }

    /**
     * Save or update pool in database
     */
    private Mono<Pool> saveOrUpdatePool(Pool pool) {
        return poolRepository.findByAddress(pool.getAddress())
                .flatMap(existing -> {
                    // Update existing pool
                    existing.setTvl(pool.getTvl());
                    existing.setLastUpdated(LocalDateTime.now());
                    existing.setIsActive(true);
                    return poolRepository.save(existing);
                })
                .switchIfEmpty(
                        // Create new pool
                        poolRepository.save(pool)
                )
                .doOnSuccess(savedPool -> {
                    scannedPoolsCount.incrementAndGet();
                    log.debug("Saved pool: {}", savedPool.getDisplayName());
                })
                .doOnError(error -> log.error("Failed to save pool {}: {}",
                        pool.getAddress(), error.getMessage()));
    }

    /**
     * Get active pools with minimum TVL
     */
    public Flux<Pool> getActivePoolsWithMinTvl() {
        return poolRepository.findActivePoolsWithMinTvl(minTvl);
    }

    /**
     * Get pools for specific token pair
     */
    public Flux<Pool> getPoolsForTokenPair(String tokenA, String tokenB) {
        return poolRepository.findPoolsForTokenPair(tokenA, tokenB);
    }

    /**
     * Get pools containing specific token
     */
    public Flux<Pool> getPoolsWithToken(String tokenMint) {
        return poolRepository.findActivePoolsWithToken(tokenMint);
    }

    /**
     * Manual pool scan trigger
     */
    public CompletableFuture<Void> triggerManualScan() {
        log.info("Manual pool scan triggered");
        return CompletableFuture.runAsync(() -> {
            scanAllDexPools().block();
        }, virtualThreadExecutor);
    }

    /**
     * Cleanup old/inactive pools
     */
    @Scheduled(fixedDelay = 3600000) // Every hour
    @Async("virtualThreadExecutor")
    public void cleanupOldPools() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);

        poolRepository.deactivateOldPools(cutoff)
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.info("Deactivated {} old pools", count);
                    }
                })
                .subscribe();
    }

    /**
     * Get scanning statistics
     */
    public ScanStats getScanStats() {
        return new ScanStats(
                scannedPoolsCount.get(),
                lastScanTime.get(),
                scannerEnabled
        );
    }

    /**
     * Pool scanning statistics
     */
    public record ScanStats(
            int totalPoolsScanned,
            long lastScanTimestamp,
            boolean scannerEnabled
    ) {}
}