-- Phase 1 seed: a starter set of tracked actors.
-- Funds carry their real SEC CIK so Phase 4 (13F) can look them up directly.
-- Congress members are seeded by slug; Phase 3 fills in their trades.
INSERT INTO tracked_actor (name, type, external_id) VALUES
    ('Berkshire Hathaway (Warren Buffett)', 'FUND', '0001067983'),
    ('Scion Asset Management (Michael Burry)', 'FUND', '0001649339'),
    ('Pershing Square (Bill Ackman)', 'FUND', '0001336528'),
    ('Bridgewater Associates (Ray Dalio)', 'FUND', '0001350694'),
    ('Nancy Pelosi', 'CONGRESS', 'nancy-pelosi'),
    ('Dan Crenshaw', 'CONGRESS', 'dan-crenshaw')
ON CONFLICT (type, external_id) DO NOTHING;
