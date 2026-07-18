-- Backtest / track-record fields on each briefing.
-- entry_price is captured when the briefing is made; the rest are filled in later by the
-- backtest evaluator once the horizon has passed.
ALTER TABLE briefing ADD COLUMN entry_price  NUMERIC(18,4);
ALTER TABLE briefing ADD COLUMN horizon_days INT;
ALTER TABLE briefing ADD COLUMN evaluated_at TIMESTAMPTZ;
ALTER TABLE briefing ADD COLUMN exit_price   NUMERIC(18,4);
ALTER TABLE briefing ADD COLUMN return_pct   NUMERIC(10,3);
ALTER TABLE briefing ADD COLUMN correct      BOOLEAN;

CREATE INDEX idx_briefing_evaluated ON briefing (evaluated_at);
