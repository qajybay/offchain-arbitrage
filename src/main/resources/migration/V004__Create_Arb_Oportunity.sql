-- Create ArbitrageOpportunity table for storing arbitrage opportunities with lifecycle tracking
-- TASK 1: Entity + Repository for arbitrage opportunity management
-- SIMPLIFIED VERSION for Flyway compatibility

-- =================== CREATE ARBITRAGE_OPPORTUNITIES TABLE ===================

CREATE TABLE IF NOT EXISTS arbitrage_opportunities (
    id BIGSERIAL PRIMARY KEY,

    -- =================== OPPORTUNITY IDENTIFICATION ===================
    opportunity_type VARCHAR(20) NOT NULL DEFAULT 'TWO_WAY'
        CHECK (opportunity_type IN ('TWO_WAY', 'TRIANGLE', 'CROSS_DEX')),

    status VARCHAR(20) NOT NULL DEFAULT 'DISCOVERED'
        CHECK (status IN ('DISCOVERED', 'VERIFIED', 'EXPIRED', 'EXECUTED', 'FAILED')),

    token_pair_id VARCHAR(89) NOT NULL, -- tokenA-tokenB sorted format

    -- =================== DEX AND POOL INFORMATION ===================
    dex_1 VARCHAR(20) NOT NULL
        CHECK (dex_1 IN ('raydium', 'orca', 'meteora')),

    dex_2 VARCHAR(20) NOT NULL
        CHECK (dex_2 IN ('raydium', 'orca', 'meteora')),

    pool_1_address VARCHAR(44) NOT NULL
        CHECK (LENGTH(TRIM(pool_1_address)) BETWEEN 43 AND 44),

    pool_2_address VARCHAR(44) NOT NULL
        CHECK (LENGTH(TRIM(pool_2_address)) BETWEEN 43 AND 44),

    token_a_mint VARCHAR(44) NOT NULL
        CHECK (LENGTH(TRIM(token_a_mint)) BETWEEN 43 AND 44),

    token_b_mint VARCHAR(44) NOT NULL
        CHECK (LENGTH(TRIM(token_b_mint)) BETWEEN 43 AND 44),

    token_symbols VARCHAR(50), -- Display format like "SOL/USDC"

    -- =================== PROFIT CALCULATIONS ===================
    profit_percentage DECIMAL(10,6) NOT NULL CHECK (profit_percentage >= 0),
    estimated_profit_usd DECIMAL(15,6),
    input_amount DECIMAL(15,6),
    output_amount DECIMAL(15,6),
    trading_path VARCHAR(200),

    -- =================== PRICE INFORMATION ===================
    price_dex_1 DECIMAL(20,10),
    price_dex_2 DECIMAL(20,10),
    price_difference_percent DECIMAL(10,6),

    -- =================== LIFECYCLE TIMESTAMPS ===================
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP,

    -- =================== RPC VERIFICATION ===================
    rpc_verified BOOLEAN NOT NULL DEFAULT false,
    last_verification_at TIMESTAMP,
    verification_attempts INTEGER DEFAULT 0,
    verification_notes VARCHAR(500),

    -- =================== EXECUTION TRACKING ===================
    execution_tx VARCHAR(88), -- Solana transaction signature
    actual_profit_usd DECIMAL(15,6),
    execution_slippage DECIMAL(10,6),
    execution_error VARCHAR(1000),

    -- =================== QUALITY METRICS ===================
    priority_score DECIMAL(10,2) DEFAULT 0,
    total_tvl_usd DECIMAL(15,2),
    data_source VARCHAR(20) DEFAULT 'DEXSCREENER',

    -- =================== CONSTRAINTS ===================
    CONSTRAINT chk_different_pools CHECK (pool_1_address != pool_2_address),
    CONSTRAINT chk_different_dex CHECK (dex_1 != dex_2),
    CONSTRAINT chk_valid_tokens CHECK (token_a_mint != token_b_mint),
    CONSTRAINT chk_valid_expiration CHECK (expires_at > created_at),
    CONSTRAINT chk_valid_profit CHECK (profit_percentage >= 0),
    CONSTRAINT chk_valid_execution_tx CHECK (
        execution_tx IS NULL OR LENGTH(execution_tx) BETWEEN 43 AND 88
    )
);

-- =================== PERFORMANCE INDEXES ===================

-- Primary indexes for opportunity management
CREATE INDEX IF NOT EXISTS idx_arb_status_created
    ON arbitrage_opportunities (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_arb_expires
    ON arbitrage_opportunities (expires_at);

CREATE INDEX IF NOT EXISTS idx_arb_profit
    ON arbitrage_opportunities (profit_percentage DESC);

CREATE INDEX IF NOT EXISTS idx_arb_active
    ON arbitrage_opportunities (status, expires_at);

-- DEX and pool lookup indexes
CREATE INDEX IF NOT EXISTS idx_arb_dex_pair
    ON arbitrage_opportunities (dex_1, dex_2, token_pair_id);

CREATE INDEX IF NOT EXISTS idx_arb_pools
    ON arbitrage_opportunities (pool_1_address, pool_2_address);

CREATE INDEX IF NOT EXISTS idx_arb_token_pair
    ON arbitrage_opportunities (token_pair_id, status, expires_at);

-- RPC verification indexes
CREATE INDEX IF NOT EXISTS idx_arb_verification
    ON arbitrage_opportunities (rpc_verified, last_verification_at);

CREATE INDEX IF NOT EXISTS idx_arb_needs_verification
    ON arbitrage_opportunities (status, last_verification_at, profit_percentage DESC);

-- Analytics and performance indexes
CREATE INDEX IF NOT EXISTS idx_arb_priority
    ON arbitrage_opportunities (priority_score DESC, profit_percentage DESC);

CREATE INDEX IF NOT EXISTS idx_arb_execution_stats
    ON arbitrage_opportunities (status, created_at, actual_profit_usd);

CREATE INDEX IF NOT EXISTS idx_arb_recent
    ON arbitrage_opportunities (created_at DESC);

-- Cleanup and maintenance indexes
CREATE INDEX IF NOT EXISTS idx_arb_cleanup
    ON arbitrage_opportunities (status, created_at);

-- =================== COMMENTS ===================

COMMENT ON TABLE arbitrage_opportunities IS 'Stores arbitrage opportunities with full lifecycle tracking from discovery to execution';
COMMENT ON COLUMN arbitrage_opportunities.token_pair_id IS 'Sorted token pair ID for consistent grouping (tokenA-tokenB)';
COMMENT ON COLUMN arbitrage_opportunities.priority_score IS 'Priority for execution order (calculated by application)';
COMMENT ON COLUMN arbitrage_opportunities.expires_at IS 'When opportunity expires and should be marked as EXPIRED';
COMMENT ON COLUMN arbitrage_opportunities.rpc_verified IS 'Whether opportunity was confirmed via Solana RPC calls';
COMMENT ON COLUMN arbitrage_opportunities.verification_attempts IS 'Number of RPC verification attempts made';
COMMENT ON COLUMN arbitrage_opportunities.execution_tx IS 'Solana transaction signature if executed';
COMMENT ON COLUMN arbitrage_opportunities.data_source IS 'Source of opportunity (DEXSCREENER, RPC_VERIFIED)';

-- =================== SUCCESS MESSAGE ===================
SELECT 'ArbitrageOpportunity table created successfully!' as message;