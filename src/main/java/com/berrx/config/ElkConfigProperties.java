package com.berrx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties для ELK Stack настроек.
 * Связывает настройки из application.yml с Java объектами.
 */
@Component
@ConfigurationProperties(prefix = "elk")
public class ElkConfigProperties {

    private boolean enabled = false;
    private LogstashConfig logstash = new LogstashConfig();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LogstashConfig getLogstash() {
        return logstash;
    }

    public void setLogstash(LogstashConfig logstash) {
        this.logstash = logstash;
    }

    /**
     * Конфигурация для Logstash подключения
     */
    public static class LogstashConfig {
        private String host = "localhost";
        private int port = 5044;
        private int connectTimeout = 5000;
        private int queueSize = 512;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public int getQueueSize() {
            return queueSize;
        }

        public void setQueueSize(int queueSize) {
            this.queueSize = queueSize;
        }

        @Override
        public String toString() {
            return String.format("LogstashConfig{host='%s', port=%d, connectTimeout=%d, queueSize=%d}",
                    host, port, connectTimeout, queueSize);
        }
    }

    @Override
    public String toString() {
        return String.format("ElkConfigProperties{enabled=%s, logstash=%s}", enabled, logstash);
    }
}