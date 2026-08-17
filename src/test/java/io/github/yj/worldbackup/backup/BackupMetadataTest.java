package io.github.yj.worldbackup.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 백업 메타데이터를 <b>읽어들이는</b> 경로 검증.
 *
 * <p>여기가 틀리면 실패가 조용하다. 손상된 백업이 목록에 정상으로 뜨고, 차등 백업이 전체
 * 백업으로 둔갑하며, 복원은 대상 경로를 좁히지 못한 채 바뀐 파일 몇 개를 살아 있는 월드
 * 위에 흩뿌린다. zip 자체는 멀쩡하므로 {@code verify-archive} 도 그걸 잡지 못한다.</p>
 */
class BackupMetadataTest {

    private static final Logger LOG = Logger.getLogger("BackupMetadataTest");

    @TempDir
    Path tmp;

    /**
     * 사이드카를 쓰다 만 상태에서도 차등 백업이 전체 백업으로 둔갑하지 않는다.
     *
     * <p>{@code YamlConfiguration.loadConfiguration} 은 파일이 깨져 있어도 예외 없이 빈 설정을
     * 돌려준다. 그걸 그대로 믿으면 {@code base-id} 와 {@code roots} 가 통째로 사라진 채
     * "정상 백업"으로 잡힌다. 같은 내용이 zip 안에 한 벌 더 들어 있으므로 거기서 되찾는다.</p>
     */
    @Test
    void truncatedSidecarFallsBackToTheArchiveCopy() throws Exception {
        BackupRepository repo = repository();
        BackupEntry original = differentialEntry(repo, "20260101-120000", "20251231-030000");
        writeArchiveWithMeta(repo, original);

        // 메타를 쓰는 도중 서버가 죽어 0바이트만 남았다.
        Files.writeString(original.metaFile(), "", StandardCharsets.UTF_8);

        BackupEntry loaded = only(repo);
        assertEquals("20251231-030000", loaded.baseId(),
                "zip 안에 base-id 가 있는데 빈 사이드카 때문에 전체 백업으로 보이면 안 된다");
        assertTrue(loaded.isDifferential());
        assertEquals(List.of("world"), loaded.roots(),
                "roots 가 비면 복원이 대상을 좁히지 못하고 덮어쓰기만 한다");
        assertTrue(loaded.hasPlayerData());

        // 그리고 base-id 를 되찾았으므로 "기준이 사라진 차등본" 판정이 비로소 걸린다.
        // 빈 사이드카를 믿던 때는 이 백업이 <b>정상 전체 백업</b>으로 보여, 복원까지 그대로 갔다.
        assertFalse(loaded.complete(), "기준 백업이 없으니 복원 불가로 잡혀야 한다");
    }

    /** 기준 백업이 제자리에 있으면 되찾은 메타로 정상 복원 대상이 된다. 위 테스트의 짝이다. */
    @Test
    void aRecoveredDifferentialIsRestorableOnceItsBaseIsThere() throws Exception {
        BackupRepository repo = repository();
        BackupEntry base = differentialEntry(repo, "20251231-030000", null);
        writeArchiveWithMeta(repo, base);
        repo.writeMeta(base);

        BackupEntry diff = differentialEntry(repo, "20260101-120000", "20251231-030000");
        writeArchiveWithMeta(repo, diff);
        Files.writeString(diff.metaFile(), "", StandardCharsets.UTF_8);

        BackupEntry loaded = repo.list().stream()
                .filter(e -> e.id().equals("20260101-120000")).findFirst().orElseThrow();
        assertEquals("20251231-030000", loaded.baseId());
        assertTrue(loaded.complete(), "기준이 있으면 복원할 수 있다");
    }

