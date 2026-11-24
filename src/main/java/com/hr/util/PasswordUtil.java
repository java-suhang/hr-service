package com.hr.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 비밀번호 해시 (NFR-01). JDK만 쓰는 PBKDF2-HMAC-SHA256.
 *
 * 저장 형식: pbkdf2_sha256$반복횟수$솔트(Base64)$해시(Base64)
 * 반복 횟수를 값에 같이 저장하므로 나중에 횟수를 올려도 기존 비밀번호를 그대로 검증할 수 있다.
 */
public final class PasswordUtil {

    private static final String PREFIX = "pbkdf2_sha256";
    private static final int ITERATIONS = 65_536;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    /** 형식이 깨진 값이면 false. 비교는 상수 시간으로 한다. */
    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (iterations < 1_000 || iterations > 10_000_000) {
                return false;
            }
            return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2를 사용할 수 없습니다", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** 사용 중인 비밀번호 규칙: 8~64자, 영문과 숫자를 모두 포함 */
    public static void validatePolicy(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw ApiException.badRequest("비밀번호는 8~64자로 입력하세요");
        }
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            throw ApiException.badRequest("비밀번호에 영문과 숫자를 모두 넣으세요");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw ApiException.badRequest("비밀번호가 너무 깁니다");
        }
    }
}
