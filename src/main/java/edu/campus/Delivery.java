package edu.campus;

import java.sql.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static edu.campus.Database.*;

/** Durable at-least-once HTTPS delivery. Gateways must deduplicate by Idempotency-Key. */
final class Delivery implements AutoCloseable {
    private final Database db; private final Map<String,String> env;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    interface Sender {int send(HttpRequest request) throws Exception;}
    private final Sender sender;
    Delivery(Database db,Map<String,String> env) {this.db=db;this.env=env;this.sender=request->client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();}
    Delivery(Database db,Map<String,String> env,Sender sender) {this.db=db;this.env=env;this.sender=sender;}
    void start() { worker.scheduleWithFixedDelay(()->{try{drain();}catch(Exception e){System.err.println("Delivery queue processing failed: "+e.getClass().getSimpleName());}},5,15,TimeUnit.SECONDS); }
    static boolean configured(Map<String,String> env,String channel) {
        String url=env.get(channel+"_GATEWAY_URL"),token=env.get(channel+"_GATEWAY_TOKEN");
        if(url==null||url.isBlank()||token==null||token.isBlank())return false;
        try{URI uri=URI.create(url);return "https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null;}catch(IllegalArgumentException e){return false;}
    }
    static void enqueue(Connection c,String body) throws SQLException {
        for(var p:rows(c,"SELECT * FROM delivery_preferences")) enqueue(c,(String)p.get("channel"),(String)p.get("destination"),body);
        if(configured(System.getenv(),"INSTITUTION"))enqueue(c,"INSTITUTION","institution",body);
    }
    static void enqueue(Connection c,String channel,String destination,String body) throws SQLException {long now=System.currentTimeMillis();exec(c,"INSERT INTO delivery_outbox VALUES(?,?,?,?, 'PENDING',0,?,NULL,?)",id(),channel,destination,body,now,now);}
    void drain() throws Exception {
        for(int i=0;i<20;i++) {
            var job=db.transaction(c->{
                var jobs=rows(c,"SELECT * FROM delivery_outbox WHERE status IN ('PENDING','RETRY','SENDING') AND next_attempt<=? ORDER BY created_at LIMIT 1",System.currentTimeMillis());
                if(jobs.isEmpty())return null;var j=jobs.get(0);exec(c,"UPDATE delivery_outbox SET status='SENDING',next_attempt=? WHERE id=?",System.currentTimeMillis()+60000,j.get("id"));return j;
            });
            if(job==null)return;
            String channel=(String)job.get("channel"),url=env.get(channel+"_GATEWAY_URL"),token=env.get(channel+"_GATEWAY_TOKEN"),status="ACCEPTED",error=null;
            int attempts=((Number)job.get("attempts")).intValue()+1;
            try {
                if(url==null||token==null||token.isBlank())throw new IllegalStateException("Gateway is not configured");
                URI uri=URI.create(url);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalStateException("Gateway requires an HTTPS URL");
                String payload=ApiServlet.JSON.writeValueAsString(Map.of("id",job.get("id"),"channel",channel,"destination",job.get("destination"),"message",job.get("body"),"created_at",job.get("created_at")));
                var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key",(String)job.get("id")).POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                int code=sender.send(request);if(code<200||code>=300)throw new IllegalStateException("Gateway HTTP "+code);
            } catch(Exception e) {status=attempts>=5?"FAILED":"RETRY";error=e instanceof IllegalStateException?e.getMessage():e.getClass().getSimpleName();}
            try(var c=db.open()){exec(c,"UPDATE delivery_outbox SET status=?,attempts=?,next_attempt=?,last_error=? WHERE id=?",status,attempts,System.currentTimeMillis()+Math.min(3600000L,15000L*(1L<<attempts)),error,job.get("id"));}
        }
    }
    public void close(){worker.shutdownNow();}
}
