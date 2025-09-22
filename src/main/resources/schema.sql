-- Обновленная схема для Solana Arbitrage Bot
-- Гибридный подход: DEX Screener + Solana RPC

-- Таблица пулов с метаданными
CREATE TABLE IF NOT EXISTS pools (
    id BIGSERIAL PRIMARY KEY,

    -- Основные данные
    address VARCHAR(44) UNIQUE NOT NULL,

    -- Токен A
    token_a_mint VARCHAR(44) NOT NULL,
    token_a_symbol VARCHAR(20),
    token_a_name VARCHAR(100),

    -- Токен B
    token_b_mint VARCHAR(44) NOT NULL,
    token_b_symbol VARCHAR(20),
    token_b_name VARCHAR(100),

    -- Ликвидность и DEX
    tvl_usd DOUBLE PRECISION,
    dex_name VARCHAR(20) NOT NULL,
    fee_rate DOUBLE PRECISION DEFAULT 0.0025,

    -- Временные метки
    last_updated TIMESTAMP NOT NULL DEFAULT NOW(),
    is_active BOOLEAN NOT NULL DEFAULT true,
    source VARCHAR(20) DEFAULT 'DEX_SCREENER',

    -- Ограничения
    CONSTRAINT chk_valid_address CHECK (LENGTH(TRIM(address)) BETWEEN 43 AND 44),
    CONSTRAINT chk_valid_tokens CHECK (LENGTH(TRIM(token_a_mint)) BETWEEN 43 AND 44 AND LENGTH(TRIM(token_b_mint)) BETWEEN 43 AND 44),
    CONSTRAINT chk_different_tokens CHECK (token_a_mint != token_b_mint),
    CONSTRAINT chk_positive_tvl CHECK (tvl_usd IS NULL OR tvl_usd > 0),
    CONSTRAINT chk_valid_dex CHECK (dex_name IN ('raydium', 'orca', 'meteora', 'jupiter')),
    CONSTRAINT chk_valid_source CHECK (source IN ('DEX_SCREENER', 'SOLANA_RPC', 'MANUAL'))
);

-- Торговые пары для анализа арбитража
CREATE TABLE IF NOT EXISTS trade_pairs (
    id BIGSERIAL PRIMARY KEY,
    token_a VARCHAR(44) NOT NULL,
    token_b VARCHAR(44) NOT NULL,

    -- Агрегированные данные
    pool_count INTEGER NOT NULL DEFAULT 0,
    avg_tvl_usd DOUBLE PRECISION,
    best_fee_rate DOUBLE PRECISION,

    -- Актуальные цены (обновляются из Solana RPC)
    last_price DOUBLE PRECISION,
    price_updated_at TIMESTAMP,

    last_updated TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Ограничения
    UNIQUE(token_a, token_b),
    CONSTRAINT chk_valid_pair_tokens CHECK (LENGTH(TRIM(token_a)) BETWEEN 43 AND 44 AND LENGTH(TRIM(token_b)) BETWEEN 43 AND 44),
    CONSTRAINT chk_different_pair_tokens CHECK (token_a != token_b),
    CONSTRAINT chk_positive_pool_count CHECK (pool_count >= 0)
);

-- Арбитражные возможности
CREATE TABLE IF NOT EXISTS arbitrage_opportunities (
    id BIGSERIAL PRIMARY KEY,

    -- Тип возможности
    opportunity_type VARCHAR(20) NOT NULL CHECK (opportunity_type IN ('TRIANGLE', 'TWO_WAY')),

    -- Профитность
    profit_percentage DOUBLE PRECISION NOT NULL CHECK (profit_percentage > 0),
    profit_amount_sol DOUBLE PRECISION,
    profit_amount_usd DOUBLE PRECISION,

    -- Путь арбитража
    path TEXT NOT NULL,
    input_amount DOUBLE PRECISION NOT NULL,
    output_amount DOUBLE PRECISION NOT NULL,

    -- Пулы (JSON массив адресов)
    pools_involved TEXT NOT NULL,
    dex_names TEXT, -- Список DEX через запятую

    -- Временные метки
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,

    -- Исполнение
    executed BOOLEAN NOT NULL DEFAULT false,
    execution_tx VARCHAR(88),
    execution_status VARCHAR(20),
    execution_profit_actual DOUBLE PRECISION,
    execution_slippage DOUBLE PRECISION,

    -- Приоритет (автоматически рассчитывается)
    priority_score DOUBLE PRECISION DEFAULT 0,

    -- Ограничения
    CONSTRAINT chk_valid_amounts CHECK (input_amount > 0 AND output_amount > input_amount),
    CONSTRAINT chk_valid_expiration CHECK (expires_at > created_at),
    CONSTRAINT chk_valid_tx_sig CHECK (execution_tx IS NULL OR LENGTH(execution_tx) <= 88),
    CONSTRAINT chk_valid_execution_status CHECK (
        execution_status IS NULL OR
        execution_status IN ('SUCCESS', 'FAILED', 'PENDING', 'CANCELLED')
    )
);

