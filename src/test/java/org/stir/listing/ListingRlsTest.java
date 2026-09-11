package org.stir.listing;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ListingRlsTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll static void migrateEmptyDatabaseAndProvisionRuntimeRoles() throws Exception {
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()){
            s.execute("CREATE ROLE idax_app; CREATE ROLE idax_admin");
        }
        Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).schemas("stir").locations("classpath:db/migration-stir").load().migrate();
    }

    @Test void emptyDatabaseMigratesAndForcedRlsSeparatesTenants() throws Exception {
        var a=UUID.randomUUID();var b=UUID.randomUUID();var id=UUID.randomUUID();
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()){
            c.setAutoCommit(false);
            s.execute("SET LOCAL ROLE idax_app");
            s.execute("SELECT set_config('app.tenant_id','"+a+"',true)");
            s.execute("INSERT INTO stir.listing VALUES ('"+id+"','"+a+"','"+UUID.randomUUID()+"','OFFER','Chair','Wood','general','physical',NULL,'ACTIVE',0,now(),now())");
            s.execute("SELECT set_config('app.tenant_id','"+b+"',true)");
            try(var rs=s.executeQuery("SELECT count(*) FROM stir.listing")){rs.next();assertEquals(0,rs.getInt(1));}
            assertEquals(0,s.executeUpdate("UPDATE stir.listing SET title='intrusion' WHERE id='"+id+"'"));
            s.execute("SET LOCAL ROLE idax_admin");
            try(var rs=s.executeQuery("SELECT count(*) FROM stir.listing")){rs.next();assertEquals(0,rs.getInt(1));}
            s.execute("SELECT set_config('app.tenant_id','"+a+"',true)");
            try(var rs=s.executeQuery("SELECT count(*) FROM stir.listing")){rs.next();assertEquals(1,rs.getInt(1));}
            c.rollback();
        }
    }

    /** Same forced tenant-equality RLS pattern, now proven for every 0.2 marketplace table. */
    @Test void marketplaceTablesForceRlsAndIsolateTenants() throws Exception {
        var a=UUID.randomUUID(); var b=UUID.randomUUID();
        var listingId=UUID.randomUUID(); var ownerId=UUID.randomUUID(); var initiatorId=UUID.randomUUID();
        var profileId=UUID.randomUUID(); var negotiationId=UUID.randomUUID(); var offerId=UUID.randomUUID();
        var agreementId=UUID.randomUUID(); var snapshotId=UUID.randomUUID();
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()){
            c.setAutoCommit(false);
            s.execute("SET LOCAL ROLE idax_app");
            s.execute("SELECT set_config('app.tenant_id','"+a+"',true)");
            s.execute("INSERT INTO stir.listing VALUES ('"+listingId+"','"+a+"','"+ownerId+"','OFFER','Chair','Wood','general','physical',NULL,'ACTIVE',0,now(),now())");
            s.execute("INSERT INTO stir.participant_profile VALUES ('"+profileId+"','"+a+"','"+ownerId+"','Owner',NULL,NULL,true,0,now(),now())");
            s.execute("INSERT INTO stir.negotiation(id,tenant_id,listing_id,initiator_id,owner_id,status,version,created_at,updated_at)"
                +" VALUES ('"+negotiationId+"','"+a+"','"+listingId+"','"+initiatorId+"','"+ownerId+"','OPEN',0,now(),now())");
            s.execute("INSERT INTO stir.offer(id,tenant_id,negotiation_id,listing_id,sequence_number,author_id,message,status,created_at)"
                +" VALUES ('"+offerId+"','"+a+"','"+negotiationId+"','"+listingId+"',1,'"+initiatorId+"','Interested','PROPOSED',now())");
            s.execute("UPDATE stir.negotiation SET last_offer_id='"+offerId+"' WHERE id='"+negotiationId+"'");
            s.execute("INSERT INTO stir.agreement(id,tenant_id,negotiation_id,listing_id,offer_id,initiator_id,owner_id,version,created_at)"
                +" VALUES ('"+agreementId+"','"+a+"','"+negotiationId+"','"+listingId+"','"+offerId+"','"+initiatorId+"','"+ownerId+"',0,now())");
            s.execute("INSERT INTO stir.agreement_snapshot(id,tenant_id,agreement_id,schema_version,canonical_json,nonce,digest_sha256,created_at)"
                +" VALUES ('"+snapshotId+"','"+a+"','"+agreementId+"',1,'{}','nonce',repeat('a',64),now())");

            for (String table : new String[]{"participant_profile","negotiation","offer","agreement","agreement_snapshot"}) {
                s.execute("SELECT set_config('app.tenant_id','"+b+"',true)");
                try(var rs=s.executeQuery("SELECT count(*) FROM stir."+table)){rs.next();assertEquals(0,rs.getInt(1),table+" must be invisible from tenant B");}
                s.execute("SELECT set_config('app.tenant_id','"+a+"',true)");
                try(var rs=s.executeQuery("SELECT count(*) FROM stir."+table)){rs.next();assertEquals(1,rs.getInt(1),table+" must be visible from its own tenant A");}
            }
            s.execute("SELECT set_config('app.tenant_id','"+b+"',true)");
            assertEquals(0,s.executeUpdate("UPDATE stir.negotiation SET status='DECLINED' WHERE id='"+negotiationId+"'"),"tenant B must not be able to mutate tenant A's negotiation");
            c.rollback();
        }
    }
}
