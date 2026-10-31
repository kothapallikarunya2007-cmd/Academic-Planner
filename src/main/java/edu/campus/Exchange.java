package edu.campus;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static edu.campus.Database.*;
import static edu.campus.CampusService.*;

/** Transactional CSV imports and secret-free JSON, CSV and calendar exports. */
final class Exchange {
    static Map<String,Object> snapshot(Connection c) throws SQLException {
        var result=new LinkedHashMap<String,Object>();result.put("schema_version",2);result.put("settings",Academic.settings(c));
        result.put("users",rows(c,"SELECT id,name,email,role,department FROM users ORDER BY id"));
        for(String table:List.of("rooms","courses","course_patterns","holidays","faculty_leave","unavailable"))result.put(table,rows(c,"SELECT * FROM "+table+" ORDER BY 1"));
        result.put("lessons",rows(c,Academic.lessonsSql()));var exams=rows(c,Academic.examsSql());for(var e:exams)e.put("invigilator_ids",Academic.staff(c,e));result.put("exams",exams);return result;
    }
    static void importCsv(Connection c,CampusService service,Map<String,Object> b,Map<String,Object> user) throws Exception {
        String entity=required(b,"entity",20);if(!Set.of("users","rooms","courses","exams","holiday","leave").contains(entity))throw new IllegalArgumentException("Unsupported import type.");
        var records=parse(required(b,"csv",900000));if(records.size()<2||records.size()>501)throw new IllegalArgumentException("Import 1–500 data rows.");
        var headers=records.get(0);if(new HashSet<>(headers).size()!=headers.size()||headers.stream().anyMatch(String::isBlank))throw new IllegalArgumentException("CSV headers must be unique and nonempty.");
        for(int i=1;i<records.size();i++) {
            var values=records.get(i);if(values.size()!=headers.size())throw new IllegalArgumentException("CSV row "+(i+1)+" has the wrong number of fields.");
            var row=new LinkedHashMap<String,Object>();for(int j=0;j<headers.size();j++)row.put(headers.get(j),values.get(j));
            try {
                if(entity.equals("courses")&&row.containsKey("faculty_email"))row.put("faculty_id",lookup(c,"users","email",row.get("faculty_email")));
                if(entity.equals("leave")&&row.containsKey("faculty_email"))row.put("faculty_id",lookup(c,"users","email",row.get("faculty_email")));
                if(entity.equals("exams")){if(row.containsKey("course_code"))row.put("course_id",lookup(c,"courses","code",row.get("course_code")));if(row.containsKey("room_name"))row.put("room_id",lookup(c,"rooms","name",row.get("room_name")));}
                service.apply(c,entity,row,user);
            } catch(Exception e){throw new IllegalArgumentException("CSV row "+(i+1)+" failed: "+(e instanceof SQLException?"duplicate or invalid reference":e.getMessage())+". No rows were imported.",e);}
        }
    }
    private static Object lookup(Connection c,String table,String field,Object value) throws SQLException {var list=rows(c,"SELECT id FROM "+table+" WHERE "+field+"=?",value);if(list.isEmpty())throw new IllegalArgumentException("Unknown "+field+" reference.");return list.get(0).get("id");}
    static List<List<String>> parse(String text) {
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        var records=new ArrayList<List<String>>();var row=new ArrayList<String>();var field=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<text.length();i++) {
            char ch=text.charAt(i);
            if(quoted){if(ch=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){field.append('"');i++;}else{quoted=false;closed=true;}}else field.append(ch);continue;}
            if(ch=='"'){if(field.length()>0||closed)throw new IllegalArgumentException("Malformed CSV quotation.");quoted=true;}
            else if(ch==','||ch=='\n'||ch=='\r'){row.add(field.toString());field.setLength(0);closed=false;if(ch!=','){records.add(row);row=new ArrayList<>();if(ch=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;}}
            else {if(closed)throw new IllegalArgumentException("Unexpected character after quoted CSV field.");field.append(ch);}
        }
        if(quoted)throw new IllegalArgumentException("Unclosed CSV quotation.");if(field.length()>0||!row.isEmpty()||closed){row.add(field.toString());records.add(row);}return records;
    }
    static String csv(List<Map<String,Object>> rows,List<String> keys) {
        var out=new StringBuilder(String.join(",",keys)).append("\r\n");
        for(var row:rows){var line=new StringJoiner(",");for(String key:keys){String v=String.valueOf(row.getOrDefault(key,""));if(v.stripLeading().matches("^[=+@\\-].*")||v.startsWith("\t")||v.startsWith("\r"))v="'"+v;line.add("\""+v.replace("\"","\"\"")+"\"");}out.append(line).append("\r\n");}return out.toString();
    }
    static String calendar(Connection c) throws SQLException {
        var cal=new Academic.Calendar(c);var zone=ZoneId.of((String)cal.settings.get("timezone"));var out=new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Campus Connect//Academic Calendar//EN\r\nCALSCALE:GREGORIAN\r\n");
        for(var l:Academic.occurrences(c))event(out,"class-"+l.get("id")+"-"+l.get("date"),str(l,"code")+" "+str(l,"title"),str(l,"room_name"),str(l,"date"),cal.periods.get(Scheduler.num(l,"slot_no")).split("-")[0],cal.periods.get(Scheduler.num(l,"slot_no")).split("-")[1],zone);
        for(var e:rows(c,Academic.examsSql()))event(out,"exam-"+e.get("id"),"Exam: "+e.get("code"),str(e,"room_name"),str(e,"exam_date"),cal.periods.get(Scheduler.num(e,"slot_no")).split("-")[0],cal.periods.get(Scheduler.num(e,"slot_no")+Scheduler.num(e,"duration")-1).split("-")[1],zone);
        return out.append("END:VCALENDAR\r\n").toString();
    }
    private static String str(Map<String,Object> row,String key){return String.valueOf(row.get(key));}
    private static String escape(String s){return s.replace("\\","\\\\").replace("\r","").replace("\n","\\n").replace(";","\\;").replace(",","\\,");}
    private static void line(StringBuilder out,String value){int bytes=0;for(int cp:value.codePoints().toArray()){String s=new String(Character.toChars(cp));int n=s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(bytes+n>73){out.append("\r\n ");bytes=1;}out.append(s);bytes+=n;}out.append("\r\n");}
    private static void event(StringBuilder out,String id,String title,String room,String date,String start,String end,ZoneId zone){
        var fmt=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);out.append("BEGIN:VEVENT\r\n");line(out,"UID:"+id+"@campus-connect");line(out,"DTSTAMP:"+fmt.format(Instant.now()));line(out,"DTSTART:"+fmt.format(LocalDate.parse(date).atTime(LocalTime.parse(start)).atZone(zone).toInstant()));line(out,"DTEND:"+fmt.format(LocalDate.parse(date).atTime(LocalTime.parse(end)).atZone(zone).toInstant()));line(out,"SUMMARY:"+escape(title));line(out,"LOCATION:"+escape(room));out.append("END:VEVENT\r\n");
    }
}
