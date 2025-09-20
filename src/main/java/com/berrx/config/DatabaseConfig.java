package com.berrx.config;

import io.r2dbc.spi.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.core.io.ClassPathResource;

/**
 * R2DBC PostgreSQL configuration with connection pooling.
 * Configures reactive database access with Virtual Threads support.
 */
@Slf4j
@Configuration
@EnableR2dbcRepositories(basePackages = "com.berrx.repository")
@EnableR2dbcAuditing
@EnableTransactionManagement
@EnableConfigurationProperties(R2dbcProperties.class)
public class DatabaseConfig extends AbstractR2dbcConfiguration {

    private final ConnectionFactory connectionFactory;

    public DatabaseConfig(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        log.info("Initializing R2DBC PostgreSQL configuration");
    }

    @Override
    public ConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    /**
     * Configure transaction manager for reactive transactions
     */
    @Bean
    public ReactiveTransactionManager transactionManager(ConnectionFactory connectionFactory) {
        return new R2dbcTransactionManager(connectionFactory);
    }

    /**
     * Initialize database schema on startup if schema.sql exists
     */
    @Bean
    public ConnectionFactoryInitializer initializer(ConnectionFactory connectionFactory) {
        var initializer = new ConnectionFactoryInitializer();
        initializer.setConnectionFactory(connectionFactory);

        try {
            var schemaResource = new ClassPathResource("schema.sql");
            if (schemaResource.exists()) {
                var populator = new ResourceDatabasePopulator(schemaResource);
                initializer.setDatabasePopulator(populator);
                log.info("Database schema will be initialized from schema.sql");
            }
        } catch (Exception e) {
            log.debug("No schema.sql found, skipping database initialization");
        }

        return initializer;
    }
}