    /**
     * <b>앞쪽 키가 살아남은</b> 사이드카도 믿지 않는다. 가장 고약한 형태다.
     *
     * <p>{@code id} 와 {@code created-at} 은 맨 처음 쓰이는 두 줄이다. 그 둘만 보고 판단하면
     * 정작 위험한 파일 - 알맹이인 {@code roots} 와 {@code base-id} 부터 잘려 나간 파일 - 이
     * 그대로 통과한다. YAML 문법으로는 멀쩡하고 zip 도 정상이라 {@code verify-archive} 조차
     * 잡지 못하는데, 목록에는 [손상] 표시 없이 <b>정상 전체 백업</b>으로 뜬다. 그 상태로 복원에
     * 들어가면 대상 경로를 좁히지 못한 채 바뀐 파일 몇 개를 살아 있는 월드 위에 흩뿌린다.</p>
     */
    @Test
    void aSidecarTruncatedAfterTheLeadingKeysIsAlsoRejected() throws Exception {
        BackupRepository repo = repository();
        BackupEntry original = differentialEntry(repo, "20260101-120000", "20251231-030000");
        writeArchiveWithMeta(repo, original);

        // roots 직전까지 쓰이고 끊겼다. 앞쪽 키는 전부 제자리에 있다.
        Files.writeString(original.metaFile(),
                "id: 20260101-120000\n"
                        + "created-at: 1767236400000\n"
                        + "created-at-text: '2026-01-01 12:00:00'\n"
                        + "type: SCHEDULED\n"
                        + "original-bytes: 4\n"
                        + "file-count: 1\n",
                StandardCharsets.UTF_8);

        BackupEntry loaded = only(repo);
        assertEquals("20251231-030000", loaded.baseId(),
                "앞쪽 키만 보고 통과시키면 차등 백업이 전체 백업으로 둔갑한다");
        assertTrue(loaded.isDifferential());
        assertEquals(List.of("world"), loaded.roots(),
                "roots 가 비면 복원이 대상을 좁히지 못하고 덮어쓰기만 한다");
        assertFalse(loaded.complete(), "기준 백업이 없으니 복원 불가로 잡혀야 한다");
    }

    /** 되찾은 메타는 사이드카에 다시 적어 둔다. 다음부터는 zip 을 열지 않아도 된다. */
    @Test
    void theRecoveredSidecarIsWrittenBackInFullyReadableForm() throws Exception {
        BackupRepository repo = repository();
        BackupEntry original = differentialEntry(repo, "20260101-120000", "20251231-030000");
        writeArchiveWithMeta(repo, original);
        // 첫 줄까지만 쓰이고 끊겼다. YAML 로는 멀쩡히 읽히는데 알맹이가 없는, 가장 고약한 형태다.
        Files.writeString(original.metaFile(), "id: 20260101-120000\n", StandardCharsets.UTF_8);

        only(repo); // 여기서 복구가 일어난다

        String repaired = Files.readString(original.metaFile(), StandardCharsets.UTF_8);
        assertTrue(repaired.contains("base-id: 20251231-030000"), "실제 내용:\n" + repaired);
        assertTrue(noTempFilesLeftIn(repo.directory()), "복구 과정에서 임시 파일이 남으면 안 된다");
    }

