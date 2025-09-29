package com.berrx.repository;

import com.berrx.model.Pool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Pool entities with JPA.
 * Supports filtering by DEX, TVL and trading pair search.
 *
 * UPDATED: Added missing methods required by ArbitrageDetectorService and DexScreenerService
 */
@Repository
public interface PoolRepository extends JpaRepository<Pool, Long> {

    /**
     * Find pool by address
     */
    Optional<Pool> findByAddress(String address);

    /**
     * Find active pools with minimum TVL
     */
    @Query("SELECT p FROM Pool p WHERE p.isActive = true AND p.tvlUsd >= :minTvl ORDER BY p.tvlUsd DESC")
    List<Pool> findActivePoolsWithMinTvl(@Param("minTvl") Double minTvl);

    /**
     * ADDED: Find pools by active status and TVL threshold (required by ArbitrageDetectorService)
     */
    @Query("SELECT p FROM Pool p WHERE p.isActive = true AND p.tvlUsd > :minTvl ORDER BY p.tvlUsd DESC")
    List<Pool> findByIsActiveTrueAndTvlUsdGreaterThan(@Param("minTvl") Double minTvl);

    /**
     * ADDED: Alternative method name that matches Spring Data naming convention
     */
    default List<Pool> findByIsActiveTrueAndTvlUsdGreaterThanBackup(Double minTvl) {
        return findActivePoolsWithMinTvl(minTvl);
    }

