package edu.campus;

import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static edu.campus.Database.*;
import static edu.campus.CampusService.*;

/** Calendar configuration and additive schema upgrade for existing installations. */
final class Academic {
    static void upgrade(Connection c) throws Exception {
        for(String ddl:List.of(
            "CREATE TABLE IF NOT EXISTS academic_settings(id INT PRIMARY KEY,body TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS holidays(holiday_date VARCHAR(10) PRIMARY KEY,label VARCHAR(100) NOT NULL)",
            "CREATE TABLE IF NOT EXISTS faculty_leave(id VARCHAR(36) PRIMARY KEY,faculty_id VARCHAR(36) NOT NULL,start_date VARCHAR(10) NOT NULL,end_date VARCHAR(10) NOT NULL,FOREIGN KEY(faculty_id) REFERENCES users(id))",
            "CREATE TABLE IF NOT EXISTS course_patterns(course_id VARCHAR(36) PRIMARY KEY,week_pattern VARCHAR(4) NOT NULL,FOREIGN KEY(course_id) REFERENCES courses(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS exam_options(exam_id VARCHAR(36) PRIMARY KEY,duration INT NOT NULL,required_staff INT NOT NULL,FOREIGN KEY(exam_id) REFERENCES exams(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS exam_staff(exam_id VARCHAR(36) NOT NULL,faculty_id VARCHAR(36) NOT NULL,PRIMARY KEY(exam_id,faculty_id),FOREIGN KEY(exam_id) REFERENCES exams(id) ON DELETE CASCADE,FOREIGN KEY(faculty_id) REFERENCES users(id))",
            "CREATE TABLE IF NOT EXISTS schedule_versions(id VARCHAR(36) PRIMARY KEY,title VARCHAR(100) NOT NULL,status VARCHAR(12) NOT NULL,created_by VARCHAR(36) NOT NULL,reviewed_by VARCHAR(36),created_at BIGINT NOT NULL,published_at BIGINT,body TEXT NOT NULL,context_body TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS delivery_preferences(user_id VARCHAR(36) NOT NULL,channel VARCHAR(12) NOT NULL,destination VARCHAR(500) NOT NULL,PRIMARY KEY(user_id,channel),FOREIGN KEY(user_id) REFERENCES users(id))",
            "CREATE TABLE IF NOT EXISTS delivery_outbox(id VARCHAR(36) PRIMARY KEY,channel VARCHAR(12) NOT NULL,destination VARCHAR(500) NOT NULL,body TEXT NOT NULL,status VARCHAR(12) NOT NULL,attempts INT NOT NULL,next_attempt BIGINT NOT NULL,last_error VARCHAR(200),created_at BIGINT NOT NULL)"
        )) exec(c,ddl);
        // A/B weeks may share a room/slot. The coordinated validator enforces actual-date uniqueness.
        for(var r:rows(c,"SELECT constraint_name FROM information_schema.table_constraints WHERE LOWER(table_name)='lessons' AND table_schema=current_schema() AND constraint_type='UNIQUE'")) {
            String name=(String)r.get("constraint_name");
            if(!name.matches("[A-Za-z0-9_]+")) throw new SQLException("Unexpected constraint identifier");
            exec(c,"ALTER TABLE lessons DROP CONSTRAINT \""+name+"\"");
        }
        if(rows(c,"SELECT id FROM academic_settings WHERE id=1").isEmpty()) {
            int year=LocalDate.now(ZoneId.of("Asia/Kolkata")).getYear();
            exec(c,"INSERT INTO academic_settings VALUES(1,?)",ApiServlet.JSON.writeValueAsString(Map.of(
                "semester_start",year+"-01-01","semester_end",year+"-12-31","timezone","Asia/Kolkata",
                "teaching_days",List.of(0,1,2,3,4),"periods",List.of("09:00-10:00","10:00-11:00","11:15-12:15","13:00-14:00","14:00-15:00","15:15-16:15"))));
        }
        if(rows(c,"SELECT id FROM schedule_versions LIMIT 1").isEmpty()&&!rows(c,"SELECT id FROM lessons LIMIT 1").isEmpty()) {
            exec(c,"INSERT INTO schedule_versions VALUES(?,?,'PUBLISHED',?,NULL,?,?,?,?)",id(),"Publication before calendar upgrade","system",System.currentTimeMillis(),System.currentTimeMillis(),ApiServlet.JSON.writeValueAsString(rows(c,"SELECT * FROM lessons ORDER BY id")),ScheduleWorkflow.context(c));
        }
    }
    @SuppressWarnings("unchecked") static Map<String,Object> settings(Connection c) throws SQLException {
        try { return ApiServlet.JSON.readValue((String)rows(c,"SELECT body FROM academic_settings WHERE id=1").get(0).get("body"),Map.class); }
        catch(Exception e) { throw new SQLException("Cannot read academic settings",e); }
    }
    static void configure(Connection c,Map<String,Object> b) throws Exception {
        LocalDate start=LocalDate.parse(required(b,"semester_start",10)),end=LocalDate.parse(required(b,"semester_end",10));
        if(end.isBefore(start)||ChronoUnit.DAYS.between(start,end)>730) throw new IllegalArgumentException("Semester must span 1–731 days.");
        ZoneId.of(required(b,"timezone",80));
        if(!(b.get("teaching_days") instanceof List<?> days)||days.isEmpty()||days.size()>7||new HashSet<>(days).size()!=days.size()) throw new IllegalArgumentException("Choose unique teaching days.");
        var normalizedDays=new ArrayList<Integer>();for(Object day:days) normalizedDays.add(integer(Map.of("day",day),"day",0,6));
        if(new HashSet<>(normalizedDays).size()!=normalizedDays.size())throw new IllegalArgumentException("Choose unique teaching days.");
        Collections.sort(normalizedDays);
        if(!(b.get("periods") instanceof List<?> periods)||periods.isEmpty()||periods.size()>16) throw new IllegalArgumentException("Configure 1–16 periods.");
        LocalTime previous=LocalTime.MIN;
        for(Object p:periods) {
            String[] times=String.valueOf(p).split("-",-1);
            if(times.length!=2) throw new IllegalArgumentException("Periods must use HH:mm-HH:mm.");
            LocalTime from=LocalTime.parse(times[0]),to=LocalTime.parse(times[1]);
            if(!to.isAfter(from)||from.isBefore(previous)) throw new IllegalArgumentException("Periods must be ordered and must not overlap."); previous=to;
        }
        exec(c,"UPDATE academic_settings SET body=? WHERE id=1",ApiServlet.JSON.writeValueAsString(Map.of("semester_start",start.toString(),"semester_end",end.toString(),"timezone",b.get("timezone"),"teaching_days",normalizedDays,"periods",periods)));
        Scheduler.requireValid(c);
    }
    static final class Calendar {
        final Map<String,Object> settings;
        final LocalDate start,end,anchor;
        final List<Integer> days=new ArrayList<>(); final List<String> periods=new ArrayList<>();
        final Set<String> holidays=new HashSet<>(); final List<Map<String,Object>> leave;
        Calendar(Connection c) throws SQLException {
            settings=settings(c); start=LocalDate.parse((String)settings.get("semester_start")); end=LocalDate.parse((String)settings.get("semester_end")); anchor=start.minusDays(start.getDayOfWeek().getValue()-1);
            for(Object d:(List<?>)settings.get("teaching_days")) days.add(Integer.parseInt(d.toString()));
            for(Object p:(List<?>)settings.get("periods")) periods.add(p.toString());
            for(var h:rows(c,"SELECT holiday_date FROM holidays")) holidays.add((String)h.get("holiday_date"));
            leave=rows(c,"SELECT * FROM faculty_leave");
        }
        boolean inTerm(LocalDate date) { return !date.isBefore(start)&&!date.isAfter(end); }
        boolean onLeave(String faculty,LocalDate date) { return leave.stream().anyMatch(l->faculty.equals(l.get("faculty_id"))&&!date.isBefore(LocalDate.parse((String)l.get("start_date")))&&!date.isAfter(LocalDate.parse((String)l.get("end_date")))); }
        boolean occurs(Map<String,Object> lesson,LocalDate date) {
            String pattern=String.valueOf(lesson.getOrDefault("week_pattern","ALL")); long week=ChronoUnit.WEEKS.between(anchor,date);
            return inTerm(date)&&days.contains(date.getDayOfWeek().getValue()-1)&&Scheduler.num(lesson,"day_no")==date.getDayOfWeek().getValue()-1&&!holidays.contains(date.toString())&&!onLeave((String)lesson.get("faculty_id"),date)&&(pattern.equals("ALL")||(pattern.equals("A")?week%2==0:week%2==1));
        }
        boolean overlap(Map<String,Object> a,Map<String,Object> b) {
            if(!a.get("day_no").equals(b.get("day_no"))) return false;
            for(LocalDate d=start;!d.isAfter(end);d=d.plusDays(1)) if(occurs(a,d)&&occurs(b,d)) return true;
            return false;
        }
    }
    static String lessonsSql() { return "SELECT l.*,co.faculty_id,co.cohort,co.code,co.title,co.students,r.capacity,r.name AS room_name,u.name AS faculty_name,COALESCE(p.week_pattern,'ALL') AS week_pattern FROM lessons l JOIN courses co ON co.id=l.course_id JOIN rooms r ON r.id=l.room_id JOIN users u ON u.id=co.faculty_id LEFT JOIN course_patterns p ON p.course_id=co.id"; }
    static String examsSql() { return "SELECT e.*,co.faculty_id,co.cohort,co.code,co.title,co.students,r.capacity,r.name AS room_name,COALESCE(o.duration,1) AS duration,COALESCE(o.required_staff,1) AS required_staff FROM exams e JOIN courses co ON co.id=e.course_id JOIN rooms r ON r.id=e.room_id LEFT JOIN exam_options o ON o.exam_id=e.id"; }
    static List<String> staff(Connection c,Map<String,Object> exam) throws SQLException { var result=new ArrayList<String>(); for(var s:rows(c,"SELECT faculty_id FROM exam_staff WHERE exam_id=?",exam.get("id"))) result.add((String)s.get("faculty_id")); if(result.isEmpty()&&exam.get("invigilator_id")!=null) result.add((String)exam.get("invigilator_id")); return result; }
    static List<Map<String,Object>> occurrences(Connection c) throws SQLException {
        var cal=new Calendar(c); var result=new ArrayList<Map<String,Object>>(); var lessons=rows(c,lessonsSql());
        for(LocalDate date=cal.start;!date.isAfter(cal.end);date=date.plusDays(1)) for(var l:lessons) if(cal.occurs(l,date)) { var row=new LinkedHashMap<>(l);row.put("date",date.toString());result.add(row); }
        return result;
    }
}
