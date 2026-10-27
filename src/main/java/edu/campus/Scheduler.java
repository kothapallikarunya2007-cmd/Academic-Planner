package edu.campus;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static edu.campus.Database.*;

/** Bounded backtracking with collection-based constraints and atomic publication. */
public final class Scheduler {
    public static final int DAYS=5, SLOTS=6;
    static String str(Map<String,Object> r,String k) { return String.valueOf(r.get(k)); }
    static int num(Map<String,Object> r,String k) { return ((Number)r.get(k)).intValue(); }
    static int weekday(String date) { return LocalDate.parse(date).getDayOfWeek().getValue()-1; }
    record Placement(String course,String room,int day,int slot) {}
    private int visits;
    public void generate(Connection c) throws Exception {
        var courses=rows(c,"SELECT * FROM courses ORDER BY students DESC,sessions DESC,code");
        var rooms=rows(c,"SELECT * FROM rooms ORDER BY capacity,name");
        var unavailable=rows(c,"SELECT * FROM unavailable");
        var exams=rows(c,"SELECT e.*,co.faculty_id,co.cohort FROM exams e JOIN courses co ON e.course_id=co.id");
        if(courses.isEmpty()) throw new IllegalArgumentException("Add at least one course before generating a timetable.");
        List<Map<String,Object>> tasks=new ArrayList<>();
        for(var course:courses) {
            if(rooms.stream().noneMatch(r->num(r,"capacity")>=num(course,"students"))) throw new IllegalArgumentException("No room has enough capacity for "+str(course,"code")+".");
            for(int i=0;i<num(course,"sessions");i++) tasks.add(course);
        }
        List<Placement> placed=new ArrayList<>(); visits=0;
        if(!place(0,tasks,rooms,unavailable,exams,placed)) throw new IllegalArgumentException("No timetable found within the search limit. Add rooms, reduce sessions, or relax faculty availability. The published timetable was preserved.");
        exec(c,"DELETE FROM lessons");
        for(var p:placed) exec(c,"INSERT INTO lessons VALUES(?,?,?,?,?)",id(),p.course,p.room,p.day,p.slot);
        Database.notify(c,"Weekly timetable published: "+placed.size()+" sessions scheduled without conflicts.");
    }
    private boolean place(int index,List<Map<String,Object>> tasks,List<Map<String,Object>> rooms,List<Map<String,Object>> unavailable,List<Map<String,Object>> exams,List<Placement> placed) {
        if(index==tasks.size()) return true;
        if(++visits>200000) return false;
        var course=tasks.get(index); String faculty=str(course,"faculty_id"),cohort=str(course,"cohort"),courseId=str(course,"id");
        // Prefer spreading sessions through the week; fall back to every legal slot.
        for(int slot=0;slot<SLOTS;slot++) for(int day=0;day<DAYS;day++) for(var room:rooms) {
            String roomId=str(room,"id"); boolean blocked=num(room,"capacity")<num(course,"students");
            for(var u:unavailable) if(str(u,"faculty_id").equals(faculty)&&num(u,"day_no")==day&&num(u,"slot_no")==slot) blocked=true;
            for(int j=0;j<placed.size();j++) {
                var p=placed.get(j); var other=tasks.get(j);
                if(p.day==day&&p.slot==slot&&(p.room.equals(roomId)||str(other,"faculty_id").equals(faculty)||str(other,"cohort").equals(cohort))) blocked=true;
            }
            for(var e:exams) if(weekday(str(e,"exam_date"))==day&&num(e,"slot_no")==slot&&(str(e,"room_id").equals(roomId)||str(e,"cohort").equals(cohort)||faculty.equals(e.get("invigilator_id")))) blocked=true;
            if(!blocked) { placed.add(new Placement(courseId,roomId,day,slot)); if(place(index+1,tasks,rooms,unavailable,exams,placed)) return true; placed.remove(placed.size()-1); }
        }
        return false;
    }
    public void allocate(Connection c) throws Exception {
        var faculty=rows(c,"SELECT id FROM users WHERE role='FACULTY' ORDER BY name");
        var exams=rows(c,"SELECT e.*,co.faculty_id,co.cohort FROM exams e JOIN courses co ON co.id=e.course_id ORDER BY exam_date,slot_no,e.id");
        if(exams.isEmpty()) throw new IllegalArgumentException("Add an examination first.");
        var lessons=rows(c,"SELECT l.*,co.faculty_id FROM lessons l JOIN courses co ON co.id=l.course_id");
        var unavailable=rows(c,"SELECT * FROM unavailable");
        Map<String,Integer> load=new HashMap<>(); Map<String,String> chosen=new LinkedHashMap<>(); visits=0;
        if(!assign(0,exams,faculty,lessons,unavailable,load,chosen)) throw new IllegalArgumentException("Not enough available invigilators. Course instructors cannot supervise their own exams. Previous allocations were preserved.");
        for(var e:chosen.entrySet()) exec(c,"UPDATE exams SET invigilator_id=? WHERE id=?",e.getValue(),e.getKey());
        Database.notify(c,"Invigilation duties published for "+exams.size()+" examinations.");
    }
    private boolean assign(int index,List<Map<String,Object>> exams,List<Map<String,Object>> faculty,List<Map<String,Object>> lessons,List<Map<String,Object>> unavailable,Map<String,Integer> load,Map<String,String> chosen) {
        if(index==exams.size()) return true; if(++visits>200000) return false;
        var exam=exams.get(index); int day=weekday(str(exam,"exam_date")),slot=num(exam,"slot_no");
        var candidates=new ArrayList<>(faculty); candidates.sort(Comparator.comparingInt(f->load.getOrDefault(str(f,"id"),0)));
        for(var f:candidates) {
            String fid=str(f,"id"); boolean busy=fid.equals(exam.get("faculty_id"));
            for(var u:unavailable) if(fid.equals(u.get("faculty_id"))&&day==num(u,"day_no")&&slot==num(u,"slot_no")) busy=true;
            for(var l:lessons) if(fid.equals(l.get("faculty_id"))&&day==num(l,"day_no")&&slot==num(l,"slot_no")) busy=true;
            for(int j=0;j<index;j++) { var e=exams.get(j); if(str(e,"exam_date").equals(str(exam,"exam_date"))&&num(e,"slot_no")==slot&&fid.equals(chosen.get(str(e,"id")))) busy=true; }
            if(!busy) { chosen.put(str(exam,"id"),fid); load.merge(fid,1,Integer::sum); if(assign(index+1,exams,faculty,lessons,unavailable,load,chosen)) return true; load.merge(fid,-1,Integer::sum); chosen.remove(str(exam,"id")); }
        }
        return false;
    }
    /** Checks every persisted assignment, including conflicts across modules. */
    public static List<String> conflicts(Connection c) throws SQLException {
        var lessons=rows(c,"SELECT l.*,co.faculty_id,co.cohort,co.code,co.students,r.capacity FROM lessons l JOIN courses co ON co.id=l.course_id JOIN rooms r ON r.id=l.room_id");
        var exams=rows(c,"SELECT e.*,co.faculty_id,co.cohort,co.code,co.students,r.capacity FROM exams e JOIN courses co ON co.id=e.course_id JOIN rooms r ON r.id=e.room_id");
        var unavailable=rows(c,"SELECT * FROM unavailable"); Set<String> errors=new LinkedHashSet<>();
        for(int i=0;i<lessons.size();i++) {
            var a=lessons.get(i);
            if(num(a,"students")>num(a,"capacity")) errors.add(str(a,"code")+": classroom capacity exceeded.");
            for(var u:unavailable) if(a.get("faculty_id").equals(u.get("faculty_id"))&&num(a,"day_no")==num(u,"day_no")&&num(a,"slot_no")==num(u,"slot_no")) errors.add(str(a,"code")+": faculty is unavailable.");
            for(int j=i+1;j<lessons.size();j++) { var b=lessons.get(j); if(num(a,"day_no")==num(b,"day_no")&&num(a,"slot_no")==num(b,"slot_no")&&(a.get("faculty_id").equals(b.get("faculty_id"))||a.get("cohort").equals(b.get("cohort"))||a.get("room_id").equals(b.get("room_id")))) errors.add(str(a,"code")+" and "+str(b,"code")+": overlapping classes."); }
        }
        for(int i=0;i<exams.size();i++) {
            var a=exams.get(i); int day=weekday(str(a,"exam_date")); Object inv=a.get("invigilator_id");
            if(num(a,"students")>num(a,"capacity")) errors.add(str(a,"code")+": exam room capacity exceeded.");
            if(a.get("faculty_id").equals(inv)) errors.add(str(a,"code")+": instructor cannot invigilate their own exam.");
            for(var u:unavailable) if(u.get("faculty_id").equals(inv)&&day==num(u,"day_no")&&num(a,"slot_no")==num(u,"slot_no")) errors.add(str(a,"code")+": invigilator unavailable.");
            for(var l:lessons) if(day==num(l,"day_no")&&num(a,"slot_no")==num(l,"slot_no")&&(a.get("room_id").equals(l.get("room_id"))||a.get("cohort").equals(l.get("cohort"))||l.get("faculty_id").equals(inv))) errors.add(str(a,"code")+": exam overlaps a weekly class; move the class or exam.");
            for(int j=i+1;j<exams.size();j++) { var b=exams.get(j); if(a.get("exam_date").equals(b.get("exam_date"))&&num(a,"slot_no")==num(b,"slot_no")&&(a.get("room_id").equals(b.get("room_id"))||a.get("cohort").equals(b.get("cohort"))||(inv!=null&&inv.equals(b.get("invigilator_id"))))) errors.add(str(a,"code")+" and "+str(b,"code")+": overlapping examinations."); }
        }
        return new ArrayList<>(errors);
    }
    static void requireValid(Connection c) throws SQLException { var errors=conflicts(c); if(!errors.isEmpty()) throw new IllegalArgumentException(String.join(" ",errors)); }
}
