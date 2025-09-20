package com.berrx.controller;

import com.berrx.model.Pool;
import com.berrx.service.PoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;

/**
 * REST API controller for pool monitoring and management.
 */
@Slf4j
@RestController
@RequestMapping("/api/pools")
@RequiredArgsConstructor
public class PoolController {

    private final PoolService poolService;

    /**
     * Get all active pools with minimum TVL
     */
    @GetMapping
    public Flux<Pool> getActivePools() {
        log.debug("Fetching active pools");
        return poolService.getActivePoolsWithMinTvl();
    }

    /**
     * Get pools for specific token pair
     */
    @GetMapping("/pair")
    public Flux<Pool> getPoolsForPair(
            @RequestParam String tokenA,
            @RequestParam String tokenB) {
        log.debug("Fetching pools for pair: {}/{}", tokenA, tokenB);
        return poolService.getPoolsForTokenPair(tokenA, tokenB);
    }

    /**
     * Get pools containing specific token
     */
    @GetMapping("/token/{tokenMint}")
    public Flux<Pool> getPoolsWithToken(@PathVariable String tokenMint) {
        log.debug("Fetching pools with token: {}", tokenMint);
        return poolService.getPoolsWithToken(tokenMint);
    }

    /**
     * Trigger manual pool scan
     */
    @PostMapping("/scan")
    public Mono<ResponseEntity<String>> triggerScan() {
        log.info("Manual pool scan triggered via API");

        CompletableFuture<Void> scanFuture = poolService.triggerManualScan();

        return Mono.fromCompletionStage(scanFuture)
                .then(Mono.just(ResponseEntity.ok("Pool scan initiated successfully")))
                .onErrorReturn(ResponseEntity.internalServerError()
                        .body("Failed to initiate pool scan"));
    }

    /**
     * Get scanning statistics
     */
    @GetMapping("/stats")
    public Mono<ResponseEntity<PoolService.ScanStats>> getScanStats() {
        log.debug("Fetching pool scan statistics");
        return Mono.just(ResponseEntity.ok(poolService.getScanStats()));
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public Mono<ResponseEntity<String>> health() {
        return Mono.just(ResponseEntity.ok("Pool service is running"));
    }
}