-- Multi-horizon briefings. The existing signal/confidence columns become the SWING (weeks) headline
-- read — what alerts, the portfolio line, and the backtest already key off. Short- and long-horizon
-- reads are added alongside so the ticker detail view can show all three and surface disagreement.
ALTER TABLE briefing ADD COLUMN short_signal     VARCHAR(8);
ALTER TABLE briefing ADD COLUMN short_confidence NUMERIC(4,3);
ALTER TABLE briefing ADD COLUMN long_signal      VARCHAR(8);
ALTER TABLE briefing ADD COLUMN long_confidence  NUMERIC(4,3);
