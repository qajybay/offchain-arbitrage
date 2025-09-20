
package com.berrx.repository;

import com.berrx.model.TradePair;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Repository for TradePair entities.
 */
@Repository
public interface TradePairRepository extends ReactiveCrudRepository<TradePair, Long> {

    /**
     * Find trade pair by tokens (both directions)
     */
    @Query("""
        SELECT * FROM trade_pairs 
        WHERE (token_a = :tokenA AND token_b = :tokenB) 
           OR (token_a = :tokenB AND token_b = :tokenA)
        """)
    Mono<TradePair> findByTokenPair(String tokenA, String tokenB);

    /**
     * Find pairs with sufficient liquidity for arbitrage
     */
    @Query("""
        SELECT * FROM trade_pairs 
        WHERE pool_count >= :minPools AND avg_tvl >= :minTvl
        ORDER BY avg_tvl DESC
        """)
    Flux<TradePair> findPairsWithSufficientLiquidity(Integer minPools, Long minTvl);

    /**
     * Find pairs containing specific token
     */
    @Query("SELECT * FROM trade_pairs WHERE token_a = :tokenMint OR token_b = :tokenMint")
    Flux<TradePair> findPairsWithToken(String tokenMint);

    /**
     * Find pairs suitable for triangle arbitrage
     */
    @Query("""
        SELECT * FROM trade_pairs 
        WHERE pool_count >= 2 AND avg_tvl >= :minTvl AND last_price IS NOT NULL
        ORDER BY avg_tvl DESC, pool_count DESC
        """)
    Flux<TradePair> findTriangleArbitrageCandidates(Long minTvl);

    /**
     * Find stale pairs that need updating
     */
    @Query("SELECT * FROM trade_pairs WHERE last_updated < :cutoffTime")
    Flux<TradePair> findStalePairs(LocalDateTime cutoffTime);

    /**
     * Update or insert trade pair data
     */
    @Query("""
        INSERT INTO trade_pairs (token_a, token_b, pool_count, avg_tvl, best_fee_rate, last_price, last_updated) 
        VALUES (:tokenA, :tokenB, :poolCount, :avgTvl, :bestFeeRate, :lastPrice, :lastUpdated)
        ON CONFLICT (token_a, token_b) 
        DO UPDATE SET 
            pool_count = :poolCount,
            avg_tvl = :avgTvl,
            best_fee_rate = :bestFeeRate,
            last_price = :lastPrice,
            last_updated = :lastUpdated
        """)
    Mono<Integer> upsertTradePair(String tokenA, String tokenB, Integer poolCount,
                                  Long avgTvl, Double bestFeeRate, Double lastPrice, LocalDateTime lastUpdated);

    /**
     * Get top trading pairs by volume/liquidity
     */
    @Query("""
        SELECT * FROM trade_pairs 
        WHERE avg_tvl IS NOT NULL 
        ORDER BY avg_tvl DESC 
        LIMIT :limit
        """)
    Flux<TradePair> findTopTradingPairs(Integer limit);

    /**
     * Count total trading pairs
     */
    Mono<Long> count();

    /**
     * Find recently updated pairs
     */
    @Query("""
        SELECT * FROM trade_pairs 
        WHERE last_updated >= :since 
        ORDER BY last_updated DESC
        """)
    Flux<TradePair> findRecentlyUpdated(LocalDateTime since);
}