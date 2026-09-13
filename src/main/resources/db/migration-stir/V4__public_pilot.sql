-- STIR 0.4 public pilot: attachments (photos/avatars), in-app notifications, minimal content
-- moderation, and STIR-side friendly labels for osTRIS device credentials. Same forced-RLS +
-- explicit server-side ownership pattern as every prior table.

CREATE TABLE stir.attachment (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 purpose varchar(32) NOT NULL CHECK (purpose IN ('LISTING_PHOTO','AVATAR')),
 listing_id uuid REFERENCES stir.listing(id),
 position smallint CHECK (position >= 0),
 object_key varchar(300) NOT NULL UNIQUE,
 media_type varchar(100) NOT NULL,
 size_bytes bigint NOT NULL CHECK (size_bytes > 0),
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DELETED')),
 created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL,
 CHECK ((purpose = 'LISTING_PHOTO') = (listing_id IS NOT NULL)),
 CHECK ((purpose = 'LISTING_PHOTO') = (position IS NOT NULL))
);
CREATE INDEX attachment_tenant_owner ON stir.attachment(tenant_id, owner_user_id, created_at DESC);
CREATE UNIQUE INDEX attachment_listing_position ON stir.attachment(listing_id, position) WHERE listing_id IS NOT NULL AND status = 'ACTIVE';

ALTER TABLE stir.participant_profile ADD COLUMN avatar_attachment_id uuid REFERENCES stir.attachment(id);

CREATE TABLE stir.notification (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 recipient_user_id uuid NOT NULL,
 type varchar(40) NOT NULL CHECK (type IN
  ('OFFER_RECEIVED','COUNTEROFFER_RECEIVED','OFFER_ACCEPTED','OFFER_DECLINED',
   'SIGNATURE_NEEDED','COUNTERPARTY_SIGNED','TRADE_COMMITTED','TRADE_REJECTED')),
 reference_type varchar(20) NOT NULL CHECK (reference_type IN ('NEGOTIATION','AGREEMENT')),
 reference_id uuid NOT NULL,
 read_at timestamptz,
 created_at timestamptz NOT NULL
);
CREATE INDEX notification_recipient_unread ON stir.notification(tenant_id, recipient_user_id, read_at, created_at DESC);

-- Content moderation (STIR's own listings/profiles) - never touches osTRIS Findings, PENALTY,
-- RESTITUTION or any economic sanction; a hidden Listing's Agreement/Trade/journal history is
-- untouched.
CREATE TABLE stir.content_report (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 reporter_user_id uuid NOT NULL,
 target_type varchar(20) NOT NULL CHECK (target_type IN ('LISTING','PROFILE')),
 target_id uuid NOT NULL,
 reason varchar(500) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED','DISMISSED')),
 created_at timestamptz NOT NULL,
 resolved_at timestamptz,
 resolved_by_user_id uuid
);
CREATE INDEX content_report_open ON stir.content_report(tenant_id, status, created_at DESC);

ALTER TABLE stir.listing ADD COLUMN hidden_by_moderator boolean NOT NULL DEFAULT false;

-- STIR-side friendly label for one of the caller's osTRIS credentials (device lifecycle UX only -
-- never the source of truth for whether a credential is active; that is always osTRIS discovery).
CREATE TABLE stir.participant_device_credential (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 user_id uuid NOT NULL,
 ostris_credential_id uuid NOT NULL,
 label varchar(80) NOT NULL,
 created_at timestamptz NOT NULL,
 UNIQUE (tenant_id, ostris_credential_id)
);
CREATE INDEX participant_device_credential_user ON stir.participant_device_credential(tenant_id, user_id);

ALTER TABLE stir.attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.attachment FORCE ROW LEVEL SECURITY;
ALTER TABLE stir.notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.notification FORCE ROW LEVEL SECURITY;
ALTER TABLE stir.content_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.content_report FORCE ROW LEVEL SECURITY;
ALTER TABLE stir.participant_device_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.participant_device_credential FORCE ROW LEVEL SECURITY;

CREATE POLICY attachment_tenant ON stir.attachment FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY notification_tenant ON stir.notification FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY content_report_tenant ON stir.content_report FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY participant_device_credential_tenant ON stir.participant_device_credential FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON stir.attachment TO idax_app, idax_admin;
GRANT SELECT, INSERT, UPDATE ON stir.notification TO idax_app, idax_admin;
GRANT SELECT, INSERT, UPDATE ON stir.content_report TO idax_app, idax_admin;
GRANT SELECT, INSERT, DELETE ON stir.participant_device_credential TO idax_app, idax_admin;
GRANT UPDATE (hidden_by_moderator) ON stir.listing TO idax_app, idax_admin;
