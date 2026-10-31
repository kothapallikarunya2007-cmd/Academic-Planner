package edu.campus;

import org.junit.jupiter.api.*;
import java.util.*;
import static edu.campus.Database.*;
import static org.junit.jupiter.api.Assertions.*;

class AcademicFeaturesTest {
    Database db;CampusService service;Map<String,Object> admin,reviewer,faculty;
    @BeforeEach void setup() throws Exception {
        db=new Database("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");db.initialize(true);service=new CampusService(db);
        admin=service.login("admin@campus.edu","Admin@12345");faculty=service.login("ananya@campus.edu","Faculty@12345");
        service.mutate("users",Map.of("name","Reviewer","email","review@campus.edu","role","ADMIN","department","Office","password","ReviewerPassword123"),admin);reviewer=service.login("review@campus.edu","ReviewerPassword123");
        configure(List.of(0,1,2,3,4),List.of("09:00-10:00","10:00-11:00","11:00-12:00","13:00-14:00","14:00-15:00","15:00-16:00"));
    }
    void configure(List<Integer> days,List<String> periods) throws Exception {service.mutate("academic/settings",Map.of("semester_start","2026-11-02","semester_end","2026-11-29","timezone","Asia/Kolkata","teaching_days",days,"periods",periods),admin);}
    String draft() throws Exception {service.mutate("generate",Map.of(),admin);try(var c=db.open()){return (String)rows(c,"SELECT id FROM schedule_versions ORDER BY created_at DESC LIMIT 1").get(0).get("id");}}
    void publish(String id) throws Exception {service.mutate("schedule/submit",Map.of("id",id),admin);service.mutate("schedule/approve",Map.of("id",id),reviewer);service.mutate("schedule/publish",Map.of("id",id),reviewer);}
    @Test void reviewPublicationRestoreAndStaleDraftProtection() throws Exception {
        String first=draft();try(var c=db.open()){assertTrue(rows(c,"SELECT * FROM lessons").isEmpty());}
        assertThrows(IllegalArgumentException.class,()->service.mutate("schedule/publish",Map.of("id",first),admin));
        service.mutate("schedule/submit",Map.of("id",first),admin);
        assertThrows(IllegalArgumentException.class,()->service.mutate("schedule/approve",Map.of("id",first),admin));
        service.mutate("schedule/approve",Map.of("id",first),reviewer);service.mutate("schedule/publish",Map.of("id",first),reviewer);
        try(var c=db.open()){assertEquals(12,rows(c,"SELECT * FROM lessons").size());}
        String stale=draft();service.mutate("rooms",Map.of("name","New lab","capacity",60),admin);
        service.mutate("schedule/submit",Map.of("id",stale),admin);service.mutate("schedule/approve",Map.of("id",stale),reviewer);
        assertThrows(IllegalArgumentException.class,()->service.mutate("schedule/publish",Map.of("id",stale),reviewer));
        service.mutate("schedule/restore",Map.of("id",first),admin);
        String restore;try(var c=db.open()){restore=(String)rows(c,"SELECT id FROM schedule_versions WHERE status='DRAFT' ORDER BY created_at DESC LIMIT 1").get(0).get("id");}publish(restore);
        try(var c=db.open()){assertEquals(2,rows(c,"SELECT * FROM schedule_versions WHERE status='PUBLISHED'").size());}
    }
    @Test void alternatingWeeksCanShareRoomAndPeriodAndRespectCalendar() throws Exception {
        db.transaction(c->{exec(c,"DELETE FROM courses WHERE id IN ('course-2','course-3')");exec(c,"UPDATE courses SET sessions=1");exec(c,"DELETE FROM rooms WHERE id<>'room-0'");return null;});
        configure(List.of(0),List.of("09:00-10:00"));
        service.mutate("course/pattern",Map.of("id","course-0","week_pattern","A"),admin);service.mutate("course/pattern",Map.of("id","course-1","week_pattern","B"),admin);
        publish(draft());try(var c=db.open()){assertEquals(2,rows(c,"SELECT * FROM lessons").size());assertEquals(4,Academic.occurrences(c).size());assertTrue(Scheduler.conflicts(c).isEmpty());}
        service.mutate("holiday",Map.of("holiday_date","2026-11-09","label","Holiday"),admin);
        service.mutate("leave",Map.of("faculty_id","faculty-0","start_date","2026-11-16","end_date","2026-11-16"),admin);
        try(var c=db.open()){var occurrences=Academic.occurrences(c);assertEquals(2,occurrences.size());assertTrue(occurrences.stream().noneMatch(l->List.of("2026-11-09","2026-11-16").contains(l.get("date"))));}
    }
    @Test void multiPeriodExamsRequireDistinctAvailableStaffForEveryPeriod() throws Exception {
        service.mutate("leave",Map.of("faculty_id","faculty-3","start_date","2026-11-07","end_date","2026-11-07"),admin);
        service.mutate("exams",Map.of("course_id","course-0","room_id","room-0","exam_date","2026-11-07","slot_no",1,"duration",2,"required_staff",2),admin);
        service.mutate("allocate",Map.of(),admin);
        try(var c=db.open()){var staff=rows(c,"SELECT * FROM exam_staff");assertEquals(2,staff.size());assertEquals(Set.of("faculty-1","faculty-2"),new HashSet<>(staff.stream().map(r->r.get("faculty_id")).toList()));}
        assertThrows(IllegalArgumentException.class,()->service.mutate("availability",Map.of("faculty_id","faculty-1","day_no",5,"slot_no",2,"available",false),admin));
        assertThrows(IllegalArgumentException.class,()->service.mutate("exams",Map.of("course_id","course-1","room_id","room-0","exam_date","2026-11-07","slot_no",2,"duration",2,"required_staff",1),admin));
        assertThrows(IllegalArgumentException.class,()->service.mutate("exams",Map.of("course_id","course-1","room_id","room-1","exam_date","2026-11-07","slot_no",5,"duration",2),admin));
    }
    @Test void examOnInactiveWeekDoesNotBlockTeachingAndInvalidConfigRollsBack() throws Exception {
        service.mutate("course/pattern",Map.of("id","course-0","week_pattern","A"),admin);publish(draft());
        Map<String,Object> lesson;try(var c=db.open()){lesson=rows(c,"SELECT * FROM lessons WHERE course_id='course-0' ORDER BY day_no,slot_no").get(0);}
        String date=java.time.LocalDate.parse("2026-11-09").plusDays(((Number)lesson.get("day_no")).intValue()).toString();
        service.mutate("exams",Map.of("course_id","course-0","room_id",lesson.get("room_id"),"exam_date",date,"slot_no",lesson.get("slot_no")),admin);
        assertThrows(IllegalArgumentException.class,()->configure(List.of(6),List.of("09:00-10:00")));
        try(var c=db.open()){assertEquals(List.of(0,1,2,3,4),Academic.settings(c).get("teaching_days"));}
    }
    @Test void csvImportIsAtomicSupportsQuotedNamesAndExportsOmitSecrets() throws Exception {
        service.mutate("imports",Map.of("entity","rooms","csv","name,capacity\r\n\"East, Hall\",80\r\n"),admin);
        assertThrows(IllegalArgumentException.class,()->service.mutate("imports",Map.of("entity","rooms","csv","name,capacity\nTemporary,40\nInvalid,-1"),admin));
        service.mutate("imports",Map.of("entity","courses","csv","code,title,faculty_email,cohort,students,sessions,week_pattern\nNEW,New course,ananya@campus.edu,Other,25,1,B"),admin);
        try(var c=db.open()){assertEquals(1,rows(c,"SELECT * FROM rooms WHERE name='East, Hall'").size());assertTrue(rows(c,"SELECT * FROM rooms WHERE name='Temporary'").isEmpty());assertFalse(ApiServlet.JSON.writeValueAsString(Exchange.snapshot(c)).contains("password_hash"));}
        assertThrows(SecurityException.class,()->service.mutate("imports",Map.of("entity","rooms","csv","name,capacity\nx,20"),faculty));
        assertThrows(IllegalArgumentException.class,()->Exchange.parse("name\n\"unclosed"));
    }
    @Test void deliveryQueueRetriesUsesIdempotencyAndNeverClaimsRecipientDelivery() throws Exception {
        assertFalse(Delivery.configured(Map.of("SMS_GATEWAY_URL","","SMS_GATEWAY_TOKEN",""),"SMS"));
        assertFalse(Delivery.configured(Map.of("SMS_GATEWAY_URL","http://example.com","SMS_GATEWAY_TOKEN","test"),"SMS"));
        service.mutate("delivery/preferences",Map.of("channel","SMS","destination","+919876543210"),faculty);
        service.mutate("announce",Map.of("body","Test announcement"),admin);
        try(var worker=new Delivery(db,Map.of("SMS_GATEWAY_URL","https://gateway.example/send","SMS_GATEWAY_TOKEN","test"),request->{assertTrue(request.headers().firstValue("Idempotency-Key").isPresent());return 503;})){worker.drain();}
        try(var c=db.open()){assertEquals("RETRY",rows(c,"SELECT * FROM delivery_outbox").get(0).get("status"));exec(c,"UPDATE delivery_outbox SET next_attempt=0");}
        try(var worker=new Delivery(db,Map.of("SMS_GATEWAY_URL","https://gateway.example/send","SMS_GATEWAY_TOKEN","test"),request->202)){worker.drain();}
        try(var c=db.open()){assertEquals("ACCEPTED",rows(c,"SELECT * FROM delivery_outbox").get(0).get("status"));}
    }
    @Test void calendarExportUsesCampusTimezoneAndOmitsCancelledOccurrences() throws Exception {
        publish(draft());service.mutate("holiday",Map.of("holiday_date","2026-11-02","label","Holiday"),admin);
        try(var c=db.open()){String ics=Exchange.calendar(c);assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"));assertFalse(ics.contains("DTSTART:20261102"));assertTrue(ics.contains("T033000Z"));}
    }
    @Test void upgradeIsRepeatableAndKeepsExistingRecords() throws Exception {
        publish(draft());try(var c=db.open()){exec(c,"DELETE FROM schedule_versions");exec(c,"ALTER TABLE lessons ADD CONSTRAINT legacy_room UNIQUE(room_id,day_no,slot_no)");}
        db.initialize(true);db.initialize(true);try(var c=db.open()){assertEquals(12,rows(c,"SELECT * FROM lessons").size());assertEquals(6,rows(c,"SELECT * FROM users").size());assertEquals("Publication before calendar upgrade",rows(c,"SELECT title FROM schedule_versions").get(0).get("title"));}
    }
    @Test void manualMoveStaysInDraftUntilReviewedAndPublished() throws Exception {
        publish(draft());Map<String,Object> lesson;try(var c=db.open()){lesson=rows(c,"SELECT * FROM lessons ORDER BY id").get(0);}
        service.mutate("move",Map.of("id",lesson.get("id"),"room_id","room-0","day_no",4,"slot_no",5),admin);
        String changed;try(var c=db.open()){assertEquals(lesson,rows(c,"SELECT * FROM lessons WHERE id=?",lesson.get("id")).get(0));changed=(String)rows(c,"SELECT id FROM schedule_versions WHERE status='DRAFT'").get(0).get("id");}
        publish(changed);try(var c=db.open()){assertEquals(5,rows(c,"SELECT slot_no FROM lessons WHERE id=?",lesson.get("id")).get(0).get("slot_no"));}
    }
}
