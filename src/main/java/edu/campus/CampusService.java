package edu.campus;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static edu.campus.Database.*;

public final class CampusService {
    public final Database db;
    public CampusService(Database db) { this.db=db; }
    public Map<String,Object> login(String email,String password) throws Exception {
        try(Connection c=db.open()) {
            var users=rows(c,"SELECT * FROM users WHERE email=?",email.toLowerCase(Locale.ROOT).trim());
            if(users.isEmpty()||!Passwords.matches(password,(String)users.get(0).get("password_hash"))) return null;
            var user=users.get(0); user.remove("password_hash"); return user;
        }
    }
    public Map<String,Object> state(String userId) throws Exception {
        return db.transaction(c -> {
            Map<String,Object> state=new LinkedHashMap<>();
            state.put("users",rows(c,"SELECT id,name,email,role,department FROM users ORDER BY name"));
            state.put("rooms",rows(c,"SELECT * FROM rooms ORDER BY name"));
            state.put("courses",rows(c,"SELECT co.*,u.name AS faculty_name FROM courses co JOIN users u ON u.id=co.faculty_id ORDER BY code"));
            state.put("lessons",rows(c,"SELECT l.*,co.code,co.title,co.cohort,co.faculty_id,u.name AS faculty_name,r.name AS room_name FROM lessons l JOIN courses co ON co.id=l.course_id JOIN users u ON u.id=co.faculty_id JOIN rooms r ON r.id=l.room_id ORDER BY day_no,slot_no,co.code"));
            state.put("exams",rows(c,"SELECT e.*,co.code,co.title,co.cohort,r.name AS room_name,u.name AS invigilator_name FROM exams e JOIN courses co ON co.id=e.course_id JOIN rooms r ON r.id=e.room_id LEFT JOIN users u ON u.id=e.invigilator_id ORDER BY exam_date,slot_no"));
            state.put("unavailable",rows(c,"SELECT * FROM unavailable ORDER BY day_no,slot_no"));
            state.put("notifications",rows(c,"SELECT n.*,CASE WHEN nr.user_id IS NULL THEN 0 ELSE 1 END AS is_read FROM notifications n LEFT JOIN notification_reads nr ON nr.notification_id=n.id AND nr.user_id=? ORDER BY created_at DESC LIMIT 100",userId));
            state.put("conflicts",Scheduler.conflicts(c));
            return state;
        });
    }
    public List<Map<String,Object>> messages() throws Exception {
        try(Connection c=db.open()) { var result=rows(c,"SELECT m.*,u.name AS sender_name,u.role FROM messages m JOIN users u ON u.id=m.sender_id ORDER BY created_at DESC,id DESC LIMIT 100"); Collections.reverse(result); return result; }
    }
    public void message(String userId,String body) throws Exception {
        if(body==null||body.isBlank()||body.length()>2000) throw new IllegalArgumentException("Messages must contain 1–2000 characters.");
        db.transaction(c->{exec(c,"INSERT INTO messages VALUES(?,?,?,?)",id(),userId,body.trim(),System.currentTimeMillis()); return null;});
    }
    public void mutate(String action,Map<String,Object> b,Map<String,Object> user) throws Exception {
        boolean admin="ADMIN".equals(user.get("role")); String uid=(String)user.get("id");
        if(!admin&&!Set.of("availability","notifications/read","password").contains(action)) throw new SecurityException("Administrator access required.");
        db.transaction(c -> {
            switch(action) {
                case "users" -> { String role=required(b,"role",10); if(!Set.of("ADMIN","FACULTY").contains(role)) throw new IllegalArgumentException("Invalid role."); String email=required(b,"email",150).toLowerCase(Locale.ROOT); if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Enter a valid email address."); exec(c,"INSERT INTO users VALUES(?,?,?,?,?,?)",id(),required(b,"name",100),email,Passwords.hash(password(b,"password")),role,required(b,"department",100)); }
                case "rooms" -> exec(c,"INSERT INTO rooms VALUES(?,?,?)",id(),required(b,"name",80),integer(b,"capacity",1,2000));
                case "courses" -> { String fid=required(b,"faculty_id",36); faculty(c,fid); exec(c,"INSERT INTO courses VALUES(?,?,?,?,?,?,?)",id(),required(b,"code",30),required(b,"title",120),fid,required(b,"cohort",60),integer(b,"students",1,2000),integer(b,"sessions",1,15)); }
                case "exams" -> { String date=required(b,"exam_date",10); LocalDate.parse(date); exec(c,"INSERT INTO exams VALUES(?,?,?,?,?,NULL)",id(),required(b,"course_id",36),required(b,"room_id",36),date,integer(b,"slot_no",0,5)); Scheduler.requireValid(c); Database.notify(c,"An examination was scheduled for "+date+". Invigilation allocation is pending."); }
                case "generate" -> new Scheduler().generate(c);
                case "allocate" -> new Scheduler().allocate(c);
                case "move" -> { int count=exec(c,"UPDATE lessons SET room_id=?,day_no=?,slot_no=? WHERE id=?",required(b,"room_id",36),integer(b,"day_no",0,4),integer(b,"slot_no",0,5),required(b,"id",36)); if(count==0) throw new IllegalArgumentException("Class not found."); Scheduler.requireValid(c); Database.notify(c,"A class was rescheduled. Please check the updated timetable."); }
                case "availability" -> { String fid=admin?required(b,"faculty_id",36):uid; faculty(c,fid); int day=integer(b,"day_no",0,4),slot=integer(b,"slot_no",0,5); exec(c,"DELETE FROM unavailable WHERE faculty_id=? AND day_no=? AND slot_no=?",fid,day,slot); if(!Boolean.TRUE.equals(b.get("available"))) exec(c,"INSERT INTO unavailable VALUES(?,?,?,?)",id(),fid,day,slot); Scheduler.requireValid(c); }
                case "notifications/read" -> { var list=rows(c,"SELECT n.id FROM notifications n WHERE NOT EXISTS(SELECT 1 FROM notification_reads r WHERE r.notification_id=n.id AND r.user_id=?)",uid); for(var n:list) exec(c,"INSERT INTO notification_reads VALUES(?,?)",uid,n.get("id")); }
                case "announce" -> Database.notify(c,required(b,"body",500));
                case "password" -> { var current=rows(c,"SELECT password_hash FROM users WHERE id=?",uid); if(!Passwords.matches(required(b,"current",128),(String)current.get(0).get("password_hash"))) throw new IllegalArgumentException("Current password is incorrect."); exec(c,"UPDATE users SET password_hash=? WHERE id=?",Passwords.hash(password(b,"password")),uid); }
                case "delete" -> {
                    String entity=required(b,"entity",20),key=required(b,"id",36);
                    switch(entity) {
                        case "courses" -> { exec(c,"DELETE FROM lessons WHERE course_id=?",key); exec(c,"DELETE FROM exams WHERE course_id=?",key); exec(c,"DELETE FROM courses WHERE id=?",key); }
                        case "rooms" -> exec(c,"DELETE FROM rooms WHERE id=?",key);
                        case "exams" -> exec(c,"DELETE FROM exams WHERE id=?",key);
                        default -> throw new IllegalArgumentException("Unsupported delete operation.");
                    }
                    Database.notify(c,"An administrator removed a "+entity+" record. Check the latest schedule.");
                }
                default -> throw new IllegalArgumentException("Unknown operation.");
            }
            return null;
        });
    }
    private static void faculty(Connection c,String id) throws SQLException { if(rows(c,"SELECT id FROM users WHERE id=? AND role='FACULTY'",id).isEmpty()) throw new IllegalArgumentException("Select a faculty member."); }
    static String required(Map<String,Object> b,String key,int max) { Object raw=b.get(key); if(!(raw instanceof String s)||s.isBlank()||s.length()>max) throw new IllegalArgumentException(key+" is required (maximum "+max+" characters)."); return s.trim(); }
    static String password(Map<String,Object> b,String key) { String s=required(b,key,128); if(s.length()<10) throw new IllegalArgumentException("Use a password of at least 10 characters."); return s; }
    static int integer(Map<String,Object> b,String key,int min,int max) { try { int n=Integer.parseInt(String.valueOf(b.get(key))); if(n<min||n>max) throw new NumberFormatException(); return n; } catch(NumberFormatException e) { throw new IllegalArgumentException(key+" must be between "+min+" and "+max+"."); } }
}
