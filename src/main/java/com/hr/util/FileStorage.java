package com.hr.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 첨부파일을 디스크에 저장한다 (C). 파일 이름은 UUID라 원래 이름·경로가 저장 위치에 영향을 주지 않는다.
 *
 * 저장 폴더 (앞에서부터 먼저 찾은 값):
 *   1. JVM 옵션 -Dhr.upload.dir=...
 *   2. 환경 변수 HR_UPLOAD_DIR
 *   3. db.properties의 upload.dir
 *   4. {사용자 홈}/hr-uploads
 */
public final class FileStorage {

    private static final Pattern STORED_NAME = Pattern.compile("^[0-9a-f]{32}\\.(jpg|png|pdf)$");

    private FileStorage() {
    }

    public static Path dir() throws IOException {
        String configured = System.getProperty("hr.upload.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("HR_UPLOAD_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = DBUtil.property("upload.dir");
        }
        Path dir = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), "hr-uploads")
                : Path.of(configured.trim());
        return Files.createDirectories(dir.toAbsolutePath().normalize());
    }

    /** 바이트를 새 파일로 저장하고 저장 이름(UUID.확장자)을 돌려준다. 임시 파일에 쓴 뒤 옮겨서 반쯤 쓴 파일이 남지 않게 한다 */
    public static String save(byte[] data, String extension) throws IOException {
        Path dir = dir();
        String name = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        if (!STORED_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("지원하지 않는 확장자: " + extension);
        }
        Path tmp = Files.createTempFile(dir, "upload-", ".tmp");
        try {
            Files.write(tmp, data);
            Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return name;
    }

    /** 저장 이름 → 실제 경로. 형식이 다르면(경로 조작 등) 예외 */
    public static Path path(String storedName) throws IOException {
        if (storedName == null || !STORED_NAME.matcher(storedName).matches()) {
            throw new IllegalArgumentException("잘못된 저장 파일 이름");
        }
        return dir().resolve(storedName);
    }

    /** DB 저장이 실패했을 때 방금 쓴 파일을 지운다 */
    public static void deleteQuietly(String storedName) {
        try {
            Files.deleteIfExists(path(storedName));
        } catch (IOException | RuntimeException ignored) {
            // 지우지 못한 파일은 DB에 연결되지 않으므로 다운로드되지 않는다
        }
    }

    public static String contentType(String storedName) {
        if (storedName.endsWith(".png")) {
            return "image/png";
        }
        if (storedName.endsWith(".pdf")) {
            return "application/pdf";
        }
        return "image/jpeg";
    }
}
