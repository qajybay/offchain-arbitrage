package com.berrx.config;

import lombok.extern.slf4j.Slf4j;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.rpc.RpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PreDestroy;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;

/**
 * Configuration for Solana RPC client using solanaj library.
 * Sets up connection to Helius RPC with Virtual Threads support.
 */
@Slf4j
@Configuration
public class SolanaConfig {

    @Value("${solana.rpc.url}")
    private String rpcUrl;

    @Value("${solana.rpc.api-key:}")
    private String apiKey;

    @Value("${solana.rpc.timeout:30}")
    private int timeoutSeconds;

    @Value("${solana.rpc.max-retries:3}")
    private int maxRetries;

    @Value("${solana.network:mainnet}")
    private String network;

    private ExecutorService solanaVirtualThreadExecutor;

    /**
     * Virtual Thread executor for async operations
     */
    @Bean
    public ExecutorService solanaVirtualThreadExecutor() {
        log.info("Creating Virtual Thread executor");
        var factory = Thread.ofVirtual()
                .name("vthread-solana-", 0)
                .factory();

        this.solanaVirtualThreadExecutor = Executors.newThreadPerTaskExecutor(factory);
        return solanaVirtualThreadExecutor;
    }

    /**
     * Main RPC client for Solana interactions
     */
    @Bean
    public RpcClient rpcClient() {
        String endpoint = rpcUrl;

        // Add API key to URL if provided (for Helius)
        if (!apiKey.isEmpty() && rpcUrl.contains("helius")) {
            endpoint = rpcUrl.contains("?") ?
                    rpcUrl + "&api-key=" + apiKey :
                    rpcUrl + "?api-key=" + apiKey;
        }

        log.info("Initializing Solana RPC client");
        log.info("Network: {}", network);
        log.info("RPC Endpoint: {}", endpoint.replaceAll("api-key=.*", "api-key=***"));

        // Create RPC client
        RpcClient rpcClient = new RpcClient(endpoint);

        // Test connection
        testConnection(rpcClient);

        return rpcClient;
    }

    /**
     * Async RPC wrapper for reactive operations
     */
    @Bean
    public ReactiveRpcClient reactiveRpcClient(RpcClient rpcClient, ExecutorService virtualThreadExecutor) {
        return new ReactiveRpcClient(rpcClient, virtualThreadExecutor);
    }

    /**
     * Well-known program IDs and tokens
     */
    @Bean
    public SolanaPrograms solanaPrograms() {
        return new SolanaPrograms();
    }

    /**
     * Container for Solana program IDs
     */
    public static class SolanaPrograms {
        // System Program
        public final PublicKey SYSTEM_PROGRAM = new PublicKey("11111111111111111111111111111111");

        // Token Program
        public final PublicKey TOKEN_PROGRAM = new PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");

        // Token 2022 Program
        public final PublicKey TOKEN_2022_PROGRAM = new PublicKey("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");

        // Associated Token Program
        public final PublicKey ASSOCIATED_TOKEN_PROGRAM = new PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");

        // Common DEX Programs
        public final PublicKey RAYDIUM_V4 = new PublicKey("675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8");
        public final PublicKey ORCA_WHIRLPOOL = new PublicKey("whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc");
        public final PublicKey JUPITER_AGGREGATOR = new PublicKey("JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4");

        // Common tokens
        public final PublicKey WRAPPED_SOL = new PublicKey("So11111111111111111111111111111111111111111");
        public final PublicKey USDC = new PublicKey("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");
        public final PublicKey USDT = new PublicKey("Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB");

        public PublicKey getProgramId(String dexName) {
            return switch (dexName.toUpperCase()) {
                case "RAYDIUM" -> RAYDIUM_V4;
                case "ORCA" -> ORCA_WHIRLPOOL;
                case "JUPITER" -> JUPITER_AGGREGATOR;
                default -> null;
            };
        }
    }

    /**
     * Reactive wrapper for RPC client
     */
    public static class ReactiveRpcClient {
        private final RpcClient rpcClient;
        private final ExecutorService executor;

        public ReactiveRpcClient(RpcClient rpcClient, ExecutorService executor) {
            this.rpcClient = rpcClient;
            this.executor = executor;
        }

        public Mono<String> getLatestBlockhash() {
            return Mono.fromCallable(() -> {
                var response = rpcClient.getApi().getLatestBlockhash();
                return Optional.of(response.getValue().getBlockhash()).orElse(null);
            }).subscribeOn(Schedulers.fromExecutor(executor));
        }

        public Mono<Long> getBalance(PublicKey publicKey) {
            return Mono.fromCallable(() ->
                    rpcClient.getApi().getBalance(publicKey)
            ).subscribeOn(Schedulers.fromExecutor(executor));
        }

        public Mono<Long> getSlot() {
            return Mono.fromCallable(() ->
                    rpcClient.getApi().getSlot()
            ).subscribeOn(Schedulers.fromExecutor(executor));
        }

        public RpcClient getRpcClient() {
            return rpcClient;
        }
    }

    /**
     * Test RPC connection on startup
     */
    private void testConnection(RpcClient rpcClient) {
        try {
            var startTime = System.currentTimeMillis();
            var slot = rpcClient.getApi().getSlot();
            var latency = System.currentTimeMillis() - startTime;

            log.info("✅ RPC connection successful");
            log.info("   Current slot: {}", slot);
            log.info("   Latency: {}ms", latency);
        } catch (Exception e) {
            log.error("❌ RPC connection test failed: {}", e.getMessage());
            // Don't fail startup, just log the error
        }
    }

    /**
     * RPC Health Check bean for monitoring
     */
    @Bean
    public RpcHealthCheck rpcHealthCheck(RpcClient rpcClient) {
        return new RpcHealthCheck(rpcClient);
    }

    /**
     * Health check for RPC connection
     */
    public static class RpcHealthCheck {
        private final RpcClient rpcClient;
        private volatile boolean healthy = false;
        private volatile long lastCheckTime = 0;
        private volatile String lastError = null;
        private volatile long lastLatency = 0;

        public RpcHealthCheck(RpcClient rpcClient) {
            this.rpcClient = rpcClient;
            checkHealth();
        }

        public void checkHealth() {
            try {
                var startTime = System.currentTimeMillis();
                var slot = rpcClient.getApi().getSlot();
                lastLatency = System.currentTimeMillis() - startTime;

                healthy = slot > 0;
                lastCheckTime = System.currentTimeMillis();
                lastError = null;

                if (healthy) {
                    log.debug("RPC health check passed (latency: {}ms, slot: {})", lastLatency, slot);
                } else {
                    log.warn("RPC health check: invalid slot response");
                }
            } catch (Exception e) {
                healthy = false;
                lastError = e.getMessage();
                lastCheckTime = System.currentTimeMillis();
                log.error("RPC health check failed: {}", e.getMessage());
            }
        }

        public boolean isHealthy() {
            // Re-check if last check was more than 30 seconds ago
            if (System.currentTimeMillis() - lastCheckTime > 30000) {
                checkHealth();
            }
            return healthy;
        }

        public String getLastError() {
            return lastError;
        }

        public long getLastLatency() {
            return lastLatency;
        }
    }

    @PreDestroy
    public void cleanup() {
        if (solanaVirtualThreadExecutor != null && !solanaVirtualThreadExecutor.isShutdown()) {
            log.info("Shutting down Virtual Thread executor");
            solanaVirtualThreadExecutor.shutdown();
        }
    }
}