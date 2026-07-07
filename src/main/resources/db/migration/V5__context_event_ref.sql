-- Dedup key for context events (8-K accession, ticker+period for analyst/earnings, ticker+date for metrics).
ALTER TABLE context_event ADD COLUMN ref VARCHAR(255);
CREATE UNIQUE INDEX uq_context_type_ref ON context_event (type, ref);
