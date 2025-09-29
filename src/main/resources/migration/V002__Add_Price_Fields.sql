-- Database migration to add price fields to pools table
-- This fixes the Hibernate error by making price fields persistent instead of @Transient

-- Add current price fields (previously @Transient)
ALTER TABLE pools ADD COLUMN IF NOT EXISTS current_price_a DOUBLE PRECISION;
ALTER TABLE pools ADD COLUMN IF NOT EXISTS current_price_b DOUBLE PRECISION;
ALTER TABLE pools ADD COLUMN IF NOT EXISTS price_updated_at TIMESTAMP;

-- Add balance fields for arbitrage calculations
ALTER TABLE pools ADD COLUMN IF NOT EXISTS token_a_balance DOUBLE PRECISION;
ALTER TABLE pools ADD COLUMN IF NOT EXISTS token_b_balance DOUBLE PRECISION;
ALTER TABLE pools ADD COLUMN IF NOT EXISTS exchange_rate DOUBLE PRECISION;

-- Add indexes for performance on arbitrage queries
CREATE INDEX IF NOT EXISTS idx_pool_current_prices ON pools(current_price_a, current_price_b) WHERE current_price_a IS NOT NULL AND current_price_b IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_pool_price_updated ON pools(price_updated_at) WHERE price_updated_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_pool_arbitrage_ready ON pools(is_active, tvl_usd, current_price_a, current_price_b) WHERE is_active = true AND current_price_a IS NOT NULL AND current_price_b IS NOT NULL;

-- Add composite index for token pair arbitrage detection
CREATE INDEX IF NOT EXISTS idx_pool_token_pair_prices ON pools(token_a_mint, token_b_mint, dex_name, current_price_a, current_price_b) WHERE is_active = true AND current_price_a IS NOT NULL;

-- Update existing pools to have default values (optional)
-- UPDATE pools SET current_price_a = NULL, current_price_b = NULL, price_updated_at = NULL WHERE current_price_a IS NULL;

COMMENT ON COLUMN pools.current_price_a IS 'Current price of token A from Solana RPC (now persistent for JPA queries)';
COMMENT ON COLUMN pools.current_price_b IS 'Current price of token B from Solana RPC (now persistent for JPA queries)';
COMMENT ON COLUMN pools.price_updated_at IS 'Timestamp of last price update from RPC';
COMMENT ON COLUMN pools.token_a_balance IS 'Current balance of token A in pool';
COMMENT ON COLUMN pools.token_b_balance IS 'Current balance of token B in pool';
COMMENT ON COLUMN pools.exchange_rate IS 'Current exchange rate (B per A)';

-- Statistics query to check data after migration
-- SELECT 
--     COUNT(*) as total_pools,
--     COUNT(current_price_a) as pools_with_price_a,
--     COUNT(current_price_b) as pools_with_price_b,
--     COUNT(price_updated_at) as pools_with_price_timestamp,
--     dex_name
-- FROM pools 
-- WHERE is_active = true 
-- GROUP BY dex_name;