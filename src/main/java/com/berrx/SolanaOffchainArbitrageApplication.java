package com.berrx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.core.publisher.Hooks;

/**
 * Main application class for Solana Offchain Arbitrage Bot.
 * Utilizes Virtual Threads for high-performance I/O operations.
 */
@Slf4j
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties
public class SolanaOffchainArbitrageApplication {

	public static void main(String[] args) {
		// Enable reactor debugging in development
		if (System.getenv("ENVIRONMENT") == null ||
				"development".equals(System.getenv("ENVIRONMENT"))) {
			Hooks.onOperatorDebug();
		}

		// Configure system properties for Virtual Threads
		System.setProperty("jdk.virtualThreadScheduler.parallelism", "1000");
		System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "10000");

		SpringApplication.run(SolanaOffchainArbitrageApplication.class, args);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		log.info("=================================================");
		log.info("🚀 Solana Arbitrage Bot Started Successfully");
		log.info("=================================================");
		log.info("Virtual Threads: {}", Thread.currentThread().isVirtual() ? "Enabled" : "Platform Threads");
		log.info("Java Version: {}", System.getProperty("java.version"));
		log.info("Active Profile: {}", System.getenv("ENVIRONMENT") != null ?
				System.getenv("ENVIRONMENT") : "default");

		log.info("=================================================");
	}
}