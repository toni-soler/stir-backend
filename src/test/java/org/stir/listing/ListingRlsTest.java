package org.stir.listing;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ListingRlsTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    @Test void emptyDatabaseMigratesAndForcedRlsSeparatesTenants() throws Exception {
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()){
            s.execute("CREATE ROLE idax_app; CREATE ROLE idax_admin");
        }
        Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).schemas("stir").locations("classpath:db/migration-stir").load().migrate();
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
}
