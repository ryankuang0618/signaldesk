-- Insider quality: a short descriptor of a signal's detail (insider role, trade type, size) so the
-- AI weighs a CEO's $2M open-market buy differently from an automatic tax-withholding sale. Nullable;
-- currently populated for Form 4 signals, available for other sources later.
ALTER TABLE trade_signal ADD COLUMN note VARCHAR(255);
