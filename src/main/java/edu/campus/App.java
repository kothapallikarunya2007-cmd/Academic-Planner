package edu.campus;

import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.Context;
import jakarta.servlet.http.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;

public final class App {
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("data")); Files.createDirectories(Path.of("runtime"));
        Database db=new Database(System.getenv().getOrDefault("DB_URL","jdbc:h2:./data/campus;DB_CLOSE_ON_EXIT=FALSE"),System.getenv().getOrDefault("DB_USER","sa"),System.getenv().getOrDefault("DB_PASSWORD",""));
        db.initialize(!"false".equalsIgnoreCase(System.getenv("SEED_DEMO")));
        CampusService service=new CampusService(db); ApiServlet api=new ApiServlet(service);
        Tomcat tomcat=new Tomcat();tomcat.setBaseDir(Path.of("runtime").toAbsolutePath().toString());
        tomcat.setPort(Integer.parseInt(System.getenv().getOrDefault("PORT","8080")));
        tomcat.getConnector().setProperty("address",System.getenv().getOrDefault("BIND_ADDRESS","127.0.0.1"));
        Context ctx=tomcat.addContext("",Path.of("runtime").toAbsolutePath().toString());
        ctx.setUseHttpOnly(true);ctx.setSessionTimeout(30);ctx.setSessionCookieName("CAMPUS_SESSION");
        ctx.addServletContainerInitializer((classes, servletContext) -> {
            servletContext.getSessionCookieConfig().setAttribute("SameSite","Strict");
            servletContext.getSessionCookieConfig().setSecure("true".equalsIgnoreCase(System.getenv("SECURE_COOKIE")));
            servletContext.setSessionTrackingModes(Set.of(jakarta.servlet.SessionTrackingMode.COOKIE));
        }, Set.of());
        var wrapper=Tomcat.addServlet(ctx,"api",api);wrapper.setAsyncSupported(true);wrapper.setLoadOnStartup(1);ctx.addServletMappingDecoded("/api/*","api");
        Tomcat.addServlet(ctx,"static",new HttpServlet(){
            @Override protected void doGet(HttpServletRequest req,HttpServletResponse res) throws IOException {
                String path=req.getRequestURI(); if(path.equals("/")) path="/index.html";
                if(!Set.of("/index.html","/app.js","/style.css").contains(path)) {res.sendError(404);return;}
                res.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
                res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("Referrer-Policy","same-origin");
                res.setContentType(path.endsWith(".css")?"text/css;charset=UTF-8":path.endsWith(".js")?"text/javascript;charset=UTF-8":"text/html;charset=UTF-8");
                try(InputStream in=App.class.getResourceAsStream("/web"+path)) {if(in==null){res.sendError(404);return;}in.transferTo(res.getOutputStream());}
            }
        });ctx.addServletMappingDecoded("/","static");
        tomcat.start();
        if(!tomcat.getConnector().getState().isAvailable()) {
            tomcat.stop();tomcat.destroy();
            throw new IllegalStateException("HTTP server could not start. Check the PORT and BIND_ADDRESS settings; the port may already be in use.");
        }
        SocketChatServer socket=null;
        if(System.getenv("CHAT_PORT")!=null) {socket=new SocketChatServer(service,api,Integer.parseInt(System.getenv("CHAT_PORT")));socket.start();}
        SocketChatServer finalSocket=socket;
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try{if(finalSocket!=null)finalSocket.close();tomcat.stop();tomcat.destroy();}catch(Exception ignored){}}));
        System.out.println("Campus Connect is ready at http://localhost:"+tomcat.getConnector().getLocalPort());
        tomcat.getServer().await();
    }
}
