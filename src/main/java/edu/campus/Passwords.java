package edu.campus;

import java.security.*;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class Passwords {
    static String hash(String password) {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + derive(password, salt);
    }
    static boolean matches(String password, String encoded) {
        String[] parts = encoded.split(":");
        return MessageDigest.isEqual(derive(password, Base64.getDecoder().decode(parts[0])).getBytes(java.nio.charset.StandardCharsets.UTF_8), parts[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static String derive(String password, byte[] salt) {
        try { return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, 210000, 256)).getEncoded()); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
