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
    private final CampusService service;
    private final AtomicLong revision=new AtomicLong();
    private final Set<AsyncContext> listeners=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService events=Executors.newSingleThreadScheduledExecutor();
    // Per-address rolling login window; bound the map by expiring idle entries.
    private final Map<String,Attempt> attempts=new HashMap<>();
    private record Attempt(long start,int count) {}
    public ApiServlet(CampusService service) { this.service=service; }
    @Override public void init() { events.scheduleAtFixedRate(()->{for(var listener:listeners) { try { listener.getResponse().getWriter().write(": heartbeat\n\n"); listener.getResponse().getWriter().flush(); if(listener.getResponse().getWriter().checkError()) close(listener); } catch(Exception e) { close(listener); } }},15,15,TimeUnit.SECONDS); }
    private void close(AsyncContext context) { listeners.remove(context); try {context.complete();} catch(Exception ignored) {} }
    public void changed() { long value=revision.incrementAndGet(); events.execute(()->{for(var listener:listeners) try { var writer=listener.getResponse().getWriter(); writer.write("data: "+value+"\n\n"); writer.flush(); if(writer.checkError()) close(listener); } catch(Exception e) {close(listener);} }); }
    private synchronized boolean allowLogin(String remote) { long now=System.currentTimeMillis(); attempts.entrySet().removeIf(e->now-e.getValue().start>60000); Attempt old=attempts.getOrDefault(remote,new Attempt(now,0)); if(old.count>=15) return false; attempts.put(remote,new Attempt(old.start,old.count+1)); return true; }
    @SuppressWarnings("unchecked")
    @Override protected void service(HttpServletRequest req,HttpServletResponse res) throws IOException {
        res.setContentType("application/json;charset=UTF-8"); res.setHeader("Cache-Control","no-store"); res.setHeader("X-Content-Type-Options","nosniff");
        try {
            String path=Optional.ofNullable(req.getPathInfo()).orElse("/"); String method=req.getMethod();
            if(!Set.of("GET","POST").contains(method)) { error(res,405,"Method not allowed."); return; }
            if(method.equals("POST")&&!"1".equals(req.getHeader("X-Campus-Request"))) {error(res,403,"Missing same-origin request header.");return;}
            Map<String,Object> body=Map.of();
            if(method.equals("POST")) { byte[] bytes=req.getInputStream().readNBytes(16385); if(bytes.length>16384) {error(res,413,"Request too large.");return;} if(bytes.length>0) { try {body=JSON.readValue(bytes,Map.class);} catch(Exception e) {error(res,400,"Invalid JSON object.");return;} if(body==null) throw new IllegalArgumentException("JSON object required."); } }
            if(path.equals("/login")&&method.equals("POST")) {
                if(!allowLogin(req.getRemoteAddr())) {error(res,429,"Too many attempts. Try again in one minute.");return;}
                var user=service.login(CampusService.required(body,"email",150),CampusService.required(body,"password",128));
                if(user==null) {error(res,401,"Email or password is incorrect.");return;}
                if(req.getSession(false)!=null) req.getSession(false).invalidate();
                var session=req.getSession(true); session.setAttribute("user",user); session.setMaxInactiveInterval(1800); write(res,user); return;
            }
            var session=req.getSession(false); var user=session==null?null:(Map<String,Object>)session.getAttribute("user");
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
                    case "/report" -> {
                        if(!"ADMIN".equals(user.get("role"))) throw new SecurityException("Administrator access required.");
                        res.setContentType("text/csv;charset=UTF-8");res.setHeader("Content-Disposition","attachment; filename=campus-timetable.csv");
                        var writer=res.getWriter(); writer.println("Course,Title,Cohort,Faculty,Room,Day,Slot");
                        var state=service.state(uid); for(var row:(List<Map<String,Object>>)state.get("lessons")) { StringJoiner line=new StringJoiner(","); for(String key:List.of("code","title","cohort","faculty_name","room_name","day_no","slot_no")) { String v=String.valueOf(row.get(key)); if(key.equals("day_no")) v=List.of("Monday","Tuesday","Wednesday","Thursday","Friday").get(Integer.parseInt(v)); if(key.equals("slot_no")) v=List.of("09:00–10:00","10:00–11:00","11:15–12:15","13:00–14:00","14:00–15:00","15:15–16:15").get(Integer.parseInt(v)); if(v.matches("^[=+@\\-\\t\\r].*")) v="'"+v; line.add("\""+v.replace("\"","\"\"")+"\""); } writer.println(line); }
                    }
                    default -> error(res,404,"Endpoint not found.");
                }
            } else {
                if(path.equals("/logout")) {session.invalidate();write(res,Map.of("ok",true));return;}
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