    /**
     * 사이드카도 zip 메타도 못 읽는 백업은 <b>계속</b> 손상으로 남는다.
     *
     * <p>{@code /wb lock} 이 이 상태를 뒤집던 적이 있다. 보호 상태를 저장하려고 메타데이터를
     * 다시 쓰는데, 그 메타데이터가 파일 이름에서 재구성한 빈 껍데기였다. 그걸 사이드카로
     * 적는 순간 다음 목록부터 [손상] 표시가 사라지고 복원까지 허용된다 - 남겨 두려고 잠근
     * 행동이 오히려 위험한 백업을 하나 만들어 내는 셈이다.</p>
     */
    @Test
    void lockingADamagedBackupNeverFabricatesMetadata() throws Exception {
        BackupRepository repo = repository();
        // 압축 도중 서버가 죽어 잘린 zip. 사이드카도, zip 내부 메타도 없다.
        Path archive = repo.directory().resolve(BackupEntry.archiveName("20260101-120000"));
        Files.writeString(archive, "zip 이 되다 만 조각", StandardCharsets.UTF_8);

        BackupEntry damaged = only(repo);
        assertFalse(damaged.complete(), "이 테스트는 손상 상태에서 출발해야 한다");

        assertTrue(repo.setLocked(damaged, true), "보호 마커 자체는 남길 수 있다");

        BackupEntry after = only(repo);
        assertFalse(after.complete(), "lock 했다고 손상 백업이 정상으로 바뀌면 안 된다");

        // 여기서 예전에는 protectedFrom 이 false 였다 - "복원에 못 쓰는 백업을 보관 정책이
        // 지켜 줄 이유가 없다" 는 판단이었다. 그 판단을 바꾼 이유는 하나뿐이다.
        //
        // "손상" 에는 <b>메타데이터를 읽지 못했다</b> 가 포함되고, 그것은 일시적일 수 있다 -
        // 네트워크 저장소가 삐끗했거나, 백업 폴더를 밖으로 동기화하는 중(zip 은 도착했고
        // 사이드카는 아직)이거나. 그 판정 하나로 잠금을 풀어 버리면, 관리자가 영구 보관하려고
        // 직접 잠근 백업이 조용히 사라진다. 되돌릴 수 없는 손해다.
        //
        // 반대 방향의 손해는 되돌릴 수 있다 - 정말 못 쓰는 zip 이 공간을 차지할 뿐이고,
        // /wb list 에 [보호] [손상] 으로 보이며 /wb unlock 뒤 지울 수 있다. 같은 이유로
        // freeUpSpace 와 prune 도 "손상으로 보인다" 는 것만 믿고 비우지 않는다.
        //
        // 새로 잠그는 길은 그대로 막혀 있다(CommandGuards.lock) - 손상된 백업이 이 보호를
        // 새로 얻는 일은 없고, 이미 잠가 둔 것이 판정 하나로 풀리지만 않는다.
        assertTrue(after.protectedFrom(true),
                "관리자가 직접 잠근 것은 손상 판정 하나로 풀리지 않는다");
    }

    /** 메타데이터는 통째로 갈아 끼운다. 쓰다 만 조각이 남지 않는다. */
    @Test
    void metadataIsSwappedInWholeNeverLeftHalfWritten() throws Exception {
        BackupRepository repo = repository();
        BackupEntry entry = differentialEntry(repo, "20260101-120000", null);
        Files.writeString(entry.archive(), "payload", StandardCharsets.UTF_8);

        repo.writeMeta(entry);
        assertTrue(noTempFilesLeftIn(repo.directory()));

        repo.writeMeta(entry.withLocked(true)); // 덮어쓰기도 마찬가지
        assertTrue(noTempFilesLeftIn(repo.directory()));
        assertTrue(only(repo).locked());
    }

    // ------------------------------------------------------------------

    private BackupRepository repository() throws IOException {
        BackupRepository repo = new BackupRepository(tmp.resolve("backups"), LOG);
        repo.ensureDirectory();
        return repo;
    }

    private BackupEntry only(BackupRepository repo) {
        List<BackupEntry> entries = repo.list();
        assertEquals(1, entries.size(), "이 테스트는 백업 하나만 다룬다");
        return entries.get(0);
    }

    private BackupEntry differentialEntry(BackupRepository repo, String id, String baseId) {
        return new BackupEntry(id, repo.directory().resolve(BackupEntry.archiveName(id)),
                Instant.parse("2026-01-01T03:00:00Z"), BackupType.SCHEDULED, null,
                0L, 4L, 1, List.of("world"), List.of("world"), List.of(),
                "test", false, true, baseId, Boolean.TRUE);
    }

    /** 메타가 들어 있는 <b>진짜</b> zip 을 만든다. 실제 백업과 같은 형식이어야 의미가 있다. */
    private void writeArchiveWithMeta(BackupRepository repo, BackupEntry entry) throws IOException {
        try (OutputStream out = Files.newOutputStream(entry.archive());
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("world/level.dat"));
            zip.write("데이터".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(BackupEntry.META_ENTRY));
            zip.write(repo.toYamlString(entry).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static boolean noTempFilesLeftIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.noneMatch(path -> path.getFileName().toString().endsWith(Archiver.TEMP_SUFFIX));
        }
    }
}
