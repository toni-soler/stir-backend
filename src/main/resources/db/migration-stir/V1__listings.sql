CREATE TABLE stir.category (code varchar(40) PRIMARY KEY);
INSERT INTO stir.category VALUES ('general'), ('home'), ('learning'), ('community');
CREATE TABLE stir.resource_kind (code varchar(40) PRIMARY KEY);
INSERT INTO stir.resource_kind VALUES ('physical'), ('service'), ('knowledge'), ('collaboration'), ('volunteering'), ('work'), ('other');
CREATE TABLE stir.listing (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 owner_id uuid NOT NULL,
 direction varchar(10) NOT NULL CHECK (direction IN ('OFFER','WANTED')),
 title varchar(160) NOT NULL CHECK (length(trim(title)) > 0),
 description varchar(8000) NOT NULL CHECK (length(trim(description)) > 0),
 category varchar(40) NOT NULL REFERENCES stir.category(code),
 resource_kind varchar(40) NOT NULL REFERENCES stir.resource_kind(code),
 location varchar(160),
 status varchar(10) NOT NULL CHECK (status IN ('ACTIVE','CLOSED')),
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL
);
CREATE INDEX listing_tenant_status ON stir.listing(tenant_id, status, created_at DESC, id);
CREATE INDEX listing_tenant_owner ON stir.listing(tenant_id, owner_id, created_at DESC, id);
ALTER TABLE stir.listing ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.listing FORCE ROW LEVEL SECURITY;
CREATE POLICY listing_tenant ON stir.listing FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
GRANT USAGE ON SCHEMA stir TO idax_app, idax_admin;
GRANT SELECT ON stir.category, stir.resource_kind TO idax_app, idax_admin;
GRANT SELECT, INSERT, UPDATE ON stir.listing TO idax_app, idax_admin;
