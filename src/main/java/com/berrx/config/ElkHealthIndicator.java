package com.berrx.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Health indicator для ELK Stack подключения.
 * Проверяет доступность Logstash только если ELK включен.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "elk.enabled", havingValue = "true")
public class ElkHealthIndicator implements HealthIndicator {

    private final ElkConfigProperties elkConfig;
    private volatile boolean lastCheckResult = false;
    private volatile long lastCheckTime = 0;
    private volatile String lastError = null;

    public ElkHealthIndicator(ElkConfigProperties elkConfig) {
        this.elkConfig = elkConfig;
        log.info("ELK Health Indicator initialized for {}:{}",
                elkConfig.getLogstash().getHost(),
                elkConfig.getLogstash().getPort());
    }

    @Override
    public Health health() {
        // Проверяем каждые 30 секунд
        if (System.currentTimeMillis() - lastCheckTime > 30000) {
            checkLogstashConnection();
        }

        if (lastCheckResult) {
            return Health.up()
                    .withDetail("logstash.host", elkConfig.getLogstash().getHost())
                    .withDetail("logstash.port", elkConfig.getLogstash().getPort())
                    .withDetail("lastCheck", new java.util.Date(lastCheckTime))
                    .build();
        } else {
            return Health.down()
                    .withDetail("logstash.host", elkConfig.getLogstash().getHost())
                    .withDetail("logstash.port", elkConfig.getLogstash().getPort())
                    .withDetail("error", lastError)
                    .withDetail("lastCheck", new java.util.Date(lastCheckTime))
                    .build();
        }
    }

    private void checkLogstashConnection() {
        try {
            String host = elkConfig.getLogstash().getHost();
            int port = elkConfig.getLogstash().getPort();
            int timeout = elkConfig.getLogstash().getConnectTimeout();

            try (Socket socket = new Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), timeout);
                lastCheckResult = true;
                lastError = null;
                log.debug("Logstash connection check successful");
            }
        } catch (SocketTimeoutException e) {
            lastCheckResult = false;
            lastError = "Connection timeout to Logstash";
            log.warn("Logstash connection timeout: {}", e.getMessage());
        } catch (IOException e) {
            lastCheckResult = false;
            lastError = "Cannot connect to Logstash: " + e.getMessage();
            log.warn("Logstash connection failed: {}", e.getMessage());
        } catch (Exception e) {
            lastCheckResult = false;
            lastError = "Unexpected error: " + e.getMessage();
            log.error("Unexpected error during Logstash health check", e);
        } finally {
            lastCheckTime = System.currentTimeMillis();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // Проверяем подключение при старте приложения
        checkLogstashConnection();

        if (lastCheckResult) {
            log.info("✅ ELK Stack connection established successfully");
        } else {
            log.warn("⚠️  ELK Stack connection failed: {}", lastError);
            log.warn("   Logs will be written only to console and file");
        }
    }
}