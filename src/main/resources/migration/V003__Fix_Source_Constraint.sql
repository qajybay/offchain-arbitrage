-- Посмотреть текущий constraint
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_valid_source';

-- Удалить старый constraint
ALTER TABLE pools DROP CONSTRAINT IF EXISTS chk_valid_source;

-- Создать новый constraint с поддержкой DEXSCREENER
ALTER TABLE pools ADD CONSTRAINT chk_valid_source
CHECK (source IN ('DEX_SCREENER', 'DEXSCREENER', 'SOLANA_RPC', 'MANUAL', 'WEBSOCKET'));