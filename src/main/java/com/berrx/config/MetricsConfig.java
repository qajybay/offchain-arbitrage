package com.berrx.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus metrics configuration for monitoring arbitrage bot performance.
 */
@Slf4j
@Configuration
public class MetricsConfig {

    // Atomic counters for metrics
    private final AtomicInteger activePoolsCount = new AtomicInteger(0);
    private final AtomicLong totalOpportunitiesFound = new AtomicLong(0);
    private final AtomicInteger pendingExecutions = new AtomicInteger(0);

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                .commonTags("application", "solana-arbitrage-bot");
    }

    /**
     * Configure arbitrage-specific metrics
     */
    @Bean
    public ArbitrageMetrics arbitrageMetrics(MeterRegistry registry) {
        log.info("Initializing Prometheus metrics");

        // Gauge for active pools being monitored
        Gauge.builder("arbitrage.pools.active", activePoolsCount, AtomicInteger::get)
                .description("Number of active pools being monitored")
                .register(registry);

        // Gauge for pending executions
        Gauge.builder("arbitrage.executions.pending", pendingExecutions, AtomicInteger::get)
                .description("Number of pending arbitrage executions")
                .register(registry);

        // Counter for total opportunities found
        Counter.builder("arbitrage.opportunities.total")
                .description("Total arbitrage opportunities found")
                .register(registry);

        // Timer for RPC call latency
        Timer.builder("arbitrage.rpc.duration")
                .description("RPC call duration")
                .register(registry);

        // Timer for arbitrage calculation
        Timer.builder("arbitrage.calculation.duration")
                .description("Arbitrage calculation duration")
                .register(registry);

        return new ArbitrageMetrics(registry, activePoolsCount, totalOpportunitiesFound, pendingExecutions);
    }

    /**
     * Helper class to manage arbitrage metrics
     */
    public static class ArbitrageMetrics {
        private final MeterRegistry registry;
        private final AtomicInteger activePoolsCount;
        private final AtomicLong totalOpportunitiesFound;
        private final AtomicInteger pendingExecutions;

        private final Counter opportunitiesCounter;
        private final Counter executionsCounter;
        private final Counter profitCounter;

        public ArbitrageMetrics(MeterRegistry registry,
                                AtomicInteger activePoolsCount,
                                AtomicLong totalOpportunitiesFound,
                                AtomicInteger pendingExecutions) {
            this.registry = registry;
            this.activePoolsCount = activePoolsCount;
            this.totalOpportunitiesFound = totalOpportunitiesFound;
            this.pendingExecutions = pendingExecutions;

            // Initialize counters
            this.opportunitiesCounter = Counter.builder("arbitrage.opportunities.found")
                    .description("Arbitrage opportunities found")
                    .tag("type", "all")
                    .register(registry);

            this.executionsCounter = Counter.builder("arbitrage.executions.completed")
                    .description("Completed arbitrage executions")
                    .register(registry);

            this.profitCounter = Counter.builder("arbitrage.profit.total")
                    .description("Total profit in SOL")
                    .baseUnit("SOL")
                    .register(registry);
        }

        public void updateActivePools(int count) {
            activePoolsCount.set(count);
        }

        public void incrementOpportunities(String type) {
            opportunitiesCounter.increment();
            totalOpportunitiesFound.incrementAndGet();

            // Type-specific counter
            Counter.builder("arbitrage.opportunities.by_type")
                    .tag("type", type)
                    .register(registry)
                    .increment();
        }

        public void incrementExecutions() {
            executionsCounter.increment();
        }

        public void recordProfit(double profitInSol) {
            profitCounter.increment(profitInSol);
        }

        public void updatePendingExecutions(int count) {
            pendingExecutions.set(count);
        }

        public Timer.Sample startTimer() {
            return Timer.start(registry);
        }

        public void recordRpcLatency(Timer.Sample sample) {
            sample.stop(Timer.builder("arbitrage.rpc.latency")
                    .description("RPC call latency")
                    .register(registry));
        }
    }
}