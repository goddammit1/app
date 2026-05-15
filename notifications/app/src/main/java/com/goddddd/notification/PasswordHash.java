package com.goddddd.notification;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.io.UnsupportedEncodingException;

/**
 * Salted SHA-256 password hashing.
 * Storage layout: per-user we keep {salt, passHash} where
 *     passHash = sha256(salt + password).
 *
 * Not cryptographically strong by modern standards (bcrypt/argon2 would be
 * better), but adequate for a small private app: passwords never travel
 * un-hashed and are useless without the per-user random salt.
 */
public final class PasswordHash {

    private PasswordHash() {}

    public static String generateSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return bytesToHex(bytes);
    }

    public static String hash(String salt, String password) {
        return sha256((salt == null ? "" : salt) + (password == null ? "" : password));
    }

    public static boolean verify(String salt, String passHash, String password) {
        if (salt == null || passHash == null) return false;
        return hash(salt, password).equalsIgnoreCase(passHash);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}