    /**
     * ADDED: Find pools updated before specific time (required by DexScreenerService)
     */
    @Query("SELECT p FROM Pool p WHERE p.isActive = true AND p.lastUpdated < :cutoffTime")
    List<Pool> findByIsActiveTrueAndLastUpdatedBefore(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Find pools by DEX and status
     */
    List<Pool> findByDexNameAndIsActiveOrderByTvlUsdDesc(String dexName, Boolean isActive);

    /**
     * Find pools by specific DEX list
     */
    @Query("SELECT p FROM Pool p WHERE p.dexName IN :dexNames AND p.isActive = true AND p.tvlUsd >= :minTvl ORDER BY p.tvlUsd DESC")
    List<Pool> findByDexNamesAndMinTvl(@Param("dexNames") List<String> dexNames, @Param("minTvl") Double minTvl);

    /**
     * Find active pools with token
     */
    @Query("SELECT p FROM Pool p WHERE p.isActive = true AND (p.tokenAMint = :tokenMint OR p.tokenBMint = :tokenMint) ORDER BY p.tvlUsd DESC")
    List<Pool> findActivePoolsWithToken(@Param("tokenMint") String tokenMint);

    /**
     * Find pools for trading pair (both directions)
     */
    @Query("""
        SELECT p FROM Pool p 
        WHERE p.isActive = true 
        AND ((p.tokenAMint = :tokenA AND p.tokenBMint = :tokenB) 
             OR (p.tokenAMint = :tokenB AND p.tokenBMint = :tokenA))
        ORDER BY p.tvlUsd DESC
        """)
    List<Pool> findPoolsForTokenPair(@Param("tokenA") String tokenA, @Param("tokenB") String tokenB);

    /**
     * Find top pools by each DEX
     */
    @Query(value = """
        SELECT DISTINCT ON (p.dex_name) p.*
        FROM pools p 
        WHERE p.is_active = true AND p.tvl_usd >= :minTvl
        ORDER BY p.dex_name, p.tvl_usd DESC
        """, nativeQuery = true)
    List<Pool> findTopPoolsByDex(@Param("minTvl") Double minTvl);

    /**
     * Count active pools
     */
    @Query("SELECT COUNT(*) FROM Pool p WHERE p.isActive = true")
    Long countActivePools();

    /**
     * Count pools by DEX
     */
    @Query("SELECT COUNT(*) FROM Pool p WHERE p.dexName = :dexName AND p.isActive = true")
    Long countActivePoolsByDex(@Param("dexName") String dexName);

    /**
     * Find stale active pools
     */
    @Query("SELECT p FROM Pool p WHERE p.isActive = true AND p.lastUpdated < :cutoffTime")
    List<Pool> findStaleActivePools(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Deactivate old pools
     */
    @Modifying
    @Transactional
    @Query("UPDATE Pool p SET p.isActive = false WHERE p.lastUpdated < :cutoffTime")
    int deactivateOldPools(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Update pool TVL
     */
    @Modifying
    @Transactional
    @Query("UPDATE Pool p SET p.tvlUsd = :tvlUsd, p.lastUpdated = :lastUpdated WHERE p.address = :address")
    int updatePoolTvl(@Param("address") String address, @Param("tvlUsd") Double tvlUsd, @Param("lastUpdated") LocalDateTime lastUpdated);

    /**
     * Find duplicate pools (by tokens and DEX)
     */
    @Query("""
        SELECT p FROM Pool p 
        WHERE p.dexName = :dexName 
        AND ((p.tokenAMint = :tokenA AND p.tokenBMint = :tokenB) 
             OR (p.tokenAMint = :tokenB AND p.tokenBMint = :tokenA))
        AND p.address != :excludeAddress
        """)
    List<Pool> findDuplicatePools(@Param("dexName") String dexName,
                                  @Param("tokenA") String tokenA,
                                  @Param("tokenB") String tokenB,
                                  @Param("excludeAddress") String excludeAddress);

    /**
     * Find pools by list of addresses
     */
    @Query("SELECT p FROM Pool p WHERE p.address IN :addresses")
    List<Pool> findByAddresses(@Param("addresses") List<String> addresses);

    /**
     * Find pools with metadata (for data quality check)
     */
    @Query("SELECT p FROM Pool p WHERE p.tokenASymbol IS NOT NULL AND p.tokenBSymbol IS NOT NULL AND p.isActive = true")
    List<Pool> findPoolsWithMetadata();

    /**
     * Find pools without metadata (for updates)
     */
    @Query("SELECT p FROM Pool p WHERE (p.tokenASymbol IS NULL OR p.tokenBSymbol IS NULL) AND p.isActive = true")
    List<Pool> findPoolsWithoutMetadata();

    /**
     * DEX statistics
     */
    @Query("""
        SELECT p.dexName, COUNT(*), AVG(p.tvlUsd), SUM(p.tvlUsd)
        FROM Pool p 
        WHERE p.isActive = true 
        GROUP BY p.dexName 
        ORDER BY COUNT(*) DESC
        """)
    List<Object[]> getDexStatistics();

    /**
     * Find recently updated pools
     */
    @Query("SELECT p FROM Pool p WHERE p.lastUpdated >= :since ORDER BY p.lastUpdated DESC")
    List<Pool> findRecentlyUpdated(@Param("since") LocalDateTime since);

    /**
     * Find pools by data source
     */
    List<Pool> findBySourceAndIsActiveOrderByTvlUsdDesc(String source, Boolean isActive);

    // =================== ADDED METHODS FOR ARBITRAGE DETECTION ===================

    /**
     * FIXED: Find pools with current prices for arbitrage detection (using persistent fields)
     */
    @Query("""
        SELECT p FROM Pool p 
        WHERE p.isActive = true 
        AND p.currentPriceA IS NOT NULL 
        AND p.currentPriceB IS NOT NULL 
        AND p.currentPriceA > 0
        AND p.currentPriceB > 0
        AND p.tvlUsd >= :minTvl
        AND p.priceUpdatedAt >= :minUpdateTime
        ORDER BY p.tvlUsd DESC
        """)
    List<Pool> findActivePoolsWithCurrentPrices(@Param("minTvl") Double minTvl,
                                                @Param("minUpdateTime") LocalDateTime minUpdateTime);

    /**
     * ADDED: Find pools by token pair for arbitrage detection
     */
    @Query("""
        SELECT p FROM Pool p 
        WHERE p.isActive = true 
        AND p.currentPriceA IS NOT NULL 
        AND p.currentPriceB IS NOT NULL
        AND ((p.tokenAMint = :tokenA AND p.tokenBMint = :tokenB) 
             OR (p.tokenAMint = :tokenB AND p.tokenBMint = :tokenA))
        ORDER BY p.tvlUsd DESC
        """)
    List<Pool> findPoolsForArbitrageByTokenPair(@Param("tokenA") String tokenA, @Param("tokenB") String tokenB);

    /**
     * ADDED: Find pools that need price updates
     */
    @Query("""
        SELECT p FROM Pool p 
        WHERE p.isActive = true 
        AND p.tvlUsd >= :minTvl
        AND (p.priceUpdatedAt IS NULL OR p.priceUpdatedAt < :stalePriceTime)
        ORDER BY p.tvlUsd DESC
        """)
    List<Pool> findPoolsNeedingPriceUpdate(@Param("minTvl") Double minTvl,
                                           @Param("stalePriceTime") LocalDateTime stalePriceTime);

    /**
     * ADDED: Update pool prices efficiently
     */
    @Modifying
    @Transactional
    @Query("""
        UPDATE Pool p 
        SET p.currentPriceA = :priceA, 
            p.currentPriceB = :priceB, 
            p.priceUpdatedAt = :updateTime 
        WHERE p.address = :address
        """)
    int updatePoolPrices(@Param("address") String address,
                         @Param("priceA") Double priceA,
                         @Param("priceB") Double priceB,
                         @Param("updateTime") LocalDateTime updateTime);

    /**
     * ADDED: Batch update pool activity status
     */
    @Modifying
    @Transactional
    @Query("UPDATE Pool p SET p.isActive = :isActive WHERE p.address IN :addresses")
    int updatePoolsActiveStatus(@Param("addresses") List<String> addresses, @Param("isActive") Boolean isActive);

    /**
     * ADDED: Find pools with fresh prices for arbitrage (convenience method)
     */
    default List<Pool> findActivePoolsWithFreshPrices(Double minTvl, int maxAgeMinutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(maxAgeMinutes);
        return findActivePoolsWithCurrentPrices(minTvl, cutoff);
    }

    /**
     * ADDED: Find pools by multiple DEX names (convenience method)
     */
    @Query("SELECT p FROM Pool p WHERE p.dexName IN :dexNames AND p.isActive = true ORDER BY p.tvlUsd DESC")
    List<Pool> findByDexNamesAndIsActive(@Param("dexNames") List<String> dexNames);

    /**
     * ADDED: Count pools by multiple criteria
     */
    @Query("""
        SELECT COUNT(*) FROM Pool p 
        WHERE p.isActive = true 
        AND p.dexName IN :dexNames 
        AND p.tvlUsd >= :minTvl
        """)
    Long countActivePoolsByCriteria(@Param("dexNames") List<String> dexNames, @Param("minTvl") Double minTvl);

    // =================== ADVANCED QUERIES FOR ARBITRAGE ===================

    /**
     * ADDED: Find arbitrage candidates (same token pairs across different DEX)
     */
    @Query(value = """
        SELECT p1.* FROM pools p1
        INNER JOIN pools p2 ON (
            (p1.token_a_mint = p2.token_a_mint AND p1.token_b_mint = p2.token_b_mint)
            OR (p1.token_a_mint = p2.token_b_mint AND p1.token_b_mint = p2.token_a_mint)
        )
        WHERE p1.is_active = true 
        AND p2.is_active = true
        AND p1.dex_name != p2.dex_name
        AND p1.current_price_a IS NOT NULL
        AND p1.current_price_b IS NOT NULL
        AND p2.current_price_a IS NOT NULL  
        AND p2.current_price_b IS NOT NULL
        AND p1.tvl_usd >= :minTvl
        AND p2.tvl_usd >= :minTvl
        ORDER BY p1.tvl_usd DESC
        """, nativeQuery = true)
    List<Pool> findArbitrageCandidates(@Param("minTvl") Double minTvl);

    /**
     * ADDED: Find top liquid pools per DEX for arbitrage
     */
    @Query(value = """
        SELECT * FROM (
            SELECT *, ROW_NUMBER() OVER (PARTITION BY dex_name ORDER BY tvl_usd DESC) as rn
            FROM pools 
            WHERE is_active = true 
            AND current_price_a IS NOT NULL 
            AND current_price_b IS NOT NULL
            AND tvl_usd >= :minTvl
        ) ranked 
        WHERE rn <= :topN
        ORDER BY dex_name, tvl_usd DESC
        """, nativeQuery = true)
    List<Pool> findTopPoolsPerDexForArbitrage(@Param("minTvl") Double minTvl, @Param("topN") Integer topN);

    /**
     * ADDED: Get pool statistics for monitoring
     */
    @Query("""
        SELECT 
            p.dexName as dexName,
            COUNT(*) as totalPools,
            COUNT(CASE WHEN p.currentPriceA IS NOT NULL THEN 1 END) as poolsWithPrices,
            AVG(p.tvlUsd) as averageTvl,
            MAX(p.priceUpdatedAt) as lastPriceUpdate
        FROM Pool p 
        WHERE p.isActive = true
        GROUP BY p.dexName
        ORDER BY totalPools DESC
        """)
    List<Object[]> getPoolStatisticsByDex();
}