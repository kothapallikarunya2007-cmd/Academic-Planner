package edu.campus;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import jakarta.servlet.*;
import java.io.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class ApiServlet extends HttpServlet {
    static final ObjectMapper JSON=new ObjectMapper();
    private final CampusService service; private final Sessions sessions;
    private final AtomicLong revision=new AtomicLong();
    private final Set<AsyncContext> listeners=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService events=Executors.newSingleThreadScheduledExecutor();
    public ApiServlet(CampusService service) { this.service=service; this.sessions=new Sessions(service.db); }
    @Override public void init() { events.scheduleAtFixedRate(()->{for(var listener:listeners) { try { listener.getResponse().getWriter().write(": heartbeat\n\n"); listener.getResponse().getWriter().flush(); if(listener.getResponse().getWriter().checkError()) close(listener); } catch(Exception e) { close(listener); } }},15,15,TimeUnit.SECONDS); }
    private void close(AsyncContext context) { listeners.remove(context); try {context.complete();} catch(Exception ignored) {} }
    public void changed() { long value=revision.incrementAndGet(); events.execute(()->{for(var listener:listeners) try { var writer=listener.getResponse().getWriter(); writer.write("data: "+value+"\n\n"); writer.flush(); if(writer.checkError()) close(listener); } catch(Exception e) {close(listener);} }); }
    @SuppressWarnings("unchecked")
    @Override protected void service(HttpServletRequest req,HttpServletResponse res) throws IOException {
        res.setContentType("application/json;charset=UTF-8"); res.setHeader("Cache-Control","no-store"); res.setHeader("X-Content-Type-Options","nosniff");
        try {
            String path=Optional.ofNullable(req.getPathInfo()).orElse("/"); String method=req.getMethod();
            if(!Set.of("GET","POST").contains(method)) { error(res,405,"Method not allowed."); return; }
            if(method.equals("POST")&&!"1".equals(req.getHeader("X-Campus-Request"))) {error(res,403,"Missing same-origin request header.");return;}
            Map<String,Object> body=Map.of();
            if(method.equals("POST")) { int limit=path.equals("/imports")?1048576:16384; byte[] bytes=req.getInputStream().readNBytes(limit+1); if(bytes.length>limit) {error(res,413,"Request too large.");return;} if(bytes.length>0) { try {body=JSON.readValue(bytes,Map.class);} catch(Exception e) {error(res,400,"Invalid JSON object.");return;} if(body==null) throw new IllegalArgumentException("JSON object required."); } }
            if(path.equals("/login")&&method.equals("POST")) {
                if(!sessions.allowLogin(CampusService.required(body,"email",150).trim().toLowerCase(Locale.ROOT))) {error(res,429,"Too many attempts. Try again in one minute.");return;}
                var user=service.login(CampusService.required(body,"email",150),CampusService.required(body,"password",128));
                if(user==null) {error(res,401,"Email or password is incorrect.");return;}

                sessions.create(req,res,(String)user.get("id")); write(res,user); return;
            }
            var user=sessions.user(req);
            if(user==null) {error(res,401,"Please sign in to continue.");return;}
            String uid=(String)user.get("id");
            if(method.equals("GET")) {
                switch(path) {
                    case "/me" -> write(res,user);
                    case "/state" -> write(res,service.state(uid));
                    case "/messages" -> write(res,service.messages());
                    case "/events" -> {
                        if(listeners.size()>=100) {error(res,503,"Live connection limit reached.");return;}
                        res.setContentType("text/event-stream;charset=UTF-8"); res.setHeader("X-Accel-Buffering","no");
                        AsyncContext context=req.startAsync(); context.setTimeout(60000);
                        context.addListener(new AsyncListener() { public void onComplete(AsyncEvent e){listeners.remove(context);} public void onTimeout(AsyncEvent e){close(context);} public void onError(AsyncEvent e){close(context);} public void onStartAsync(AsyncEvent e){} });
                        listeners.add(context); res.getWriter().write("data: "+revision.get()+"\n\n");res.getWriter().flush();
                    }
                    case "/occurrences" -> {try(var c=service.db.open()){write(res,Academic.occurrences(c));}}
                    case "/export", "/report" -> {
                        if(!"ADMIN".equals(user.get("role")))throw new SecurityException("Administrator access required.");
                        String format=Optional.ofNullable(req.getParameter("format")).orElse("csv");
                        try(var c=service.db.open()) {
                            if(format.equals("json")){res.setHeader("Content-Disposition","attachment; filename=campus-export.json");write(res,Exchange.snapshot(c));}
                            else if(format.equals("ics")){res.setContentType("text/calendar;charset=UTF-8");res.setHeader("Content-Disposition","attachment; filename=campus-calendar.ics");res.getWriter().write(Exchange.calendar(c));}
                            else if(format.equals("csv")){
                                String entity=Optional.ofNullable(req.getParameter("entity")).orElse("lessons");
                                List<Map<String,Object>> data;List<String> keys;
                                switch(entity){
                                    case "lessons" -> {data=Database.rows(c,Academic.lessonsSql());keys=List.of("code","title","cohort","faculty_name","room_name","day_no","slot_no","week_pattern");}
                                    case "exams" -> {data=Database.rows(c,Academic.examsSql());for(var e:data)e.put("invigilator_ids",String.join(";",Academic.staff(c,e)));keys=List.of("code","exam_date","room_name","slot_no","duration","required_staff","invigilator_ids");}
                                    case "occurrences" -> {data=Academic.occurrences(c);keys=List.of("date","code","faculty_name","room_name","slot_no");}
                                    case "users" -> {data=Database.rows(c,"SELECT id,name,email,role,department FROM users ORDER BY name");keys=List.of("id","name","email","role","department");}
                                    case "rooms" -> {data=Database.rows(c,"SELECT * FROM rooms ORDER BY name");keys=List.of("id","name","capacity");}
                                    default -> throw new IllegalArgumentException("Unsupported export entity.");
                                }
                                res.setContentType("text/csv;charset=UTF-8");res.setHeader("Content-Disposition","attachment; filename=campus-"+entity+".csv");res.getWriter().write(Exchange.csv(data,keys));
                            }else throw new IllegalArgumentException("Choose json, csv or ics.");
                        }
                    }
                    default -> error(res,404,"Endpoint not found.");
                }
            } else {
                if(path.equals("/logout")) {sessions.revoke(req,res);write(res,Map.of("ok",true));return;}
                if(path.equals("/messages")) service.message(uid,CampusService.required(body,"body",2000));
                else service.mutate(path.substring(1),body,user);
                changed();write(res,Map.of("ok",true));
            }
        } catch(SecurityException e) {error(res,403,e.getMessage());}
        catch(IllegalArgumentException|java.time.DateTimeException e) {error(res,409,e.getMessage());}
        catch(SQLException e) { if(e.getSQLState()!=null&&e.getSQLState().startsWith("23")) error(res,409,"Duplicate record or a resource still in use. Check names, references, and room bookings."); else {getServletContext().log("Database failure",e);error(res,500,"Database operation failed.");} }
        catch(Exception e) {getServletContext().log("Request failure",e);error(res,500,"Unexpected server error. No partial changes were saved.");}
    }
    private static void write(HttpServletResponse res,Object value) throws IOException { JSON.writeValue(res.getWriter(),value); }
    private static void error(HttpServletResponse res,int status,String message) throws IOException {if(res.isCommitted()) return;res.setStatus(status);write(res,Map.of("error",message));}
    @Override public void destroy() {events.shutdownNow();for(var c:listeners) close(c);}
}
