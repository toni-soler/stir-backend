-- Optional opaque commitment to extension-owned terms. STIR stores no external domain data.
ALTER TABLE stir.offer ADD COLUMN external_contract_namespace varchar(80);
ALTER TABLE stir.offer ADD COLUMN external_contract_digest varchar(64);
ALTER TABLE stir.offer ADD CONSTRAINT offer_external_contract_pair CHECK
    ((external_contract_namespace IS NULL AND external_contract_digest IS NULL) OR
     (external_contract_namespace ~ '^[a-z][a-z0-9._-]{2,79}$' AND
      external_contract_digest ~ '^[0-9a-f]{64}$'));
