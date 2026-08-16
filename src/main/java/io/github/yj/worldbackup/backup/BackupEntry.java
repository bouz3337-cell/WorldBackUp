package io.github.yj.worldbackup.backup;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 백업 하나에 대한 메타데이터.
 *
 * @param roots    백업에 포함된 최상위 경로들(서버 루트 기준 상대 경로). 복원 시 이 경로들만 교체한다.
 * @param excludes 백업에서 제외했던 패턴. 복원 시 이 파일들은 건드리지 않는다.
 * @param complete 메타데이터를 읽어낸 정상 백업인지. false 면 압축이 끝나지 않았거나 zip 이 손상된 것으로 보고
 *                 복원 대상에서 제외하고, 보관 정책에서도 보호하지 않는다.
 * @param baseId   차등 백업이면 기준이 되는 전체 백업의 id, 전체 백업이면 null.
 *                 복원하려면 이 백업과 기준 백업 두 개가 모두 있어야 한다.
 */
public record BackupEntry(
        String id,
        Path archive,
        Instant createdAt,
        BackupType type,
        String label,
        long archiveBytes,
        long originalBytes,
        int fileCount,
        List<String> roots,
        List<String> worlds,
        List<String> excludes,
        String serverVersion,
        boolean locked,
        boolean complete,
        String baseId
) {

    public static final String ARCHIVE_PREFIX = "wb-";
    public static final String ARCHIVE_SUFFIX = ".zip";
    public static final String META_SUFFIX = ".yml";
    /** 사이드카 메타가 사라져도 보호 상태를 잃지 않도록 따로 두는 빈 마커 파일. */
    public static final String LOCK_SUFFIX = ".locked";
    /** zip 내부에 함께 저장되는 메타데이터 엔트리 이름. */
    public static final String META_ENTRY = "worldbackup-meta.yml";

    public static final DateTimeFormatter ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    public static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public static String newId(Instant instant) {
        return ID_FORMAT.format(instant);
    }

    public static String archiveName(String id) {
        return ARCHIVE_PREFIX + id + ARCHIVE_SUFFIX;
    }

    public static String metaName(String id) {
        return ARCHIVE_PREFIX + id + META_SUFFIX;
    }

    public static String lockName(String id) {
        return ARCHIVE_PREFIX + id + LOCK_SUFFIX;
    }

    /** 파일 이름에서 id 를 추출한다. 형식이 다르면 null. */
    public static String idFromArchive(Path archive) {
        String name = archive.getFileName().toString();
        if (!name.startsWith(ARCHIVE_PREFIX) || !name.endsWith(ARCHIVE_SUFFIX)) return null;
        return name.substring(ARCHIVE_PREFIX.length(), name.length() - ARCHIVE_SUFFIX.length());
    }

    public Path metaFile() {
        return archive.getParent().resolve(metaName(id));
    }

    public Path lockFile() {
        return archive.getParent().resolve(lockName(id));
    }

    public String displayTime() {
        return DISPLAY_FORMAT.format(createdAt);
    }

    public LocalDate localDate() {
        return LocalDate.ofInstant(createdAt, ZoneId.systemDefault());
    }

    public boolean hasLabel() {
        return label != null && !label.isBlank();
    }

    /**
     * 보관 정책에서 자동 삭제를 막아야 하는지.
     * 손상된 백업은 보호하지 않는다 - 남겨 둘 가치가 없다.
     */
    public boolean protectedFrom(boolean protectManual) {
        if (!complete) return false;
        return locked || (protectManual && type.manualLike());
    }

    /** {@code /wb lock} 으로 관리자가 직접 잠근 백업인지. 개수 상한에서도 제외된다. */
    public boolean explicitlyLocked() {
        return locked;
    }

    /** 기준 백업이 있어야만 복원할 수 있는 차등 백업인지. */
    public boolean isDifferential() {
        return baseId != null && !baseId.isBlank();
    }

    public BackupEntry withLocked(boolean value) {
        return new BackupEntry(id, archive, createdAt, type, label, archiveBytes, originalBytes,
                fileCount, roots, worlds, excludes, serverVersion, value, complete, baseId);
    }

    public BackupEntry withComplete(boolean value) {
        return new BackupEntry(id, archive, createdAt, type, label, archiveBytes, originalBytes,
                fileCount, roots, worlds, excludes, serverVersion, locked, value, baseId);
    }
}
