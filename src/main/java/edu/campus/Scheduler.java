package edu.campus;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static edu.campus.Database.*;

/** Bounded backtracking; callers hold the database coordination lock. */
public final class Scheduler {
    public static final int DAYS=5,SLOTS=6;
    static String str(Map<String,Object> r,String k) { return String.valueOf(r.get(k)); }
    static int num(Map<String,Object> r,String k) { return ((Number)r.get(k)).intValue(); }
    static int weekday(String date) { return LocalDate.parse(date).getDayOfWeek().getValue()-1; }
    private int visits;
    private Academic.Calendar calendar;
    private List<Map<String,Object>> unavailable,exams;
    private Map<String,List<String>> staff;
    public void generate(Connection c) throws Exception {
        calendar=new Academic.Calendar(c); unavailable=rows(c,"SELECT * FROM unavailable"); exams=rows(c,Academic.examsSql());staff=new HashMap<>();
        for(var e:exams) staff.put(str(e,"id"),Academic.staff(c,e));
        var courses=rows(c,"SELECT co.*,COALESCE(p.week_pattern,'ALL') AS week_pattern FROM courses co LEFT JOIN course_patterns p ON p.course_id=co.id ORDER BY students DESC,sessions DESC,code");
        var rooms=rows(c,"SELECT * FROM rooms ORDER BY capacity,name");
        if(courses.isEmpty()) throw new IllegalArgumentException("Add at least one course first.");
        var tasks=new ArrayList<Map<String,Object>>();
        for(var co:courses) { if(rooms.stream().noneMatch(r->num(r,"capacity")>=num(co,"students"))) throw new IllegalArgumentException("No room has enough capacity for "+co.get("code")); for(int i=0;i<num(co,"sessions");i++) tasks.add(co); }
        var placed=new ArrayList<Map<String,Object>>(); visits=0;
        if(!place(0,tasks,rooms,placed)) throw new IllegalArgumentException("No complete schedule found within 200,000 search nodes. Existing publication is unchanged.");
        exec(c,"DELETE FROM lessons");
        for(var l:placed) exec(c,"INSERT INTO lessons VALUES(?,?,?,?,?)",id(),l.get("id"),l.get("room_id"),l.get("day_no"),l.get("slot_no"));
        requireValid(c);
    }
    private boolean unavailable(String fid,int day,int slot) { return unavailable.stream().anyMatch(u->fid.equals(u.get("faculty_id"))&&day==num(u,"day_no")&&slot==num(u,"slot_no")); }
    private boolean place(int index,List<Map<String,Object>> tasks,List<Map<String,Object>> rooms,List<Map<String,Object>> placed) {
        if(index==tasks.size()) return true; if(++visits>200000) return false;
        var course=tasks.get(index);
        for(int slot=0;slot<calendar.periods.size();slot++) for(int day:calendar.days) for(var room:rooms) {
            if(num(room,"capacity")<num(course,"students")||unavailable(str(course,"faculty_id"),day,slot)) continue;
            var candidate=new LinkedHashMap<>(course);candidate.put("room_id",room.get("id"));candidate.put("day_no",day);candidate.put("slot_no",slot);
            boolean exists=false;for(LocalDate d=calendar.start;!d.isAfter(calendar.end);d=d.plusDays(1)) if(calendar.occurs(candidate,d)){exists=true;break;}
            if(!exists) continue;
            boolean busy=false;
            for(var other:placed) if(num(other,"slot_no")==slot&&resources(candidate,other)&&calendar.overlap(candidate,other)) {busy=true;break;}
            for(var e:exams) if(covers(e,slot)&&calendar.occurs(candidate,LocalDate.parse(str(e,"exam_date")))&&(same(candidate,e,"room_id")||same(candidate,e,"cohort")||staff.get(str(e,"id")).contains(str(candidate,"faculty_id")))) busy=true;
            if(!busy) { placed.add(candidate);if(place(index+1,tasks,rooms,placed)) return true;placed.remove(placed.size()-1); }
        }
        return false;
    }
    static boolean same(Map<String,Object> a,Map<String,Object> b,String key) {return Objects.equals(a.get(key),b.get(key));}
    static boolean resources(Map<String,Object> a,Map<String,Object> b) {return same(a,b,"faculty_id")||same(a,b,"cohort")||same(a,b,"room_id");}
    static boolean covers(Map<String,Object> exam,int slot) {return slot>=num(exam,"slot_no")&&slot<num(exam,"slot_no")+num(exam,"duration");}
    static boolean overlapExams(Map<String,Object> a,Map<String,Object> b) {return same(a,b,"exam_date")&&num(a,"slot_no")<num(b,"slot_no")+num(b,"duration")&&num(b,"slot_no")<num(a,"slot_no")+num(a,"duration");}
    public void allocate(Connection c) throws Exception {
        calendar=new Academic.Calendar(c);unavailable=rows(c,"SELECT * FROM unavailable");
        exams=rows(c,Academic.examsSql()+" ORDER BY exam_date,slot_no,e.id");if(exams.isEmpty()) throw new IllegalArgumentException("Add an examination first.");
        var faculty=rows(c,"SELECT id FROM users WHERE role='FACULTY' ORDER BY name");var lessons=rows(c,Academic.lessonsSql());
        var tasks=new ArrayList<Map<String,Object>>();for(var e:exams) for(int i=0;i<num(e,"required_staff");i++) tasks.add(e);
        var selected=new ArrayList<String>();visits=0;
        if(!assign(0,tasks,faculty,lessons,selected,new HashMap<>())) throw new IllegalArgumentException("Not enough available invigilators for all exam periods. Previous allocations were preserved.");
        exec(c,"DELETE FROM exam_staff");exec(c,"UPDATE exams SET invigilator_id=NULL");
        for(int i=0;i<tasks.size();i++) {String eid=str(tasks.get(i),"id"),fid=selected.get(i);exec(c,"INSERT INTO exam_staff VALUES(?,?)",eid,fid);exec(c,"UPDATE exams SET invigilator_id=? WHERE id=? AND invigilator_id IS NULL",fid,eid);}
        requireValid(c);Database.notify(c,"Invigilation duties published for "+exams.size()+" examinations.");
    }
    private boolean assign(int index,List<Map<String,Object>> tasks,List<Map<String,Object>> faculty,List<Map<String,Object>> lessons,List<String> chosen,Map<String,Integer> load) {
        if(index==tasks.size()) return true;if(++visits>200000) return false;
        var e=tasks.get(index);var date=LocalDate.parse(str(e,"exam_date"));int day=weekday(date.toString());
        var candidates=new ArrayList<>(faculty);candidates.sort(Comparator.comparingInt(f->load.getOrDefault(str(f,"id"),0)));
        for(var f:candidates) {
            String fid=str(f,"id");boolean busy=fid.equals(e.get("faculty_id"))||calendar.onLeave(fid,date);
            for(int s=num(e,"slot_no");s<num(e,"slot_no")+num(e,"duration");s++) if(unavailable(fid,day,s)) busy=true;
            for(var l:lessons) if(fid.equals(l.get("faculty_id"))&&covers(e,num(l,"slot_no"))&&calendar.occurs(l,date)) busy=true;
            for(int j=0;j<index;j++) if(fid.equals(chosen.get(j))&&overlapExams(e,tasks.get(j))) busy=true;
            if(!busy) {chosen.add(fid);load.merge(fid,num(e,"duration"),Integer::sum);if(assign(index+1,tasks,faculty,lessons,chosen,load))return true;chosen.remove(chosen.size()-1);load.merge(fid,-num(e,"duration"),Integer::sum);}
        }
        return false;
    }
    public static List<String> conflicts(Connection c) throws SQLException {
        var cal=new Academic.Calendar(c);var lessons=rows(c,Academic.lessonsSql());var exams=rows(c,Academic.examsSql());var unavailable=rows(c,"SELECT * FROM unavailable");var errors=new LinkedHashSet<String>();var staff=new HashMap<String,List<String>>();
        for(var e:exams) staff.put(str(e,"id"),Academic.staff(c,e));
        for(int i=0;i<lessons.size();i++) {
            var a=lessons.get(i);
            if(!cal.days.contains(num(a,"day_no"))||num(a,"slot_no")>=cal.periods.size())errors.add("A class uses a disabled day or period.");
            if(num(a,"students")>num(a,"capacity")) errors.add(a.get("code")+": classroom capacity exceeded.");
            for(var u:unavailable) if(same(a,u,"faculty_id")&&same(a,u,"day_no")&&same(a,u,"slot_no"))errors.add(a.get("code")+": faculty is unavailable.");
            for(int j=i+1;j<lessons.size();j++){var b=lessons.get(j);if(same(a,b,"slot_no")&&resources(a,b)&&cal.overlap(a,b))errors.add(a.get("code")+" and "+b.get("code")+": overlapping classes.");}
        }
        for(int i=0;i<exams.size();i++) {
            var a=exams.get(i);var date=LocalDate.parse(str(a,"exam_date"));var inv=staff.get(str(a,"id"));
            if(!cal.inTerm(date)||cal.holidays.contains(date.toString()))errors.add(a.get("code")+": exam falls outside the semester or on a holiday.");
            if(num(a,"slot_no")+num(a,"duration")>cal.periods.size())errors.add(a.get("code")+": exam exceeds the last period.");
            if(num(a,"students")>num(a,"capacity"))errors.add(a.get("code")+": exam room capacity exceeded.");
            if(!inv.isEmpty()&&inv.size()!=num(a,"required_staff"))errors.add(a.get("code")+": incorrect invigilator count.");
            for(String fid:inv) {
                if(fid.equals(a.get("faculty_id")))errors.add(a.get("code")+": instructor cannot invigilate own exam.");
                if(cal.onLeave(fid,date))errors.add(a.get("code")+": invigilator is on leave.");
                for(var u:unavailable)if(fid.equals(u.get("faculty_id"))&&weekday(date.toString())==num(u,"day_no")&&covers(a,num(u,"slot_no")))errors.add(a.get("code")+": invigilator unavailable.");
            }
            for(var l:lessons)if(covers(a,num(l,"slot_no"))&&cal.occurs(l,date)&&(same(a,l,"room_id")||same(a,l,"cohort")||inv.contains(str(l,"faculty_id"))))errors.add(a.get("code")+": exam overlaps a teaching occurrence.");
            for(int j=i+1;j<exams.size();j++){var b=exams.get(j);if(overlapExams(a,b)&&(same(a,b,"room_id")||same(a,b,"cohort")||staff.get(str(b,"id")).stream().anyMatch(inv::contains)))errors.add(a.get("code")+" and "+b.get("code")+": overlapping examinations.");}
        }
        return new ArrayList<>(errors);
    }
    static void requireValid(Connection c) throws SQLException {var errors=conflicts(c);if(!errors.isEmpty())throw new IllegalArgumentException(String.join(" ",errors));}
}
