package edu.campus;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Optional loopback TCP teaching interface. JSON lines: login, send, history. */
public final class SocketChatServer implements AutoCloseable {
    private final ServerSocket server;
    private final ExecutorService workers=new ThreadPoolExecutor(0,16,60,TimeUnit.SECONDS,new SynchronousQueue<>());
    private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    private final CampusService service; private final ApiServlet api;
    public SocketChatServer(CampusService service,ApiServlet api,int port) throws IOException {this.service=service;this.api=api;server=new ServerSocket(port,16,InetAddress.getLoopbackAddress());}
    public void start() { Thread accept=new Thread(()->{while(!server.isClosed()) try {Socket socket=server.accept();clients.add(socket);try{workers.execute(()->handle(socket));}catch(RejectedExecutionException e){clients.remove(socket);socket.close();}}catch(IOException e){if(!server.isClosed()) e.printStackTrace();}},"chat-accept");accept.setDaemon(true);accept.start(); }
    @SuppressWarnings("unchecked") private void handle(Socket socket) {
        try(socket;var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));var out=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true)) {
            socket.setSoTimeout(60000); Map<String,Object> user=null; int failures=0;
            out.println("{\"info\":\"Authenticate with {email,password}; then {body} or {history:true}.\"}");
            String line;
            while((line=boundedLine(input))!=null) {
                try {
                    Map<String,Object> b=ApiServlet.JSON.readValue(line,Map.class);
                    if(user==null) {user=service.login(CampusService.required(b,"email",150),CampusService.required(b,"password",128)); if(user==null) {out.println("{\"error\":\"Invalid credentials\"}");if(++failures>=3) break;} else out.println(ApiServlet.JSON.writeValueAsString(Map.of("user",user)));}
                    else if(Boolean.TRUE.equals(b.get("history"))) out.println(ApiServlet.JSON.writeValueAsString(service.messages()));
                    else {service.message((String)user.get("id"),CampusService.required(b,"body",2000));api.changed();out.println("{\"ok\":true}");}
                } catch(Exception e) {out.println("{\"error\":\"Invalid request or unavailable database\"}");}
            }
        } catch(IOException ignored) {} finally {clients.remove(socket);}
    }
    private String boundedLine(Reader reader) throws IOException {StringBuilder b=new StringBuilder();int ch;while((ch=reader.read())!=-1&&ch!='\n'){if(b.length()>=8192)throw new IOException("Line too long");b.append((char)ch);}return ch==-1&&b.isEmpty()?null:b.toString();}
    public void close() throws IOException {server.close();for(var s:clients)try{s.close();}catch(IOException ignored){}workers.shutdownNow();}
}
