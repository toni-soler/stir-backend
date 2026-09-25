-- Explicit per-case order makes simultaneous review events deterministic on rebuild.
ALTER TABLE stir.market_integrity_case_event ADD COLUMN sequence int;
WITH numbered AS (
 SELECT id,row_number() OVER (PARTITION BY tenant_id,case_id ORDER BY recorded_at,id) AS n
 FROM stir.market_integrity_case_event
)
UPDATE stir.market_integrity_case_event e SET sequence=numbered.n
FROM numbered WHERE e.id=numbered.id;
ALTER TABLE stir.market_integrity_case_event ALTER COLUMN sequence SET NOT NULL;
ALTER TABLE stir.market_integrity_case_event ADD CONSTRAINT case_event_order_unique UNIQUE(tenant_id,case_id,sequence);
