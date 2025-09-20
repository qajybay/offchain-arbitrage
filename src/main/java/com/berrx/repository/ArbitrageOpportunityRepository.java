package com.berrx.repository;

import com.berrx.model.ArbitrageOpportunity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Repository for ArbitrageOpportunity entities.
 */
@Repository
public interface ArbitrageOpportunityRepository extends ReactiveCrudRepository<ArbitrageOpportunity, Long> {

    /**
     * Find active (non-expired, non-executed) opportunities
     */
    @Query("""
        SELECT * FROM arbitrage_opportunities 
        WHERE executed = false AND expires_at > NOW()
        ORDER BY priority_score DESC
        """)
    Flux<ArbitrageOpportunity> findActiveOpportunities();

    /**
     * Find opportunities by type
     */
    @Query("""
        SELECT * FROM arbitrage_opportunities 
        WHERE opportunity_type = :type AND executed = false AND expires_at > NOW()
        ORDER BY profit_percentage DESC
        """)
    Flux<ArbitrageOpportunity> findActiveOpportunitiesByType(String type);

    /**
     * Find high-profit opportunities (above threshold)
     */
    @Query("""
        SELECT * FROM arbitrage_opportunities 
        WHERE profit_percentage >= :minProfit 
        AND executed = false AND expires_at > NOW()
        ORDER BY priority_score DESC
        LIMIT :limit
        """)
    Flux<ArbitrageOpportunity> findHighProfitOpportunities(Double minProfit, Integer limit);

    /**
     * Count active opportunities by type
     */
    @Query("""
        SELECT COUNT(*) FROM arbitrage_opportunities 
        WHERE opportunity_type = :type AND executed = false AND expires_at > NOW()
        """)
    Mono<Long> countActiveOpportunitiesByType(String type);

    /**
     * Find executed opportunities for analysis
     */
    @Query("""
        SELECT * FROM arbitrage_opportunities 
        WHERE executed = true 
        ORDER BY created_at DESC 
        LIMIT :limit
        """)
    Flux<ArbitrageOpportunity> findRecentExecutedOpportunities(Integer limit);

    /**
     * Clean up expired opportunities
     */
    @Query("DELETE FROM arbitrage_opportunities WHERE expires_at < :cutoffTime AND executed = false")
    Mono<Integer> deleteExpiredOpportunities(LocalDateTime cutoffTime);

    /**
     * Update execution status
     */
    @Query("""
        UPDATE arbitrage_opportunities 
        SET executed = true, execution_tx = :txSignature, execution_status = :status, execution_profit_actual = :actualProfit
        WHERE id = :id
        """)
    Mono<Integer> markExecuted(Long id, String txSignature, String status, Double actualProfit);

    /**
     * Find opportunities involving specific pools
     */
    @Query("""
        SELECT * FROM arbitrage_opportunities 
        WHERE pools_involved LIKE :poolPattern 
        AND executed = false AND expires_at > NOW()
        """)
    Flux<ArbitrageOpportunity> findOpportunitiesWithPool(String poolPattern);

    /**
     * Get opportunity statistics
     */
    @Query("""
        SELECT 
            opportunity_type,
            COUNT(*) as count,
            AVG(profit_percentage) as avg_profit,
            MAX(profit_percentage) as max_profit
        FROM arbitrage_opportunities 
        WHERE created_at >= :since
        GROUP BY opportunity_type
        """)
    Flux<Object[]> getOpportunityStatistics(LocalDateTime since);
}
