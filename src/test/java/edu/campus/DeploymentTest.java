package edu.campus;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentTest {
    @Test void productionRejectsLocalDatabaseAndUnsafeDefaults() {
        assertThrows(IllegalStateException.class,()->Deployment.validate(Map.of()));
        var env=new HashMap<>(Map.of("DB_URL","jdbc:postgresql://host/campus?sslmode=verify-full","DB_USER","app","DB_PASSWORD","secret","SEED_DEMO","false","SECURE_COOKIE","true"));
        assertDoesNotThrow(()->Deployment.validate(env));
        env.put("SEED_DEMO","true"); assertThrows(IllegalStateException.class,()->Deployment.validate(env));
        env.put("SEED_DEMO","false"); env.put("SECURE_COOKIE","false"); assertThrows(IllegalStateException.class,()->Deployment.validate(env));
    }
    @Test void loginLimitsAreSharedAcrossInstances() throws Exception {
        var db=new Database("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""); db.initialize(true);
        var first=new Sessions(db); var second=new Sessions(db);
        for(int i=0;i<15;i++) assertTrue((i%2==0?first:second).allowLogin("admin@campus.edu"));
        assertFalse(new Sessions(db).allowLogin("admin@campus.edu"));
        assertTrue(second.allowLogin("another@campus.edu"));
    }
}
