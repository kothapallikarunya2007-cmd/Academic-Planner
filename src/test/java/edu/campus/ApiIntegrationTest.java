package edu.campus;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.apache.catalina.startup.Tomcat;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiIntegrationTest {
    @TempDir Path temp;
    Tomcat tomcat; String base; CampusService service; ApiServlet api; HttpClient admin,faculty;
    @BeforeEach void start() throws Exception {
        var db=new Database("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");db.initialize(true);service=new CampusService(db);api=new ApiServlet(service);
        tomcat=new Tomcat();tomcat.setBaseDir(temp.toString());tomcat.setPort(0);tomcat.getConnector().setProperty("address","127.0.0.1");
        var ctx=tomcat.addContext("",temp.toString());ctx.setUseHttpOnly(true);
        var servlet=Tomcat.addServlet(ctx,"api",api);servlet.setAsyncSupported(true);servlet.setLoadOnStartup(1);ctx.addServletMappingDecoded("/api/*","api");tomcat.start();
        base="http://127.0.0.1:"+tomcat.getConnector().getLocalPort()+"/api/";
        admin=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        faculty=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    }
    @AfterEach void stop() throws Exception {tomcat.stop();tomcat.destroy();}
    HttpResponse<String> request(HttpClient client,String path,String body) throws Exception {var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(10));if(body!=null)builder.header("Content-Type","application/json").header("X-Campus-Request","1").POST(HttpRequest.BodyPublishers.ofString(body));return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());}
    void login(HttpClient client,String email,String password) throws Exception {assertEquals(200,request(client,"login",ApiServlet.JSON.writeValueAsString(Map.of("email",email,"password",password))).statusCode());}
    @Test void httpSessionRoleCsrfAndValidation() throws Exception {
        assertEquals(401,request(admin,"state",null).statusCode());
        var noHeader=HttpRequest.newBuilder(URI.create(base+"login")).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403,admin.send(noHeader,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400,request(admin,"login","[]").statusCode());
        login(admin,"admin@campus.edu","Admin@12345");login(faculty,"ananya@campus.edu","Faculty@12345");
        assertEquals(403,request(faculty,"generate","{}").statusCode());assertEquals(403,request(faculty,"report",null).statusCode());
        assertEquals(200,request(admin,"generate","{}").statusCode());assertTrue(request(admin,"report",null).body().contains("Advanced Java"));
        assertEquals(409,request(admin,"rooms","{\"name\":\"Bad\",\"capacity\":-1}").statusCode());
        var state=request(admin,"state",null);assertEquals(200,state.statusCode());assertFalse(state.body().contains("password_hash"));
        assertEquals(200,request(admin,"logout","{}").statusCode());assertEquals(401,request(admin,"me",null).statusCode());
    }
    @Test void realtimeEventsAndSocketBridge() throws Exception {
        login(admin,"admin@campus.edu","Admin@12345");
        var response=admin.send(HttpRequest.newBuilder(URI.create(base+"events")).build(),HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200,response.statusCode());
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try(var lines=new BufferedReader(new InputStreamReader(response.body()))) {
            assertTrue(reader.submit(lines::readLine).get(5,TimeUnit.SECONDS).startsWith("data: "));lines.readLine();
            assertEquals(200,request(admin,"messages","{\"body\":\"Live browser message\"}").statusCode());
            assertTrue(reader.submit(lines::readLine).get(5,TimeUnit.SECONDS).startsWith("data: "));
        } finally {reader.shutdownNow();}
        int port;try(var portProbe=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=portProbe.getLocalPort();}
        try(var tcp=new SocketChatServer(service,api,port)) {
            tcp.start();try(var socket=new Socket(InetAddress.getLoopbackAddress(),port);var in=new BufferedReader(new InputStreamReader(socket.getInputStream()));var out=new PrintWriter(socket.getOutputStream(),true)) {
                socket.setSoTimeout(5000);assertTrue(in.readLine().contains("Authenticate"));out.println("{\"email\":\"ananya@campus.edu\",\"password\":\"Faculty@12345\"}");assertTrue(in.readLine().contains("Ananya"));out.println("{\"body\":\"Message from TCP\"}");assertTrue(in.readLine().contains("true"));out.println("{\"history\":true}");assertTrue(in.readLine().contains("Live browser message"));
            }
        }
        assertTrue(request(admin,"messages",null).body().contains("Message from TCP"));
    }
}
