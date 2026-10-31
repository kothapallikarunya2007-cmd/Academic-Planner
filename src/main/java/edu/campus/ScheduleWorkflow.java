package edu.campus;

import java.sql.*;
import java.util.*;
import static edu.campus.Database.*;
import static edu.campus.CampusService.*;

final class ScheduleWorkflow {
    static String context(Connection c) throws Exception {
        var result=new LinkedHashMap<String,Object>();result.put("settings",Academic.settings(c));
        for(String table:List.of("courses","rooms","unavailable","holidays","faculty_leave","course_patterns","exams","exam_options","exam_staff"))
            result.put(table,rows(c,"SELECT * FROM "+table+" ORDER BY 1,2"));
        result.put("published",rows(c,"SELECT * FROM lessons ORDER BY id"));
        return ApiServlet.JSON.writeValueAsString(result);
    }
    static void draft(Connection c,String uid,String title,String operation,Map<String,Object> b) throws Exception {
        if(title.isBlank()||title.length()>100)throw new IllegalArgumentException("Draft title must contain 1–100 characters.");
        String context=context(c),body;Savepoint save=c.setSavepoint();
        try {
            if(operation.equals("generate")) new Scheduler().generate(c);
            else if(operation.equals("move")) {
                var cal=new Academic.Calendar(c);int day=integer(b,"day_no",0,6);if(!cal.days.contains(day))throw new IllegalArgumentException("Teaching day is disabled.");
                if(exec(c,"UPDATE lessons SET day_no=?,slot_no=?,room_id=? WHERE id=?",day,integer(b,"slot_no",0,cal.periods.size()-1),required(b,"room_id",36),required(b,"id",36))==0)throw new IllegalArgumentException("Class not found.");
                Scheduler.requireValid(c);
            } else {
                var version=find(c,required(b,"id",36));if(!"PUBLISHED".equals(version.get("status")))throw new IllegalArgumentException("Only a published version can be restored.");
                replace(c,(String)version.get("body"));Scheduler.requireValid(c);
            }
            body=ApiServlet.JSON.writeValueAsString(rows(c,"SELECT * FROM lessons ORDER BY id"));
        } finally { c.rollback(save);c.releaseSavepoint(save); }
        exec(c,"INSERT INTO schedule_versions VALUES(?,?, 'DRAFT',?,NULL,?,NULL,?,?)",id(),title,uid,System.currentTimeMillis(),body,context);
    }
    static Map<String,Object> find(Connection c,String id) throws SQLException {var list=rows(c,"SELECT * FROM schedule_versions WHERE id=?",id);if(list.isEmpty())throw new IllegalArgumentException("Schedule version not found.");return list.get(0);}
    @SuppressWarnings("unchecked") static void replace(Connection c,String body) throws Exception {
        List<Map<String,Object>> lessons=ApiServlet.JSON.readValue(body,List.class);exec(c,"DELETE FROM lessons");
        for(var l:lessons)exec(c,"INSERT INTO lessons VALUES(?,?,?,?,?)",l.get("id"),l.get("course_id"),l.get("room_id"),l.get("day_no"),l.get("slot_no"));
    }
    static void transition(Connection c,String action,Map<String,Object> b,String uid) throws Exception {
        String id=required(b,"id",36);var v=find(c,id);String status=(String)v.get("status");
        switch(action) {
            case "schedule/submit" -> {if(!status.equals("DRAFT"))throw new IllegalArgumentException("Submit a draft.");exec(c,"UPDATE schedule_versions SET status='PENDING' WHERE id=?",id);}
            case "schedule/approve", "schedule/reject" -> {
                if(!status.equals("PENDING"))throw new IllegalArgumentException("Version must be pending review.");
                if(uid.equals(v.get("created_by")))throw new IllegalArgumentException("A different administrator must review this schedule.");
                exec(c,"UPDATE schedule_versions SET status=?,reviewed_by=? WHERE id=?",action.endsWith("approve")?"APPROVED":"REJECTED",uid,id);
            }
            case "schedule/publish" -> {
                if(!status.equals("APPROVED"))throw new IllegalArgumentException("Approval is required before publication.");
                if(!context(c).equals(v.get("context_body")))throw new IllegalArgumentException("Academic data or the publication changed after this draft. Generate or restore a new draft for review.");
                replace(c,(String)v.get("body"));Scheduler.requireValid(c);
                var counts=rows(c,"SELECT co.id,co.sessions,COUNT(l.id) AS actual FROM courses co LEFT JOIN lessons l ON l.course_id=co.id GROUP BY co.id,co.sessions");
                for(var count:counts)if(((Number)count.get("sessions")).intValue()!=((Number)count.get("actual")).intValue())throw new IllegalArgumentException("Draft does not cover current course requirements.");
                exec(c,"UPDATE schedule_versions SET status='PUBLISHED',published_at=? WHERE id=?",System.currentTimeMillis(),id);
                Database.notify(c,"Timetable published: "+v.get("title"));
            }
            default -> throw new IllegalArgumentException("Unknown schedule action.");
        }
    }
}
