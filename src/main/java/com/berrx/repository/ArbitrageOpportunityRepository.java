package com.berrx.repository;

import com.berrx.model.ArbitrageOpportunity;
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
 * MINIMAL Repository for ArbitrageOpportunity entities
 *
 * This version contains ONLY working queries to get the application started.
 * NO complex queries, NO date arithmetic, NO problematic JPQL.
 */
@Repository
public interface ArbitrageOpportunityRepository extends JpaRepository<ArbitrageOpportunity, Long> {

    // =================== BASIC QUERIES ONLY ===================

    /**
     * Find all active opportunities
     */
    @Query("SELECT a FROM ArbitrageOpportunity a WHERE a.status IN ('DISCOVERED', 'VERIFIED')")
    List<ArbitrageOpportunity> findActiveOpportunities();

    /**
     * Find opportunities needing verification
     */
    @Query("SELECT a FROM ArbitrageOpportunity a WHERE a.status = 'DISCOVERED' AND a.rpcVerified = false")
    List<ArbitrageOpportunity> findOpportunitiesNeedingVerification();

    /**
     * Find by execution transaction
     */
    Optional<ArbitrageOpportunity> findByExecutionTx(String executionTx);

    /**
     * Find by status
     */
    List<ArbitrageOpportunity> findByStatus(ArbitrageOpportunity.OpportunityStatus status);

    /**
     * Find recent opportunities (basic query)
     */
    @Query("SELECT a FROM ArbitrageOpportunity a WHERE a.createdAt >= :since ORDER BY a.createdAt DESC")
    List<ArbitrageOpportunity> findRecentOpportunities(@Param("since") LocalDateTime since);

    /**
     * Count by status
     */
    Long countByStatus(ArbitrageOpportunity.OpportunityStatus status);

    /**
     * Update status
     */
    @Modifying
    @Transactional
    @Query("UPDATE ArbitrageOpportunity a SET a.status = :status WHERE a.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") ArbitrageOpportunity.OpportunityStatus status);

    /**
     * Mark as verified
     */
    @Modifying
    @Transactional
    @Query("UPDATE ArbitrageOpportunity a SET a.rpcVerified = true, a.status = 'VERIFIED' WHERE a.id = :id")
    int markAsVerified(@Param("id") Long id);

    // =================== CONVENIENCE METHODS ===================

    /**
     * Find today's opportunities (safe method using Java)
     */
    default List<ArbitrageOpportunity> findTodaysOpportunities() {
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        return findRecentOpportunities(startOfDay);
    }

    /**
     * Find currently active (safe method using Java)
     */
    default List<ArbitrageOpportunity> findCurrentlyActive() {
        return findActiveOpportunities().stream()
                .filter(a -> a.getExpiresAt().isAfter(LocalDateTime.now()))
                .toList();
    }

    /**
     * Count active opportunities
     */
    default Long countCurrentlyActive() {
        return (long) findCurrentlyActive().size();
    }
}