package com.berrx.repository;

import com.berrx.model.Pool;
import com.berrx.model.TradePair;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Repository for Pool entities with reactive R2DBC operations.
 */
@Repository
public interface PoolRepository extends ReactiveCrudRepository<Pool, Long> {

    /**
     * Find pool by address
     */
    Mono<Pool> findByAddress(String address);

    /**
     * Find all active pools with TVL above threshold
     */
    @Query("SELECT * FROM pools WHERE is_active = true AND tvl >= :minTvl ORDER BY tvl DESC")
    Flux<Pool> findActivePoolsWithMinTvl(Long minTvl);

    /**
     * Find pools by DEX name
     */
    Flux<Pool> findByDexNameAndIsActive(String dexName, Boolean isActive);

    /**
     * Find pools containing specific token
     */
    @Query("SELECT * FROM pools WHERE is_active = true AND (token_a_mint = :tokenMint OR token_b_mint = :tokenMint)")
    Flux<Pool> findActivePoolsWithToken(String tokenMint);

    /**
     * Find pools for a specific token pair (both directions)
     */
    @Query("""
        SELECT * FROM pools 
        WHERE is_active = true 
        AND ((token_a_mint = :tokenA AND token_b_mint = :tokenB) 
             OR (token_a_mint = :tokenB AND token_b_mint = :tokenA))
        ORDER BY tvl DESC
        """)
    Flux<Pool> findPoolsForTokenPair(String tokenA, String tokenB);

    /**
     * Count active pools
     */
    @Query("SELECT COUNT(*) FROM pools WHERE is_active = true")
    Mono<Long> countActivePools();

    /**
     * Find pools that need updating (older than specified minutes)
     */
    @Query("SELECT * FROM pools WHERE is_active = true AND last_updated < :cutoffTime")
    Flux<Pool> findPoolsNeedingUpdate(LocalDateTime cutoffTime);

    /**
     * Update pool TVL and timestamp
     */
    @Query("UPDATE pools SET tvl = :tvl, last_updated = :lastUpdated WHERE address = :address")
    Mono<Integer> updatePoolTvl(String address, Long tvl, LocalDateTime lastUpdated);

    /**
     * Deactivate pools not seen recently
     */
    @Query("UPDATE pools SET is_active = false WHERE last_updated < :cutoffTime")
    Mono<Integer> deactivateOldPools(LocalDateTime cutoffTime);

    /**
     * Get top pools by TVL for each DEX
     */
    @Query("""
        SELECT DISTINCT ON (dex_name) *
        FROM pools 
        WHERE is_active = true AND tvl >= :minTvl
        ORDER BY dex_name, tvl DESC
        """)
    Flux<Pool> findTopPoolsByDex(Long minTvl);
}