package edu.campus;

import java.sql.*;
import java.util.*;

/** JDBC repository. Values are always bound parameters; table names are internal constants. */
public final class Database {
    private final String url, username, password;
    public Database(String url, String username, String password) { this.url=url; this.username=username; this.password=password; }
    public Connection open() throws SQLException { return DriverManager.getConnection(url, username, password); }
    public interface Work<T> { T run(Connection connection) throws Exception; }
    public <T> T transaction(Work<T> work) throws Exception {
        try (Connection c=open()) {
            c.setAutoCommit(false);
            // Serialize schedule/resource edits even across application instances.
            try (PreparedStatement p=c.prepareStatement("SELECT id FROM coordination_lock WHERE id=1 FOR UPDATE")) { p.executeQuery().close(); }
            try { T result=work.run(c); c.commit(); return result; }
            catch (Exception e) { c.rollback(); throw e; }
        }
    }
    public static List<Map<String,Object>> rows(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p=c.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) p.setObject(i+1,args[i]);
            try(ResultSet rs=p.executeQuery()) {
                List<Map<String,Object>> result=new ArrayList<>();
                while(rs.next()) { Map<String,Object> row=new LinkedHashMap<>(); for(int i=1;i<=rs.getMetaData().getColumnCount();i++) row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i)); result.add(row); }
                return result;
            }
        }
    }
    public static int exec(Connection c,String sql,Object...args) throws SQLException {
        try(PreparedStatement p=c.prepareStatement(sql)) { for(int i=0;i<args.length;i++) p.setObject(i+1,args[i]); return p.executeUpdate(); }
    }
    public static String id() { return UUID.randomUUID().toString(); }
    public void initialize(boolean seed) throws Exception {
        try(Connection c=open(); Statement s=c.createStatement()) {
            for(String ddl: List.of(
                "CREATE TABLE IF NOT EXISTS coordination_lock(id INT PRIMARY KEY)",
                "CREATE TABLE IF NOT EXISTS users(id VARCHAR(36) PRIMARY KEY,name VARCHAR(100) NOT NULL,email VARCHAR(150) NOT NULL UNIQUE,password_hash VARCHAR(200) NOT NULL,role VARCHAR(10) NOT NULL,department VARCHAR(100) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS rooms(id VARCHAR(36) PRIMARY KEY,name VARCHAR(80) NOT NULL UNIQUE,capacity INT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS courses(id VARCHAR(36) PRIMARY KEY,code VARCHAR(30) NOT NULL UNIQUE,title VARCHAR(120) NOT NULL,faculty_id VARCHAR(36) NOT NULL,cohort VARCHAR(60) NOT NULL,students INT NOT NULL,sessions INT NOT NULL,FOREIGN KEY(faculty_id) REFERENCES users(id))",
                "CREATE TABLE IF NOT EXISTS unavailable(id VARCHAR(36) PRIMARY KEY,faculty_id VARCHAR(36) NOT NULL,day_no INT NOT NULL,slot_no INT NOT NULL,UNIQUE(faculty_id,day_no,slot_no),FOREIGN KEY(faculty_id) REFERENCES users(id))",
                "CREATE TABLE IF NOT EXISTS lessons(id VARCHAR(36) PRIMARY KEY,course_id VARCHAR(36) NOT NULL,room_id VARCHAR(36) NOT NULL,day_no INT NOT NULL,slot_no INT NOT NULL,UNIQUE(room_id,day_no,slot_no),FOREIGN KEY(course_id) REFERENCES courses(id),FOREIGN KEY(room_id) REFERENCES rooms(id))",
                "CREATE TABLE IF NOT EXISTS exams(id VARCHAR(36) PRIMARY KEY,course_id VARCHAR(36) NOT NULL,room_id VARCHAR(36) NOT NULL,exam_date VARCHAR(10) NOT NULL,slot_no INT NOT NULL,invigilator_id VARCHAR(36),UNIQUE(room_id,exam_date,slot_no),FOREIGN KEY(course_id) REFERENCES courses(id),FOREIGN KEY(room_id) REFERENCES rooms(id),FOREIGN KEY(invigilator_id) REFERENCES users(id))",
                "CREATE TABLE IF NOT EXISTS messages(id VARCHAR(36) PRIMARY KEY,sender_id VARCHAR(36) NOT NULL,body VARCHAR(2000) NOT NULL,created_at BIGINT NOT NULL,FOREIGN KEY(sender_id) REFERENCES users(id))",
                "CREATE TABLE IF NOT EXISTS notifications(id VARCHAR(36) PRIMARY KEY,body VARCHAR(500) NOT NULL,created_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS notification_reads(user_id VARCHAR(36) NOT NULL,notification_id VARCHAR(36) NOT NULL,PRIMARY KEY(user_id,notification_id),FOREIGN KEY(user_id) REFERENCES users(id),FOREIGN KEY(notification_id) REFERENCES notifications(id))"
            )) s.execute(ddl);
            if(rows(c,"SELECT id FROM coordination_lock").isEmpty()) exec(c,"INSERT INTO coordination_lock VALUES(1)");
        }
        transaction(c -> {
            if(rows(c,"SELECT id FROM users").isEmpty()) {
                String adminPassword=System.getenv().getOrDefault("ADMIN_PASSWORD","Admin@12345");
                exec(c,"INSERT INTO users VALUES(?,?,?,?,?,?)","admin","Campus Administrator","admin@campus.edu",Passwords.hash(adminPassword),"ADMIN","Administration");
                if(seed) {
                    String[] names={"Ananya Sharma","Rahul Mehta","Priya Nair","Arjun Rao"};
                    String[] emails={"ananya","rahul","priya","arjun"};
                    for(int i=0;i<4;i++) exec(c,"INSERT INTO users VALUES(?,?,?,?,?,?)","faculty-"+i,names[i],emails[i]+"@campus.edu",Passwords.hash("Faculty@12345"),"FACULTY",i==3?"Mathematics":"Computer Science");
                    exec(c,"INSERT INTO rooms VALUES(?,?,?)","room-0","Lecture Hall 101",80);
                    exec(c,"INSERT INTO rooms VALUES(?,?,?)","room-1","Computer Lab 02",40);
                    exec(c,"INSERT INTO rooms VALUES(?,?,?)","room-2","Seminar Hall",120);
                    String[] titles={"Advanced Java Programming","Database Management Systems","Computer Networks","Discrete Mathematics"};
                    for(int i=0;i<4;i++) exec(c,"INSERT INTO courses VALUES(?,?,?,?,?,?,?)","course-"+i,"CS"+(301+i),titles[i],"faculty-"+i,"B.Tech CSE · Semester 5",36,3);
                    notify(c,"Welcome to Campus Connect. Add your resources, then generate the weekly timetable.");
                }
            }
            return null;
        });
    }
    public static void notify(Connection c,String message) throws SQLException { exec(c,"INSERT INTO notifications VALUES(?,?,?)",id(),message,System.currentTimeMillis()); }
}
