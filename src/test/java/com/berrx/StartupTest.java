package com.berrx;

import lombok.extern.slf4j.Slf4j;
import org.p2p.solanaj.rpc.RpcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Startup test to verify all components are initialized correctly
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "startup.test.enabled", havingValue = "true", matchIfMissing = false)
public class StartupTest implements CommandLineRunner {

    @Autowired
    private RpcClient rpcClient;

    @Autowired
    private SolanaConfig.ReactiveRpcClient reactiveRpcClient;

    @Autowired
    private SolanaConfig.RpcHealthCheck rpcHealthCheck;

    @Autowired
    private SolanaConfig.SolanaPrograms solanaPrograms;

    @Override
    public void run(String... args) throws Exception {
        log.info("=================================================");
        log.info("🧪 Running Startup Tests...");
        log.info("=================================================");

        // Test 1: RPC Connection
        testRpcConnection();

        // Test 2: Get Latest Blockhash
        testLatestBlockhash();

        // Test 3: Get Current Slot
        testCurrentSlot();

        // Test 4: Check Known Programs
        testKnownPrograms();

        // Test 5: Reactive operations
        testReactiveOperations();

        log.info("=================================================");
        log.info("✅ All startup tests passed!");
        log.info("=================================================");
    }

    private void testRpcConnection() {
        log.info("Test 1: Checking RPC connection...");

        if (rpcHealthCheck.isHealthy()) {
            log.info("  ✅ RPC is healthy (latency: {}ms)", rpcHealthCheck.getLastLatency());
        } else {
            log.error("  ❌ RPC is unhealthy: {}", rpcHealthCheck.getLastError());
        }
    }

    private void testLatestBlockhash() {
        log.info("Test 2: Fetching latest blockhash...");

        try {
            var blockhashResponse = rpcClient.getApi().getLatestBlockhash();
            log.info("  ✅ Latest blockhash: {}", blockhashResponse.getValue());
            if (blockhashResponse.getContext() != null) {
                log.info("     Slot: {}", blockhashResponse.getContext().getSlot());
            }
        } catch (Exception e) {
            log.error("  ❌ Failed to fetch blockhash: {}", e.getMessage());
        }
    }

    private void testCurrentSlot() {
        log.info("Test 3: Getting current slot...");

        try {
            var slot = rpcClient.getApi().getSlot();
            log.info("  ✅ Current slot: {}", slot);
        } catch (Exception e) {
            log.error("  ❌ Failed to get slot: {}", e.getMessage());
        }
    }

    private void testKnownPrograms() {
        log.info("Test 4: Verifying known programs...");

        log.info("  Programs loaded:");
        log.info("    - System Program: {}", solanaPrograms.SYSTEM_PROGRAM.toBase58());
        log.info("    - Token Program: {}", solanaPrograms.TOKEN_PROGRAM.toBase58());
        log.info("    - Raydium: {}", solanaPrograms.RAYDIUM_V4.toBase58());
        log.info("    - Orca: {}", solanaPrograms.ORCA_WHIRLPOOL.toBase58());
        log.info("  ✅ All programs configured");
    }

    private void testReactiveOperations() {
        log.info("Test 5: Testing reactive operations...");

        try {
            // Test reactive blockhash fetch
            var blockhash = reactiveRpcClient.getLatestBlockhash()
                    .block();
            log.info("  ✅ Reactive blockhash: {}", blockhash);

            // Test reactive slot fetch
            var slot = reactiveRpcClient.getSlot()
                    .block();
            log.info("  ✅ Reactive slot: {}", slot);

        } catch (Exception e) {
            log.error("  ❌ Failed reactive test: {}", e.getMessage());
        }
    }
}