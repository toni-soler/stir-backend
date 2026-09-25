-- Keep the seven-seat count in PostgreSQL, including when a caller bypasses the HTTP layer.
CREATE FUNCTION stir.require_seven_constitutional_seats() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (SELECT count(*) FROM stir.constitutional_seat WHERE tenant_id=NEW.tenant_id AND authority_id=NEW.id) <> 7 THEN
  RAISE EXCEPTION 'Constitutional authority requires exactly seven seats';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER seven_seats_at_commit AFTER INSERT ON stir.constitutional_authority
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION stir.require_seven_constitutional_seats();
