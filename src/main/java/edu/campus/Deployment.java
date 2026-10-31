package edu.campus;

import java.util.Map;

final class Deployment {
    static boolean production() { return "production".equals(System.getenv("APP_ENV")) || System.getenv("VERCEL") != null; }
    static void validate(Map<String,String> env) {
        String url=env.getOrDefault("DB_URL", "");
        if(!url.startsWith("jdbc:postgresql://")) throw new IllegalStateException("Production requires DB_URL for an external PostgreSQL database.");
        if(!url.contains("sslmode=verify-full")) throw new IllegalStateException("Production DB_URL must use sslmode=verify-full.");
        if(env.getOrDefault("DB_USER", "").isBlank() || env.getOrDefault("DB_PASSWORD", "").isBlank()) throw new IllegalStateException("Set DB_USER and DB_PASSWORD.");
        if(!"false".equalsIgnoreCase(env.get("SEED_DEMO"))) throw new IllegalStateException("Production requires SEED_DEMO=false.");
        if(!"true".equalsIgnoreCase(env.get("SECURE_COOKIE"))) throw new IllegalStateException("Production requires SECURE_COOKIE=true.");
        if(env.containsKey("CHAT_PORT")) throw new IllegalStateException("CHAT_PORT is only supported for local teaching.");
    }
}
