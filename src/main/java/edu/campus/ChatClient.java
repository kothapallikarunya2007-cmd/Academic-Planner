package edu.campus;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Run with java -cp target/campus-connect-1.0.0.jar edu.campus.ChatClient */
public final class ChatClient {
    public static void main(String[] args) throws Exception {
        Console console=System.console();
        if(console==null) {System.err.println("Run in an interactive terminal so your password can be hidden.");return;}
        int port=args.length==0?9090:Integer.parseInt(args[0]);
        try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),port);
            var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            var out=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true)) {
            System.out.println(input.readLine());
            String email=console.readLine("Email: ");char[] password=console.readPassword("Password: ");
            out.println(ApiServlet.JSON.writeValueAsString(Map.of("email",email,"password",new String(password))));Arrays.fill(password,'\0');
            String response=input.readLine();System.out.println(response);
            if(response==null||response.contains("\"error\""))return;
            System.out.println("Type a message, /history to read the room, or /quit to exit. Idle timeout: 60 seconds.");
            String line;while((line=console.readLine("> "))!=null&&!line.equals("/quit")) {
                out.println(ApiServlet.JSON.writeValueAsString(line.equals("/history")?Map.of("history",true):Map.of("body",line)));
                response=input.readLine();if(response==null){System.out.println("Connection closed.");break;}System.out.println(response);
            }
        }
    }
}
