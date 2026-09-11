-- STIR 0.3 economic exchange: explicit tenant<->community/unit and user<->participant/account
-- bindings, and Trade (STIR's own link to an osTRIS EXCHANGE, never a parallel ledger).

CREATE TABLE stir.marketplace_economic_binding (
 tenant_id uuid PRIMARY KEY,
 community_id uuid NOT NULL,
 unit_id uuid NOT NULL,
 created_at timestamptz NOT NULL
);

CREATE TABLE stir.participant_economic_binding (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 user_id uuid NOT NULL,
 community_id uuid NOT NULL,
 unit_id uuid NOT NULL,
 participant_id uuid NOT NULL,
 account_id uuid NOT NULL,
 controller_id uuid NOT NULL,
 credential_id uuid NOT NULL,
 public_key_base64url varchar(64) NOT NULL,
 created_at timestamptz NOT NULL,
 UNIQUE (tenant_id, user_id)
);

-- Explicit, auditable economic direction frozen at accept time (see section 6 of the 0.3 brief:
-- OFFER -> initiator pays the owner/provider; WANTED -> owner pays the initiator/provider).
-- NULL on both means this Agreement has no economic execution (free/non-monetary exchange).
ALTER TABLE stir.agreement ADD COLUMN payer_user_id uuid;
ALTER TABLE stir.agreement ADD COLUMN payee_user_id uuid;
ALTER TABLE stir.agreement ADD CONSTRAINT agreement_payer_payee_both_or_neither
 CHECK ((payer_user_id IS NULL) = (payee_user_id IS NULL));
-- IS DISTINCT FROM treats two NULLs as NOT distinct, so a plain "IS DISTINCT FROM" check would
-- reject the legitimate NOT_APPLICABLE case (both NULL) along with the real payer=payee bug.
ALTER TABLE stir.agreement ADD CONSTRAINT agreement_payer_not_payee
 CHECK (payer_user_id IS NULL OR payer_user_id IS DISTINCT FROM payee_user_id);

ALTER TABLE stir.agreement DROP CONSTRAINT agreement_economic_phase_check;
ALTER TABLE stir.agreement ADD CONSTRAINT agreement_economic_phase_check CHECK (economic_phase IN
 ('NOT_APPLICABLE','AWAITING_ECONOMIC_EXECUTION','AWAITING_SIGNATURES','COMMITTING','COMMITTED','REJECTED'));

CREATE TABLE stir.trade (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 agreement_id uuid NOT NULL UNIQUE REFERENCES stir.agreement(id),
 community_id uuid NOT NULL,
 unit_id uuid NOT NULL,
 transaction_id uuid NOT NULL UNIQUE,
 payer_account_id uuid NOT NULL,
 payee_account_id uuid NOT NULL,
 payer_user_id uuid NOT NULL,
 payee_user_id uuid NOT NULL,
 amount numeric(78,0) NOT NULL CHECK (amount > 0),
 contractual_metadata_digest varchar(64) NOT NULL CHECK (contractual_metadata_digest ~ '^[0-9a-f]{64}$'),
 execution_state varchar(24) NOT NULL CHECK (execution_state IN ('AWAITING_SIGNATURES','COMMITTING','COMMITTED','REJECTED')),
 committed_sequence bigint,
 protocol_digest varchar(64),
 committed_at timestamptz,
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL
);
CREATE INDEX trade_tenant_payer ON stir.trade(tenant_id, payer_user_id, created_at DESC, id);
CREATE INDEX trade_tenant_payee ON stir.trade(tenant_id, payee_user_id, created_at DESC, id);

ALTER TABLE stir.marketplace_economic_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.marketplace_economic_binding FORCE ROW LEVEL SECURITY;
ALTER TABLE stir.participant_economic_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.participant_economic_binding FORCE ROW LEVEL SECURITY;
ALTER TABLE stir.trade ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.trade FORCE ROW LEVEL SECURITY;

CREATE POLICY marketplace_economic_binding_tenant ON stir.marketplace_economic_binding FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY participant_economic_binding_tenant ON stir.participant_economic_binding FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY trade_tenant ON stir.trade FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);

GRANT SELECT, INSERT, UPDATE ON stir.marketplace_economic_binding TO idax_app, idax_admin;
GRANT SELECT, INSERT, UPDATE ON stir.participant_economic_binding TO idax_app, idax_admin;
GRANT SELECT, INSERT, UPDATE ON stir.trade TO idax_app, idax_admin;
