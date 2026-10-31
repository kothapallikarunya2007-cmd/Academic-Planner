package edu.campus;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static edu.campus.Database.*;

class CampusServiceTest {
    Database db; CampusService service; Map<String,Object> admin,faculty,reviewer;
    @BeforeEach void setup() throws Exception {
        db=new Database("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");db.initialize(true);service=new CampusService(db);
        admin=service.login("admin@campus.edu","Admin@12345");faculty=service.login("ananya@campus.edu","Faculty@12345");
        service.mutate("users",Map.of("name","Reviewer","email","reviewer@campus.edu","role","ADMIN","department","Office","password","ReviewerPassword123"),admin);reviewer=service.login("reviewer@campus.edu","ReviewerPassword123");
        service.mutate("academic/settings",Map.of("semester_start","2026-01-01","semester_end","2026-12-31","timezone","Asia/Kolkata","teaching_days",List.of(0,1,2,3,4),"periods",List.of("09:00-10:00","10:00-11:00","11:15-12:15","13:00-14:00","14:00-15:00","15:15-16:15")),admin);
    }
    void publish() throws Exception {
        service.mutate("generate",Map.of(),admin);
        String id;try(var c=db.open()){id=(String)rows(c,"SELECT id FROM schedule_versions WHERE status='DRAFT' ORDER BY created_at DESC LIMIT 1").get(0).get("id");}
        service.mutate("schedule/submit",Map.of("id",id),admin);service.mutate("schedule/approve",Map.of("id",id),reviewer);service.mutate("schedule/publish",Map.of("id",id),reviewer);
    }
    @Test void credentialsAndAuthorization() throws Exception {
        assertNull(service.login("admin@campus.edu","wrong"));assertFalse(admin.containsKey("password_hash"));
        assertThrows(SecurityException.class,()->service.mutate("generate",Map.of(),faculty));
        service.mutate("password",Map.of("current","Faculty@12345","password","NewPassword@123"),faculty);
        assertNull(service.login("ananya@campus.edu","Faculty@12345"));assertNotNull(service.login("ananya@campus.edu","NewPassword@123"));
    }
    @Test void generationHonorsCapacityAvailabilityAndCohorts() throws Exception {
        service.mutate("availability",Map.of("faculty_id","faculty-0","day_no",0,"slot_no",0,"available",false),admin);
        publish();
        try(var c=db.open()) {
            assertEquals(12,rows(c,"SELECT * FROM lessons").size());assertEquals(List.of(),Scheduler.conflicts(c));
            assertTrue(rows(c,"SELECT * FROM lessons WHERE course_id='course-0' AND day_no=0 AND slot_no=0").isEmpty());
            for(var course:rows(c,"SELECT * FROM courses"))assertEquals(3,rows(c,"SELECT * FROM lessons WHERE course_id=?",course.get("id")).size());
        }
    }
    @Test void impossibleGenerationPreservesPublishedSchedule() throws Exception {
        publish();
        List<Map<String,Object>> before;try(var c=db.open()){before=rows(c,"SELECT * FROM lessons ORDER BY id");}
        service.mutate("courses",Map.of("code","BIG","title","Oversized cohort","faculty_id","faculty-0","cohort","X","students",2000,"sessions",1),admin);
        assertThrows(IllegalArgumentException.class,()->service.mutate("generate",Map.of(),admin));
        try(var c=db.open()){assertEquals(before,rows(c,"SELECT * FROM lessons ORDER BY id"));}
    }
    @Test void invalidMovesAndAvailabilityRollBack() throws Exception {
        publish();
        Map<String,Object> first,second;try(var c=db.open()){var list=rows(c,"SELECT * FROM lessons ORDER BY day_no,slot_no");first=list.get(0);second=list.get(1);}
        assertThrows(Exception.class,()->service.mutate("move",Map.of("id",second.get("id"),"room_id",first.get("room_id"),"day_no",first.get("day_no"),"slot_no",first.get("slot_no")),admin));
        String fid;try(var c=db.open()){fid=(String)rows(c,"SELECT faculty_id FROM courses WHERE id=?",first.get("course_id")).get(0).get("faculty_id");}
        String facultyId=fid;
        assertThrows(IllegalArgumentException.class,()->service.mutate("availability",Map.of("faculty_id",facultyId,"day_no",first.get("day_no"),"slot_no",first.get("slot_no"),"available",false),admin));
        try(var c=db.open()){assertTrue(rows(c,"SELECT * FROM unavailable").isEmpty());assertEquals(List.of(),Scheduler.conflicts(c));}
    }
    @Test void allocateBalancesAndRejectsOwnInstructor() throws Exception {
        for(int i=0;i<4;i++)service.mutate("exams",Map.of("course_id","course-"+i,"room_id","room-0","exam_date","2026-11-07","slot_no",i),admin);
        service.mutate("allocate",Map.of(),admin);
        try(var c=db.open()) {var exams=rows(c,"SELECT e.*,co.faculty_id FROM exams e JOIN courses co ON co.id=e.course_id");Set<Object> assigned=new HashSet<>();for(var e:exams){assertNotNull(e.get("invigilator_id"));assertNotEquals(e.get("faculty_id"),e.get("invigilator_id"));assigned.add(e.get("invigilator_id"));}assertEquals(4,assigned.size());assertEquals(List.of(),Scheduler.conflicts(c));}
    }
    @Test void failedAllocationRollsBackAndExamCannotOverlapTeaching() throws Exception {
        publish();
        Map<String,Object> lesson;try(var c=db.open()){lesson=rows(c,"SELECT * FROM lessons WHERE day_no=0").get(0);}
        assertThrows(Exception.class,()->service.mutate("exams",Map.of("course_id",lesson.get("course_id"),"room_id",lesson.get("room_id"),"exam_date","2026-11-02","slot_no",lesson.get("slot_no")),admin));
        service.mutate("exams",Map.of("course_id","course-0","room_id","room-0","exam_date","2026-11-02","slot_no",5),admin);
        service.mutate("allocate",Map.of(),admin);
        List<Map<String,Object>> previous;try(var c=db.open()){previous=rows(c,"SELECT * FROM exams");}
        db.transaction(c->{for(int i=1;i<4;i++)exec(c,"INSERT INTO unavailable VALUES(?,?,?,?)",id(),"faculty-"+i,0,5);return null;});
        assertThrows(IllegalArgumentException.class,()->service.mutate("allocate",Map.of(),admin));
        try(var c=db.open()){assertEquals(previous,rows(c,"SELECT * FROM exams"));}
    }
    @Test void facultyAvailabilityCannotTargetSomeoneElse() throws Exception {
        service.mutate("availability",Map.of("faculty_id","faculty-1","day_no",1,"slot_no",3,"available",false),faculty);
        try(var c=db.open()){assertEquals("faculty-0",rows(c,"SELECT * FROM unavailable").get(0).get("faculty_id"));}
    }
    @Test void concurrentGenerationAndChatRemainConsistent() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(6);
        try {List<Callable<Void>> tasks=new ArrayList<>();for(int i=0;i<12;i++){int n=i;tasks.add(()->{if(n<3)db.transaction(c->{new Scheduler().generate(c);return null;});else service.message("faculty-0","Concurrent message "+n);return null;});}for(var future:pool.invokeAll(tasks))future.get();}
        finally{pool.shutdownNow();}
        try(var c=db.open()){assertEquals(12,rows(c,"SELECT * FROM lessons").size());assertEquals(9,rows(c,"SELECT * FROM messages").size());assertEquals(List.of(),Scheduler.conflicts(c));}
    }
    @Test void notificationsArePerUserAndDeletesRespectReferences() throws Exception {
        service.mutate("notifications/read",Map.of(),faculty);
        @SuppressWarnings("unchecked") var notifications=(List<Map<String,Object>>)service.state("faculty-0").get("notifications");
        assertEquals(1,((Number)notifications.get(0).get("is_read")).intValue());
        publish();
        String room;try(var c=db.open()){room=(String)rows(c,"SELECT room_id FROM lessons").get(0).get("room_id");}
        assertThrows(Exception.class,()->service.mutate("delete",Map.of("entity","rooms","id",room),admin));
        service.mutate("delete",Map.of("entity","courses","id","course-0"),admin);
        try(var c=db.open()){assertTrue(rows(c,"SELECT * FROM lessons WHERE course_id='course-0'").isEmpty());}
    }
}
