-- Solana Arbitrage Bot Database Schema
-- Simplified version for R2DBC compatibility

-- Create enum type for opportunity types (simplified approach)
-- Note: R2DBC may have issues with complex enum creation, so we'll use VARCHAR with constraints

-- Pools with TVL >= 40k
CREATE TABLE IF NOT EXISTS pools (
    id BIGSERIAL PRIMARY KEY,
    address VARCHAR(44) UNIQUE NOT NULL,
    token_a VARCHAR(44) NOT NULL,
    token_b VARCHAR(44) NOT NULL,
    token_a_mint VARCHAR(44) NOT NULL,
    token_b_mint VARCHAR(44) NOT NULL,
    tvl BIGINT NOT NULL CHECK (tvl >= 0),
    dex_name VARCHAR(50) NOT NULL,
    fee_rate DECIMAL(8,6) NOT NULL DEFAULT 0.0025,
    last_updated TIMESTAMP NOT NULL DEFAULT NOW(),
    is_active BOOLEAN NOT NULL DEFAULT true,

    -- Constraints
    CONSTRAINT chk_valid_address CHECK (LENGTH(address) = 44),
    CONSTRAINT chk_valid_tokens CHECK (LENGTH(token_a) = 44 AND LENGTH(token_b) = 44),
    CONSTRAINT chk_valid_mints CHECK (LENGTH(token_a_mint) = 44 AND LENGTH(token_b_mint) = 44)
);

-- Trade pairs for arbitrage analysis
CREATE TABLE IF NOT EXISTS trade_pairs (
    id BIGSERIAL PRIMARY KEY,
    token_a VARCHAR(44) NOT NULL,
    token_b VARCHAR(44) NOT NULL,
    pool_count INTEGER NOT NULL DEFAULT 0,
    avg_tvl BIGINT,
    best_fee_rate DECIMAL(8,6),
    last_price DECIMAL(18,8),
    last_updated TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Unique constraint for pairs
    UNIQUE(token_a, token_b),
    CONSTRAINT chk_valid_pair_tokens CHECK (LENGTH(token_a) = 44 AND LENGTH(token_b) = 44),
    CONSTRAINT chk_different_tokens CHECK (token_a != token_b)
);

-- Arbitrage opportunities with expiration
-- Using VARCHAR instead of ENUM for R2DBC compatibility
CREATE TABLE IF NOT EXISTS arbitrage_opportunities (
    id BIGSERIAL PRIMARY KEY,
    opportunity_type VARCHAR(20) NOT NULL CHECK (opportunity_type IN ('TRIANGLE', 'TWO_WAY')),
    profit_percentage DECIMAL(10,6) NOT NULL CHECK (profit_percentage > 0),
    profit_amount_sol DECIMAL(18,8),
    path TEXT NOT NULL,
    input_amount DECIMAL(18,8) NOT NULL,
    output_amount DECIMAL(18,8) NOT NULL,
    pools_involved TEXT NOT NULL, -- JSON array of pool addresses

    -- Execution tracking
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,
    executed BOOLEAN NOT NULL DEFAULT false,
    execution_tx VARCHAR(88), -- Transaction signature
    execution_status VARCHAR(20),
    execution_profit_actual DECIMAL(18,8),

    -- Priority score (simplified calculation for R2DBC)
    priority_score DECIMAL(8,4) DEFAULT 0,

    CONSTRAINT chk_valid_amounts CHECK (input_amount > 0 AND output_amount > input_amount),
    CONSTRAINT chk_valid_expiration CHECK (expires_at > created_at),
    CONSTRAINT chk_valid_tx_sig CHECK (execution_tx IS NULL OR LENGTH(execution_tx) <= 88)
);

-- Indexes for performance (fixed for R2DBC compatibility)
CREATE INDEX IF NOT EXISTS idx_pools_active_tvl ON pools (is_active, tvl DESC) WHERE is_active = true;
CREATE INDEX IF NOT EXISTS idx_pools_dex_tokens ON pools (dex_name, token_a, token_b);
CREATE INDEX IF NOT EXISTS idx_pools_updated ON pools (last_updated DESC);
CREATE INDEX IF NOT EXISTS idx_pools_token_mints ON pools (token_a_mint, token_b_mint);

CREATE INDEX IF NOT EXISTS idx_trade_pairs_tokens ON trade_pairs (token_a, token_b);
CREATE INDEX IF NOT EXISTS idx_trade_pairs_tvl ON trade_pairs (avg_tvl DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS idx_trade_pairs_updated ON trade_pairs (last_updated DESC);

-- Simplified indexes without NOW() function
CREATE INDEX IF NOT EXISTS idx_opportunities_active ON arbitrage_opportunities (expires_at DESC, executed);
CREATE INDEX IF NOT EXISTS idx_opportunities_type_profit ON arbitrage_opportunities (opportunity_type, profit_percentage DESC);
CREATE INDEX IF NOT EXISTS idx_opportunities_priority ON arbitrage_opportunities (priority_score DESC);
CREATE INDEX IF NOT EXISTS idx_opportunities_execution ON arbitrage_opportunities (execution_status, executed);
CREATE INDEX IF NOT EXISTS idx_opportunities_created ON arbitrage_opportunities (created_at DESC);

-- Simple indexes without WHERE clauses
CREATE INDEX IF NOT EXISTS idx_active_triangle_opportunities ON arbitrage_opportunities (profit_percentage DESC)
    WHERE opportunity_type = 'TRIANGLE';

CREATE INDEX IF NOT EXISTS idx_active_twoway_opportunities ON arbitrage_opportunities (profit_percentage DESC)
    WHERE opportunity_type = 'TWO_WAY';