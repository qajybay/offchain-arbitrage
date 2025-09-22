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
 * Repository для Pool entities с JPA.
 * Поддерживает фильтрацию по DEX, TVL и поиск торговых пар.
 */
@Repository
public interface PoolRepository extends JpaRepository<Pool, Long> {

    /**
     * Найти пул по адресу
     */
    Optional<Pool> findByAddress(String address);

    /**
     * Найти активные пулы с минимальным TVL
     */
    @Query("SELECT p FROM Pool p WHERE p.isActive = true AND p.tvlUsd >= :minTvl ORDER BY p.tvlUsd DESC")
    List<Pool> findActivePoolsWithMinTvl(@Param("minTvl") Double minTvl);

    /**
     * Найти пулы по DEX и статусу
     */
    List<Pool> findByDexNameAndIsActiveOrderByTvlUsdDesc(String dexName, Boolean isActive);

    /**
     * Найти пулы по конкретным DEX
     */
    @Query("SELECT p FROM Pool p WHERE p.dexName IN :dexNames AND p.isActive = true AND p.tvlUsd >= :minTvl ORDER BY p.tvlUsd DESC")
    List<Pool> findByDexNamesAndMinTvl(@Param("dexNames") List<String> dexNames, @Param("minTvl") Double minTvl);

    /**
     * Найти активные пулы с токеном
     */
    @Query("SELECT p FROM Pool p WHERE p.isActive = true AND (p.tokenAMint = :tokenMint OR p.tokenBMint = :tokenMint) ORDER BY p.tvlUsd DESC")
    List<Pool> findActivePoolsWithToken(@Param("tokenMint") String tokenMint);

    /**
     * Найти пулы для торговой пары (оба направления)
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
     * Найти топ пулы по каждому DEX
     */
    @Query(value = """
        SELECT DISTINCT ON (p.dex_name) p.*
        FROM pools p 
        WHERE p.is_active = true AND p.tvl_usd >= :minTvl
        ORDER BY p.dex_name, p.tvl_usd DESC
        """, nativeQuery = true)
    List<Pool> findTopPoolsByDex(@Param("minTvl") Double minTvl);

    /**
     * Подсчитать активные пулы
     */
    @Query("SELECT COUNT(*) FROM Pool p WHERE p.isActive = true")
    Long countActivePools();

    /**
     * Подсчитать пулы по DEX
     */
    @Query("SELECT COUNT(*) FROM Pool p WHERE p.dexName = :dexName AND p.isActive = true")
    Long countActivePoolsByDex(@Param("dexName") String dexName);

    /**
     * Найти устаревшие пулы
     */
    @Query("SELECT p FROM Pool p WHERE p.isActive = true AND p.lastUpdated < :cutoffTime")
    List<Pool> findStaleActivePools(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Деактивировать старые пулы
     */
    @Modifying
    @Transactional
    @Query("UPDATE Pool p SET p.isActive = false WHERE p.lastUpdated < :cutoffTime")
    int deactivateOldPools(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Обновить TVL пула
     */
    @Modifying
    @Query("UPDATE Pool p SET p.tvlUsd = :tvlUsd, p.lastUpdated = :lastUpdated WHERE p.address = :address")
    int updatePoolTvl(@Param("address") String address, @Param("tvlUsd") Double tvlUsd, @Param("lastUpdated") LocalDateTime lastUpdated);

    /**
     * Найти дубликаты пулов (по токенам и DEX)
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
     * Найти пулы по списку адресов
     */
    @Query("SELECT p FROM Pool p WHERE p.address IN :addresses")
    List<Pool> findByAddresses(@Param("addresses") List<String> addresses);

    /**
     * Найти пулы с метаданными (для проверки качества данных)
     */
    @Query("SELECT p FROM Pool p WHERE p.tokenASymbol IS NOT NULL AND p.tokenBSymbol IS NOT NULL AND p.isActive = true")
    List<Pool> findPoolsWithMetadata();

    /**
     * Найти пулы без метаданных (для обновления)
     */
    @Query("SELECT p FROM Pool p WHERE (p.tokenASymbol IS NULL OR p.tokenBSymbol IS NULL) AND p.isActive = true")
    List<Pool> findPoolsWithoutMetadata();

    /**
     * Статистика по DEX
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
     * Найти недавно обновленные пулы
     */
    @Query("SELECT p FROM Pool p WHERE p.lastUpdated >= :since ORDER BY p.lastUpdated DESC")
    List<Pool> findRecentlyUpdated(@Param("since") LocalDateTime since);

    /**
     * Найти пулы по источнику данных
     */
    List<Pool> findBySourceAndIsActiveOrderByTvlUsdDesc(String source, Boolean isActive);
}