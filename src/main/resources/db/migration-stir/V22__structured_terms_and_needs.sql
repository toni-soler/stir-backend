-- Comparable listings, needs with a private part, and structured negotiation terms
-- (STRUCTURED_TERMS_AND_NEEDS.md). All columns are nullable or defaulted: existing rows,
-- clients and snapshots keep their meaning. V20/V21 are taken by the governed-state
-- effectivity line, hence V22.

-- What is being offered or needed, beyond title and description: the object's state, how it
-- changes hands, what it includes, until when it is available, and how far a conversation got.
ALTER TABLE stir.listing ADD COLUMN item_condition varchar(20);
ALTER TABLE stir.listing ADD COLUMN fulfilment_mode varchar(20);
ALTER TABLE stir.listing ADD COLUMN included_items varchar(1000);
ALTER TABLE stir.listing ADD COLUMN available_until date;
ALTER TABLE stir.listing ADD COLUMN availability varchar(20) NOT NULL DEFAULT 'AVAILABLE';
-- A need: by when, and which dimensions can move and which cannot.
ALTER TABLE stir.listing ADD COLUMN needed_by date;
ALTER TABLE stir.listing ADD COLUMN flexible_on varchar(120);
ALTER TABLE stir.listing ADD COLUMN firm_on varchar(120);
-- Detail the owner shares only with the people negotiating with them, never in search results,
-- never in a listing revision (and therefore never in reference evidence).
ALTER TABLE stir.listing ADD COLUMN counterpart_note varchar(2000);

ALTER TABLE stir.listing ADD CONSTRAINT listing_item_condition_known CHECK
    (item_condition IS NULL OR item_condition IN ('NEW','LIKE_NEW','GOOD','FAIR','FOR_PARTS'));
ALTER TABLE stir.listing ADD CONSTRAINT listing_fulfilment_mode_known CHECK
    (fulfilment_mode IS NULL OR fulfilment_mode IN ('PICKUP','DELIVERY','PICKUP_OR_DELIVERY','ON_SITE','REMOTE'));
ALTER TABLE stir.listing ADD CONSTRAINT listing_availability_known CHECK
    (availability IN ('AVAILABLE','IN_CONVERSATION','COMMITTED'));
ALTER TABLE stir.listing ADD CONSTRAINT listing_needed_by_only_for_needs CHECK
    (needed_by IS NULL OR direction = 'WANTED');
ALTER TABLE stir.listing ADD CONSTRAINT listing_dimensions_known CHECK
    ((flexible_on IS NULL OR flexible_on ~ '^(TIMING|PLACE|QUANTITY|CONDITION|AMOUNT)(,(TIMING|PLACE|QUANTITY|CONDITION|AMOUNT))*$') AND
     (firm_on IS NULL OR firm_on ~ '^(TIMING|PLACE|QUANTITY|CONDITION|AMOUNT)(,(TIMING|PLACE|QUANTITY|CONDITION|AMOUNT))*$'));

-- A proposal is more than an amount: what it covers, what each side does, how and by when it is
-- fulfilled, what is still open, and until when it can be accepted. Frozen into the Agreement
-- snapshot from schemaVersion 4.
ALTER TABLE stir.offer ADD COLUMN scope varchar(2000);
ALTER TABLE stir.offer ADD COLUMN owner_tasks varchar(1000);
ALTER TABLE stir.offer ADD COLUMN initiator_tasks varchar(1000);
ALTER TABLE stir.offer ADD COLUMN fulfilment_mode varchar(20);
ALTER TABLE stir.offer ADD COLUMN fulfil_by date;
ALTER TABLE stir.offer ADD COLUMN open_questions varchar(1000);
ALTER TABLE stir.offer ADD COLUMN valid_until timestamptz;
ALTER TABLE stir.offer ADD CONSTRAINT offer_fulfilment_mode_known CHECK
    (fulfilment_mode IS NULL OR fulfilment_mode IN ('PICKUP','DELIVERY','PICKUP_OR_DELIVERY','ON_SITE','REMOTE'));
