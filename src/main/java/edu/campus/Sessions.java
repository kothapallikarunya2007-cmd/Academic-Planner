package edu.campus;

import jakarta.servlet.http.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static edu.campus.Database.*;

/** Opaque cookies; only SHA-256 token digests are persisted. */
final class Sessions {
    private final Database db;
    Sessions(Database db) { this.db=db; }
    private static String digest(String token) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
    private String token(HttpServletRequest req) {
        if(req.getCookies()!=null) for(var c:req.getCookies()) if(c.getName().equals("CAMPUS_AUTH") && c.getValue().matches("[A-Za-z0-9_-]{43}")) return c.getValue();
        return null;
    }
    private void cookie(HttpServletResponse res,String token,int age) {
        Cookie c=new Cookie("CAMPUS_AUTH",token); c.setPath("/"); c.setHttpOnly(true); c.setSecure(Deployment.production() || "true".equalsIgnoreCase(System.getenv("SECURE_COOKIE"))); c.setAttribute("SameSite","Strict"); c.setMaxAge(age); res.addCookie(c);
    }
    void create(HttpServletRequest req,HttpServletResponse res,String user) throws Exception {
        revoke(req,res); byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try(var c=db.open()) { exec(c,"DELETE FROM app_sessions WHERE expires_at<?",System.currentTimeMillis()); exec(c,"INSERT INTO app_sessions VALUES(?,?,?)",digest(token),user,System.currentTimeMillis()+1800000); }
        cookie(res,token,1800);
    }
    Map<String,Object> user(HttpServletRequest req) throws Exception {
        String token=token(req); if(token==null) return null;
        try(var c=db.open()) { var users=rows(c,"SELECT u.id,u.name,u.email,u.role,u.department FROM users u JOIN app_sessions s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires_at>?",digest(token),System.currentTimeMillis()); return users.isEmpty()?null:users.get(0); }
    }
    void revoke(HttpServletRequest req,HttpServletResponse res) throws Exception { String token=token(req); if(token!=null) try(var c=db.open()){exec(c,"DELETE FROM app_sessions WHERE token_hash=?",digest(token));} cookie(res,"",0); }
    boolean allowLogin(String account) throws Exception {
        return db.transaction(c -> {
            long now=System.currentTimeMillis(); exec(c,"DELETE FROM login_attempts WHERE window_start<?",now-60000);
            var attempts=rows(c,"SELECT attempts FROM login_attempts WHERE account=?",account);
            if(attempts.isEmpty()) { exec(c,"INSERT INTO login_attempts VALUES(?,?,1)",account,now); return true; }
            if(((Number)attempts.get(0).get("attempts")).intValue()>=15) return false;
            exec(c,"UPDATE login_attempts SET attempts=attempts+1 WHERE account=?",account); return true;
        });
    }
}
