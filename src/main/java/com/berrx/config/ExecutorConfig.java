package com.berrx.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Configures Virtual Threads for maximum performance.
 * Uses Project Loom's virtual threads for I/O-intensive operations.
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
public class ExecutorConfig implements AsyncConfigurer {

    @Value("${arbitrage.scanner.virtual-threads-enabled:true}")
    private boolean virtualThreadsEnabled;

    /**
     * Primary executor using Virtual Threads for I/O operations
     */
    @Bean(name = "virtualThreadExecutor")
    @Primary
    @ConditionalOnProperty(
            name = "arbitrage.scanner.virtual-threads-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public ExecutorService virtualThreadExecutor() {
        log.info("Initializing Virtual Thread Executor");

        ThreadFactory factory = Thread.ofVirtual()
                .name("vthread-arbitrage-", 0)
                .factory();

        return Executors.newThreadPerTaskExecutor(factory);
    }

    /**
     * Fallback platform thread executor if virtual threads are disabled
     */
    @Bean(name = "platformThreadExecutor")
    @ConditionalOnProperty(
            name = "arbitrage.scanner.virtual-threads-enabled",
            havingValue = "false"
    )
    public Executor platformThreadExecutor() {
        log.info("Virtual threads disabled, using platform thread executor");

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("arbitrage-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();

        return executor;
    }

    /**
     * Dedicated executor for blockchain RPC calls
     */
    @Bean(name = "rpcExecutor")
    public ExecutorService rpcExecutor() {
        log.info("Initializing RPC Virtual Thread Executor");

        ThreadFactory factory = Thread.ofVirtual()
                .name("vthread-rpc-", 0)
                .factory();

        return Executors.newThreadPerTaskExecutor(factory);
    }

    /**
     * Dedicated executor for database operations
     */
    @Bean(name = "dbExecutor")
    public ExecutorService dbExecutor() {
        log.info("Initializing Database Virtual Thread Executor");

        ThreadFactory factory = Thread.ofVirtual()
                .name("vthread-db-", 0)
                .factory();

        return Executors.newThreadPerTaskExecutor(factory);
    }

    @Override
    public Executor getAsyncExecutor() {
        if (virtualThreadsEnabled) {
            return virtualThreadExecutor();
        } else {
            return platformThreadExecutor();
        }
    }
}