-- Кеш токенов (метаданные из Jupiter/DEX Screener)
CREATE TABLE IF NOT EXISTS token_metadata (
    id BIGSERIAL PRIMARY KEY,
    mint_address VARCHAR(44) UNIQUE NOT NULL,
    symbol VARCHAR(20),
    name VARCHAR(100),
    decimals INTEGER DEFAULT 9,
    logo_uri TEXT,

    -- Источник данных
    source VARCHAR(20) DEFAULT 'JUPITER',
    verified BOOLEAN DEFAULT false,

    -- Временные метки
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_updated TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Ограничения
    CONSTRAINT chk_valid_mint CHECK (LENGTH(TRIM(mint_address)) BETWEEN 43 AND 44),
    CONSTRAINT chk_valid_decimals CHECK (decimals >= 0 AND decimals <= 18),
    CONSTRAINT chk_valid_token_source CHECK (source IN ('JUPITER', 'DEX_SCREENER', 'ON_CHAIN', 'MANUAL'))
);

-- Индексы для производительности
CREATE INDEX IF NOT EXISTS idx_pools_active_tvl ON pools (is_active, tvl_usd DESC) WHERE is_active = true;
CREATE INDEX IF NOT EXISTS idx_pools_dex_tokens ON pools (dex_name, token_a_mint, token_b_mint);
CREATE INDEX IF NOT EXISTS idx_pools_updated ON pools (last_updated DESC);
CREATE INDEX IF NOT EXISTS idx_pools_token_mints ON pools (token_a_mint, token_b_mint);
CREATE INDEX IF NOT EXISTS idx_pools_symbols ON pools (token_a_symbol, token_b_symbol) WHERE token_a_symbol IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_pools_source ON pools (source, is_active);

CREATE INDEX IF NOT EXISTS idx_trade_pairs_tokens ON trade_pairs (token_a, token_b);
CREATE INDEX IF NOT EXISTS idx_trade_pairs_tvl ON trade_pairs (avg_tvl_usd DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS idx_trade_pairs_updated ON trade_pairs (last_updated DESC);
CREATE INDEX IF NOT EXISTS idx_trade_pairs_price_updated ON trade_pairs (price_updated_at DESC NULLS LAST);

CREATE INDEX IF NOT EXISTS idx_opportunities_active ON arbitrage_opportunities (expires_at DESC, executed) WHERE executed = false;
CREATE INDEX IF NOT EXISTS idx_opportunities_type_profit ON arbitrage_opportunities (opportunity_type, profit_percentage DESC);
CREATE INDEX IF NOT EXISTS idx_opportunities_priority ON arbitrage_opportunities (priority_score DESC);
CREATE INDEX IF NOT EXISTS idx_opportunities_execution ON arbitrage_opportunities (execution_status, executed);
CREATE INDEX IF NOT EXISTS idx_opportunities_created ON arbitrage_opportunities (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_opportunities_dex ON arbitrage_opportunities USING gin(string_to_array(dex_names, ','));

CREATE INDEX IF NOT EXISTS idx_token_metadata_mint ON token_metadata (mint_address);
CREATE INDEX IF NOT EXISTS idx_token_metadata_symbol ON token_metadata (symbol) WHERE symbol IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_token_metadata_updated ON token_metadata (last_updated DESC);
CREATE INDEX IF NOT EXISTS idx_token_metadata_verified ON token_metadata (verified, source);

-- Триггер для автоматического обновления priority_score
CREATE OR REPLACE FUNCTION update_priority_score()
RETURNS TRIGGER AS $$
BEGIN
    -- Простая формула приоритета: profit% * 100 + (время до истечения в минутах / 10)
    NEW.priority_score = (NEW.profit_percentage * 100) +
                         (EXTRACT(EPOCH FROM (NEW.expires_at - NOW())) / 600);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_update_priority_score
    BEFORE INSERT OR UPDATE ON arbitrage_opportunities
    FOR EACH ROW
    EXECUTE FUNCTION update_priority_score();

-- Предзаполнение известных токенов
INSERT INTO token_metadata (mint_address, symbol, name, decimals, verified, source) VALUES
('So11111111111111111111111111111111111111112', 'SOL', 'Wrapped Solana', 9, true, 'MANUAL'),
('EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v', 'USDC', 'USD Coin', 6, true, 'MANUAL'),
('Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB', 'USDT', 'Tether USD', 6, true, 'MANUAL'),
('mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So', 'mSOL', 'Marinade staked SOL', 9, true, 'MANUAL'),
('7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs', 'ETH', 'Ethereum (Portal)', 8, true, 'MANUAL'),
('9n4nbM75f5Ui33ZbPYXn59EwSgE8CGsHtAeTH5YFeJ9E', 'BTC', 'Bitcoin (Portal)', 8, true, 'MANUAL')
ON CONFLICT (mint_address) DO UPDATE SET
    last_updated = NOW();

-- Комментарии для документации
COMMENT ON TABLE pools IS 'Пулы ликвидности с метаданными из DEX Screener';
COMMENT ON TABLE trade_pairs IS 'Агрегированные торговые пары для анализа арбитража';
COMMENT ON TABLE arbitrage_opportunities IS 'Найденные арбитражные возможности с временем жизни';
COMMENT ON TABLE token_metadata IS 'Кеш метаданных токенов из различных источников';

COMMENT ON COLUMN pools.tvl_usd IS 'TVL в USD из DEX Screener';
COMMENT ON COLUMN pools.source IS 'Источник данных: DEX_SCREENER, SOLANA_RPC, MANUAL';
COMMENT ON COLUMN arbitrage_opportunities.priority_score IS 'Автоматически рассчитываемый приоритет (триггер)';
COMMENT ON COLUMN trade_pairs.price_updated_at IS 'Время последнего обновления цены из Solana RPC';