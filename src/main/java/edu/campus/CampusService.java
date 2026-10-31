package edu.campus;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static edu.campus.Database.*;

public final class CampusService {
    public final Database db;
    public CampusService(Database db) {this.db=db;}
    public Map<String,Object> login(String email,String password) throws Exception {
        try(var c=db.open()){var list=rows(c,"SELECT * FROM users WHERE email=?",email.toLowerCase(Locale.ROOT).trim());if(list.isEmpty()||!Passwords.matches(password,(String)list.get(0).get("password_hash")))return null;var user=list.get(0);user.remove("password_hash");return user;}
    }
    public Map<String,Object> state(String uid) throws Exception {
        return db.transaction(c->{
            var state=new LinkedHashMap<String,Object>();
            var users=rows(c,"SELECT id,name,email,role,department FROM users ORDER BY name");state.put("users",users);
            state.put("rooms",rows(c,"SELECT * FROM rooms ORDER BY name"));
            state.put("courses",rows(c,"SELECT co.*,u.name AS faculty_name,COALESCE(p.week_pattern,'ALL') AS week_pattern FROM courses co JOIN users u ON u.id=co.faculty_id LEFT JOIN course_patterns p ON p.course_id=co.id ORDER BY code"));
            state.put("lessons",rows(c,Academic.lessonsSql()+" ORDER BY day_no,slot_no,co.code"));
            var exams=rows(c,Academic.examsSql()+" ORDER BY exam_date,slot_no");
            for(var e:exams){var staff=Academic.staff(c,e);e.put("invigilator_ids",staff);e.put("invigilator_name",String.join(", ",users.stream().filter(u->staff.contains(u.get("id"))).map(u->(String)u.get("name")).toList()));}
            state.put("exams",exams);state.put("unavailable",rows(c,"SELECT * FROM unavailable"));
            state.put("settings",Academic.settings(c));state.put("holidays",rows(c,"SELECT * FROM holidays ORDER BY holiday_date"));state.put("leave",rows(c,"SELECT l.*,u.name FROM faculty_leave l JOIN users u ON u.id=l.faculty_id ORDER BY start_date"));
            state.put("notifications",rows(c,"SELECT n.*,CASE WHEN nr.user_id IS NULL THEN 0 ELSE 1 END AS is_read FROM notifications n LEFT JOIN notification_reads nr ON nr.notification_id=n.id AND nr.user_id=? ORDER BY created_at DESC LIMIT 100",uid));
            state.put("conflicts",Scheduler.conflicts(c));state.put("preferences",rows(c,"SELECT channel,destination FROM delivery_preferences WHERE user_id=?",uid));
            boolean admin=users.stream().anyMatch(u->uid.equals(u.get("id"))&&"ADMIN".equals(u.get("role")));
            if(admin){state.put("versions",rows(c,"SELECT * FROM schedule_versions ORDER BY created_at DESC LIMIT 100"));state.put("deliveries",rows(c,"SELECT id,channel,status,attempts,last_error,created_at FROM delivery_outbox ORDER BY created_at DESC LIMIT 100"));var gateways=new LinkedHashMap<String,Boolean>();for(String channel:List.of("EMAIL","SMS","PUSH","INSTITUTION"))gateways.put(channel,Delivery.configured(System.getenv(),channel));state.put("gateways",gateways);}
            else state.put("versions",rows(c,"SELECT id,title,status,published_at FROM schedule_versions WHERE status='PUBLISHED' ORDER BY published_at DESC LIMIT 100"));
            var calendar=new Academic.Calendar(c);LocalDate today=LocalDate.now(java.time.ZoneId.of((String)calendar.settings.get("timezone")));int teachingDates=0;for(LocalDate date=calendar.start;!date.isAfter(calendar.end);date=date.plusDays(1))if(calendar.days.contains(date.getDayOfWeek().getValue()-1)&&!calendar.holidays.contains(date.toString()))teachingDates++;
            int roomCount=rows(c,"SELECT id FROM rooms").size();int capacity=teachingDates*calendar.periods.size()*roomCount;state.put("room_utilization",capacity==0?0:Math.round(Academic.occurrences(c).size()*100.0/capacity));
            state.put("today",today.toString());state.put("today_lessons",rows(c,Academic.lessonsSql()).stream().filter(l->calendar.occurs(l,today)).toList());
            return state;
        });
    }
    public List<Map<String,Object>> messages() throws Exception {try(var c=db.open()){var list=rows(c,"SELECT m.*,u.name AS sender_name,u.role FROM messages m JOIN users u ON u.id=m.sender_id ORDER BY created_at DESC,id DESC LIMIT 100");Collections.reverse(list);return list;}}
    public void message(String uid,String body) throws Exception {if(body==null||body.isBlank()||body.length()>2000)throw new IllegalArgumentException("Messages must contain 1–2000 characters.");db.transaction(c->{exec(c,"INSERT INTO messages VALUES(?,?,?,?)",id(),uid,body.trim(),System.currentTimeMillis());return null;});}
    public void mutate(String action,Map<String,Object> b,Map<String,Object> user) throws Exception {db.transaction(c->{apply(c,action,b,user);return null;});}
    void apply(Connection c,String action,Map<String,Object> b,Map<String,Object> user) throws Exception {
        boolean admin="ADMIN".equals(user.get("role"));String uid=(String)user.get("id");
        if(!admin&&!Set.of("availability","notifications/read","password","delivery/preferences").contains(action))throw new SecurityException("Administrator access required.");
        var cal=new Academic.Calendar(c);
        switch(action) {
            case "users" -> {String role=required(b,"role",10);if(!Set.of("ADMIN","FACULTY").contains(role))throw new IllegalArgumentException("Invalid role.");String email=required(b,"email",150).toLowerCase(Locale.ROOT);if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("Enter a valid email address.");exec(c,"INSERT INTO users VALUES(?,?,?,?,?,?)",id(),required(b,"name",100),email,Passwords.hash(password(b,"password")),role,required(b,"department",100));}
            case "rooms" -> exec(c,"INSERT INTO rooms VALUES(?,?,?)",id(),required(b,"name",80),integer(b,"capacity",1,2000));
            case "courses" -> {String fid=required(b,"faculty_id",36);faculty(c,fid);String key=id();exec(c,"INSERT INTO courses VALUES(?,?,?,?,?,?,?)",key,required(b,"code",30),required(b,"title",120),fid,required(b,"cohort",60),integer(b,"students",1,2000),integer(b,"sessions",1,15));setPattern(c,key,String.valueOf(b.getOrDefault("week_pattern","ALL")));}
            case "course/pattern" -> {setPattern(c,required(b,"id",36),required(b,"week_pattern",4));Scheduler.requireValid(c);}
            case "exams" -> {String key=id(),date=required(b,"exam_date",10);LocalDate.parse(date);exec(c,"INSERT INTO exams VALUES(?,?,?,?,?,NULL)",key,required(b,"course_id",36),required(b,"room_id",36),date,integer(b,"slot_no",0,cal.periods.size()-1));exec(c,"INSERT INTO exam_options VALUES(?,?,?)",key,integerDefault(b,"duration",1,1,cal.periods.size()),integerDefault(b,"required_staff",1,1,20));Scheduler.requireValid(c);Database.notify(c,"Examination scheduled for "+date+". Invigilation allocation is pending.");}
            case "generate","move","schedule/restore" -> ScheduleWorkflow.draft(c,uid,String.valueOf(b.getOrDefault("title",action.equals("generate")?"Generated timetable":action.equals("move")?"Class move":"Restored timetable")),action.equals("schedule/restore")?"restore":action,b);
            case "schedule/submit","schedule/approve","schedule/reject","schedule/publish" -> ScheduleWorkflow.transition(c,action,b,uid);
            case "allocate" -> new Scheduler().allocate(c);
            case "academic/settings" -> Academic.configure(c,b);
            case "holiday" -> {String date=required(b,"holiday_date",10);if(!cal.inTerm(LocalDate.parse(date)))throw new IllegalArgumentException("Holiday must be within the semester.");exec(c,"INSERT INTO holidays VALUES(?,?)",date,required(b,"label",100));Scheduler.requireValid(c);Database.notify(c,"Holiday added: "+date+". Teaching occurrences on that date are cancelled.");}
            case "leave" -> {String fid=required(b,"faculty_id",36);faculty(c,fid);var start=LocalDate.parse(required(b,"start_date",10));var end=LocalDate.parse(required(b,"end_date",10));if(end.isBefore(start)||!cal.inTerm(start)||!cal.inTerm(end))throw new IllegalArgumentException("Leave dates must be ordered and within the semester.");exec(c,"INSERT INTO faculty_leave VALUES(?,?,?,?)",id(),fid,start.toString(),end.toString());Scheduler.requireValid(c);Database.notify(c,"Faculty leave recorded for "+start+" through "+end+". Affected classes are cancelled; arrange replacements separately.");}
            case "availability" -> {String fid=admin?required(b,"faculty_id",36):uid;faculty(c,fid);int day=integer(b,"day_no",0,6),slot=integer(b,"slot_no",0,cal.periods.size()-1);exec(c,"DELETE FROM unavailable WHERE faculty_id=? AND day_no=? AND slot_no=?",fid,day,slot);if(!Boolean.TRUE.equals(b.get("available")))exec(c,"INSERT INTO unavailable VALUES(?,?,?,?)",id(),fid,day,slot);Scheduler.requireValid(c);}
            case "notifications/read" -> {for(var n:rows(c,"SELECT n.id FROM notifications n WHERE NOT EXISTS(SELECT 1 FROM notification_reads r WHERE r.notification_id=n.id AND r.user_id=?)",uid))exec(c,"INSERT INTO notification_reads VALUES(?,?)",uid,n.get("id"));}
            case "announce" -> Database.notify(c,required(b,"body",500));
            case "password" -> {var old=rows(c,"SELECT password_hash FROM users WHERE id=?",uid);if(!Passwords.matches(required(b,"current",128),(String)old.get(0).get("password_hash")))throw new IllegalArgumentException("Current password is incorrect.");exec(c,"UPDATE users SET password_hash=? WHERE id=?",Passwords.hash(password(b,"password")),uid);exec(c,"DELETE FROM app_sessions WHERE user_id=?",uid);}
            case "delivery/preferences" -> {String channel=required(b,"channel",12);if(!Set.of("EMAIL","SMS","PUSH").contains(channel))throw new IllegalArgumentException("Unsupported channel.");exec(c,"DELETE FROM delivery_preferences WHERE user_id=? AND channel=?",uid,channel);if(!Boolean.FALSE.equals(b.get("enabled"))){String dest=required(b,"destination",500);if(channel.equals("EMAIL")&&!dest.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("Enter an email address.");if(channel.equals("SMS")&&!dest.matches("\\+[1-9][0-9]{7,14}"))throw new IllegalArgumentException("Use an international phone number, e.g. +919876543210.");exec(c,"INSERT INTO delivery_preferences VALUES(?,?,?)",uid,channel,dest);}}
            case "delivery/retry" -> {if(exec(c,"UPDATE delivery_outbox SET status='PENDING',attempts=0,next_attempt=?,last_error=NULL WHERE id=? AND status='FAILED'",System.currentTimeMillis(),required(b,"id",36))==0)throw new IllegalArgumentException("Only failed deliveries can be retried.");}
            case "imports" -> Exchange.importCsv(c,this,b,user);
            case "integration/sync" -> Delivery.enqueue(c,"INSTITUTION","institution",ApiServlet.JSON.writeValueAsString(Exchange.snapshot(c)));
            case "delete" -> {
                String entity=required(b,"entity",20),key=required(b,"id",36);
                switch(entity){
                    case "courses" -> {exec(c,"DELETE FROM lessons WHERE course_id=?",key);exec(c,"DELETE FROM exams WHERE course_id=?",key);exec(c,"DELETE FROM courses WHERE id=?",key);}
                    case "rooms" -> exec(c,"DELETE FROM rooms WHERE id=?",key);
                    case "exams" -> exec(c,"DELETE FROM exams WHERE id=?",key);
                    case "holidays" -> exec(c,"DELETE FROM holidays WHERE holiday_date=?",key);
                    case "leave" -> exec(c,"DELETE FROM faculty_leave WHERE id=?",key);
                    default -> throw new IllegalArgumentException("Unsupported delete operation.");
                }
                Scheduler.requireValid(c);Database.notify(c,"An administrator removed a "+entity+" record. Check the latest schedule.");
            }
            default -> throw new IllegalArgumentException("Unknown operation.");
        }
    }
    static void setPattern(Connection c,String key,String pattern) throws SQLException {if(!Set.of("ALL","A","B").contains(pattern))throw new IllegalArgumentException("Week pattern must be ALL, A or B.");exec(c,"DELETE FROM course_patterns WHERE course_id=?",key);exec(c,"INSERT INTO course_patterns VALUES(?,?)",key,pattern);}
    static void faculty(Connection c,String id) throws SQLException {if(rows(c,"SELECT id FROM users WHERE id=? AND role='FACULTY'",id).isEmpty())throw new IllegalArgumentException("Select a faculty member.");}
    static String required(Map<String,Object> b,String key,int max) {Object raw=b.get(key);if(!(raw instanceof String s)||s.isBlank()||s.length()>max)throw new IllegalArgumentException(key+" is required (maximum "+max+" characters).");return s.trim();}
    static String password(Map<String,Object> b,String key) {String s=required(b,key,128);if(s.length()<10)throw new IllegalArgumentException("Use a password of at least 10 characters.");return s;}
    static int integerDefault(Map<String,Object> b,String key,int fallback,int min,int max){return b.containsKey(key)?integer(b,key,min,max):fallback;}
    static int integer(Map<String,Object> b,String key,int min,int max) {try{int n=Integer.parseInt(String.valueOf(b.get(key)));if(n<min||n>max)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new IllegalArgumentException(key+" must be between "+min+" and "+max+".");}}
}
