package io.github.yj.worldbackup;

import io.github.yj.worldbackup.backup.Archiver;
import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.BackupType;
import io.github.yj.worldbackup.backup.Manifest;
import io.github.yj.worldbackup.restore.PendingRestore;
import io.github.yj.worldbackup.restore.RestoreApplier;
import io.github.yj.worldbackup.util.FileUtil;
import io.github.yj.worldbackup.util.GlobMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "백업 -> 테러 -> 복원" 전체 흐름 검증.
 * Bukkit 서버 없이 파일 계층만으로 실제 동작을 재현한다.
 */
class BackupRestoreRoundTripTest {

    private static final Logger LOG = Logger.getLogger("WorldBackUpTest");

    private static final List<String> EXCLUDES = List.of(
            "**/session.lock",
            "**/logs/**",
            "plugins/WorldBackUp/backups/**"
    );

    @TempDir
    Path tmp;

    @Test
    void restoreUndoesGriefAndKeepsPreservedFiles() throws Exception {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Path backupDir = dataFolder.resolve("backups");

        // ---------- 백업 전 상태 ----------
        write(world.resolve("level.dat"), "LEVEL-V1");
        write(world.resolve("region/r.0.0.mca"), "REGION-0-0");
        write(world.resolve("region/r.0.1.mca"), "REGION-0-1");
        write(world.resolve("playerdata/8f0a-uuid.dat"), "DIAMOND=64");
        write(world.resolve("session.lock"), "LOCK");            // 제외 + 보존 대상
        write(world.resolve("logs/latest.log"), "log noise");     // 제외 대상
        write(serverRoot.resolve("server.properties"), "motd=hello");

        BackupRepository repository = new BackupRepository(backupDir, LOG);
        repository.ensureDirectory();

        BackupEntry entry = createBackup(repository, serverRoot, world, backupDir);

        // level.dat, region 2개, playerdata, server.properties = 5개
        assertEquals(5, entry.fileCount(), "제외 패턴이 적용된 파일 수");
        assertTrue(Files.isRegularFile(entry.archive()));

        // 사이드카 메타를 지워도 zip 내부 메타로 복구되는지 확인
        Files.delete(entry.metaFile());
        List<BackupEntry> listed = repository.list();
        assertEquals(1, listed.size());
        BackupEntry reloaded = listed.get(0);
        assertEquals(List.of("world", "server.properties"), reloaded.roots());
        assertEquals(List.of("world"), reloaded.worlds());
        assertEquals(EXCLUDES, reloaded.excludes());

        // ---------- 테러 발생 ----------
        Files.delete(world.resolve("region/r.0.0.mca"));            // 지형 삭제
        write(world.resolve("region/r.9.9.mca"), "GRIEFED");        // 이상한 지형 추가
        write(world.resolve("playerdata/8f0a-uuid.dat"), "DIAMOND=0"); // 인벤토리 초기화
        write(serverRoot.resolve("server.properties"), "motd=hacked");
        write(world.resolve("session.lock"), "LOCK-RUNNING");        // 서버가 잡고 있는 파일

        // ---------- 복원 ----------
        new PendingRestore(
                reloaded.id(),
                reloaded.archive(),
                null,
                "tester",
                System.currentTimeMillis(),
                true,
                true,
                concat(List.of("**/session.lock"), reloaded.excludes()),
                reloaded.roots()
        ).write(dataFolder);

        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        // ---------- 검증 ----------
        assertEquals("REGION-0-0", read(world.resolve("region/r.0.0.mca")), "삭제된 지형이 되살아나야 한다");
        assertEquals("REGION-0-1", read(world.resolve("region/r.0.1.mca")));
        assertEquals("DIAMOND=64", read(world.resolve("playerdata/8f0a-uuid.dat")), "인벤토리가 복구되어야 한다");
        assertEquals("motd=hello", read(serverRoot.resolve("server.properties")));
        assertEquals("LEVEL-V1", read(world.resolve("level.dat")));

        assertFalse(Files.exists(world.resolve("region/r.9.9.mca")), "백업에 없던 파일은 제거되어야 한다");
        assertEquals("LOCK-RUNNING", read(world.resolve("session.lock")), "preserve 대상은 그대로 남아야 한다");
        assertEquals("log noise", read(world.resolve("logs/latest.log")), "제외했던 파일은 복원이 건드리지 않는다");

        assertFalse(Files.exists(PendingRestore.file(dataFolder)), "예약 파일은 처리 후 사라져야 한다");
        assertFalse(Files.exists(PendingRestore.processingFile(dataFolder)));
        assertTrue(Files.isRegularFile(dataFolder.resolve(PendingRestore.REPORT_NAME)), "복원 보고서가 남아야 한다");
        assertTrue(Files.isDirectory(dataFolder.resolve("replaced")), "교체된 파일이 보관되어야 한다");
    }

    @Test
    void interruptedRestoreDoesNotLoopForever() throws Exception {
        Path serverRoot = tmp.resolve("server2");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Files.createDirectories(dataFolder);

        // 이전 시도가 중단된 상황을 재현한다.
        Files.writeString(PendingRestore.processingFile(dataFolder), "id: broken\narchive: nowhere\n");
        Files.writeString(PendingRestore.file(dataFolder), "id: broken\narchive: nowhere\n");

        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertFalse(Files.exists(PendingRestore.file(dataFolder)), "재시도 루프에 빠지지 않아야 한다");
        assertFalse(Files.exists(PendingRestore.processingFile(dataFolder)));
        try (var stream = Files.list(dataFolder)) {
            assertTrue(stream.anyMatch(p -> p.getFileName().toString().startsWith("restore-failed-")),
                    "실패 기록이 남아야 한다");
        }
    }

    @Test
    void zipSlipEntriesAreRejected() throws Exception {
        Path serverRoot = tmp.resolve("server3");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Files.createDirectories(dataFolder);
        Path archive = tmp.resolve("evil.zip");

        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("world/ok.dat"));
            zip.write("ok".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("world/../../escaped.txt"));
            zip.write("evil".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        new PendingRestore("evil", archive, null, "tester", System.currentTimeMillis(),
                false, true, List.of(), List.of("world")).write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("ok", read(serverRoot.resolve("world/ok.dat")));
        assertFalse(Files.exists(tmp.resolve("escaped.txt")), "서버 폴더 밖으로 빠져나가면 안 된다");
    }

    /** 핵심 안전장치: 깨진 백업으로는 기존 월드를 건드리지 않고 중단해야 한다. */
    @Test
    void corruptArchiveIsRejectedBeforeAnythingIsDeleted() throws Exception {
        Path serverRoot = tmp.resolve("server4");
        Path world = serverRoot.resolve("world");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Files.createDirectories(dataFolder);

        write(world.resolve("region/r.0.0.mca"), "SOULBOUND-TERRAIN");
        write(world.resolve("level.dat"), "LEVEL");

        // 정상 zip 을 만든 뒤 뒷부분을 잘라 압축 도중 서버가 죽은 상황을 흉내낸다.
        Path archive = tmp.resolve("truncated.zip");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("world/region/r.0.0.mca"));
            zip.write("BACKED-UP-TERRAIN".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] full = Files.readAllBytes(archive);
        Files.write(archive, java.util.Arrays.copyOf(full, full.length / 2));

        new PendingRestore("truncated", archive, null, "tester", System.currentTimeMillis(),
                true, true, List.of(), List.of("world")).write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("SOULBOUND-TERRAIN", read(world.resolve("region/r.0.0.mca")),
                "검증에 실패하면 기존 월드를 지우지 않아야 한다");
        assertEquals("LEVEL", read(world.resolve("level.dat")));

        var report = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(dataFolder.resolve(PendingRestore.REPORT_NAME).toFile());
        assertFalse(report.getBoolean("success"), "실패로 기록되어야 한다");
        assertEquals(0, report.getInt("removed-files"), "아무것도 지우지 않았어야 한다");
    }

    /** 백업에 없는 경로를 복원 대상으로 잡으면 월드를 비우기 전에 멈춰야 한다. */
    @Test
    void archiveMissingRequestedRootIsRejected() throws Exception {
        Path serverRoot = tmp.resolve("server5");
        Path world = serverRoot.resolve("world");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Files.createDirectories(dataFolder);
        write(world.resolve("level.dat"), "LEVEL");

        Path archive = tmp.resolve("other-world.zip");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("world_nether/level.dat"));
            zip.write("NETHER".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        new PendingRestore("mismatch", archive, null, "tester", System.currentTimeMillis(),
                true, true, List.of(), List.of("world")).write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("LEVEL", read(world.resolve("level.dat")), "월드가 그대로 남아야 한다");
    }

    /**
     * 차등 백업의 핵심 계약:
     * 바뀐 파일만 저장하지만, 복원하면 그 시점 상태가 <b>정확히</b> 재현되어야 한다.
     * 그 사이 삭제된 파일이 기준 백업에서 되살아나서도 안 된다.
     */
    @Test
    void differentialBackupRestoresExactSnapshot() throws Exception {
        Path serverRoot = tmp.resolve("server6");
        Path world = serverRoot.resolve("world");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Path backupDir = dataFolder.resolve("backups");

        write(world.resolve("level.dat"), "LEVEL");
        write(world.resolve("region/r.0.0.mca"), "TERRAIN-A");   // 끝까지 안 바뀜
        write(world.resolve("region/r.0.1.mca"), "TERRAIN-B");   // 차등 시점에 수정됨
        write(world.resolve("region/r.0.2.mca"), "TERRAIN-C");   // 차등 시점에 삭제됨
        write(serverRoot.resolve("server.properties"), "motd=hello");

        BackupRepository repository = new BackupRepository(backupDir, LOG);
        repository.ensureDirectory();

        BackupEntry full = createBackup(repository, serverRoot, world, backupDir, null, "full");

        // ---------- 전체 백업 이후의 변화 ----------
        Thread.sleep(1100); // 수정 시각이 확실히 달라지도록
        write(world.resolve("region/r.0.1.mca"), "TERRAIN-B-CHANGED");
        Files.delete(world.resolve("region/r.0.2.mca"));
        write(world.resolve("region/r.9.9.mca"), "TERRAIN-NEW");

        BackupEntry diff = createBackup(repository, serverRoot, world, backupDir, full, "diff");

        assertEquals(full.id(), diff.baseId(), "차등 백업은 기준을 기억해야 한다");
        assertTrue(diff.isDifferential());
        assertEquals(5, diff.fileCount(), "매니페스트에는 그 시점의 전체 파일이 담긴다");
        assertTrue(diff.archiveBytes() < full.archiveBytes(), "차등본이 전체 백업보다 작아야 한다");

        // 안 바뀐 파일은 차등 zip 에 들어 있지 않아야 한다.
        assertFalse(zipContains(diff.archive(), "world/region/r.0.0.mca"), "안 바뀐 파일은 다시 저장하지 않는다");
        assertTrue(zipContains(diff.archive(), "world/region/r.0.1.mca"), "바뀐 파일은 저장한다");
        assertTrue(zipContains(diff.archive(), "world/region/r.9.9.mca"), "새 파일은 저장한다");

        // ---------- 테러 발생 ----------
        FileUtil.deleteRecursively(world);
        write(world.resolve("region/r.0.0.mca"), "GRIEFED");
        write(serverRoot.resolve("server.properties"), "motd=hacked");

        // ---------- 차등 백업으로 복원 ----------
        new PendingRestore(diff.id(), diff.archive(), full.archive(), "tester",
                System.currentTimeMillis(), true, true,
                concat(List.of("**/session.lock"), diff.excludes()), diff.roots()).write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        // ---------- 검증: 차등 시점 상태가 그대로 ----------
        assertEquals("TERRAIN-A", read(world.resolve("region/r.0.0.mca")), "기준 백업에서 가져와야 한다");
        assertEquals("TERRAIN-B-CHANGED", read(world.resolve("region/r.0.1.mca")), "차등본에서 가져와야 한다");
        assertEquals("TERRAIN-NEW", read(world.resolve("region/r.9.9.mca")));
        assertEquals("LEVEL", read(world.resolve("level.dat")));
        assertEquals("motd=hello", read(serverRoot.resolve("server.properties")));

        assertFalse(Files.exists(world.resolve("region/r.0.2.mca")),
                "차등 시점에 삭제된 파일이 기준 백업에서 되살아나면 안 된다");
    }

    /**
     * 빈 폴더도 매니페스트와 같은 규칙을 따라야 한다.
     * 기준 백업 이후 사라진 빈 폴더가 차등 복원 때 되살아나면 안 된다.
     */
    @Test
    void differentialRestoreDoesNotResurrectDeletedEmptyDirectories() throws Exception {
        Path serverRoot = tmp.resolve("server8");
        Path world = serverRoot.resolve("world");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Path backupDir = dataFolder.resolve("backups");

        write(world.resolve("level.dat"), "LEVEL");
        write(serverRoot.resolve("server.properties"), "motd=hello");
        Files.createDirectories(world.resolve("datapacks"));  // 끝까지 유지되는 빈 폴더
        Files.createDirectories(world.resolve("old_poi"));    // 차등 시점에 사라지는 빈 폴더

        BackupRepository repository = new BackupRepository(backupDir, LOG);
        repository.ensureDirectory();

        BackupEntry full = createBackup(repository, serverRoot, world, backupDir, null, "full");

        Thread.sleep(1100);
        Files.delete(world.resolve("old_poi"));
        BackupEntry diff = createBackup(repository, serverRoot, world, backupDir, full, "diff");

        FileUtil.deleteRecursively(world);

        new PendingRestore(diff.id(), diff.archive(), full.archive(), "tester",
                System.currentTimeMillis(), false, true,
                concat(List.of("**/session.lock"), diff.excludes()), diff.roots()).write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("LEVEL", read(world.resolve("level.dat")));
        assertTrue(Files.isDirectory(world.resolve("datapacks")), "유지된 빈 폴더는 복원되어야 한다");
        assertFalse(Files.exists(world.resolve("old_poi")),
                "차등 시점에 삭제된 빈 폴더가 기준 백업에서 되살아나면 안 된다");
    }

    /** 기준 백업이 사라진 차등 백업은 목록에서 손상으로 표시되어야 한다. */
    @Test
    void differentialWithoutBaseIsMarkedBroken() throws Exception {
        Path serverRoot = tmp.resolve("server7");
        Path world = serverRoot.resolve("world");
        Path backupDir = serverRoot.resolve("plugins/WorldBackUp/backups");
        write(world.resolve("level.dat"), "LEVEL");
        write(serverRoot.resolve("server.properties"), "motd=hello");

        BackupRepository repository = new BackupRepository(backupDir, LOG);
        repository.ensureDirectory();

        BackupEntry full = createBackup(repository, serverRoot, world, backupDir, null, "full");
        Thread.sleep(1100);
        write(world.resolve("level.dat"), "LEVEL-2");
        BackupEntry diff = createBackup(repository, serverRoot, world, backupDir, full, "diff");

        assertTrue(repository.list().stream().allMatch(BackupEntry::complete), "둘 다 멀쩡해야 한다");
        assertEquals(1, repository.dependents(repository.list(), full.id()).size());

        repository.delete(full); // 기준을 잃어버린 상황

        BackupEntry orphan = repository.list().stream()
                .filter(e -> e.id().equals(diff.id())).findFirst().orElseThrow();
        assertFalse(orphan.complete(), "기준이 없는 차등 백업은 복원 불가로 표시되어야 한다");
    }

    // ------------------------------------------------------------------

    private static boolean zipContains(Path archive, String entryName) throws IOException {
        try (var zip = new java.util.zip.ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            return zip.getEntry(entryName) != null;
        }
    }

    private BackupEntry createBackup(BackupRepository repository, Path serverRoot, Path world, Path backupDir)
            throws IOException {
        return createBackup(repository, serverRoot, world, backupDir, null, null);
    }

    /** @param base null 이면 전체 백업, 아니면 그 백업을 기준으로 하는 차등 백업 */
    private BackupEntry createBackup(BackupRepository repository, Path serverRoot, Path world, Path backupDir,
                                     BackupEntry base, String suffix) throws IOException {
        Instant now = Instant.now();
        String id = BackupEntry.newId(now) + (suffix == null ? "" : "-" + suffix);
        Path archive = backupDir.resolve(BackupEntry.archiveName(id));
        List<Path> targets = List.of(world, serverRoot.resolve("server.properties"));
        List<String> roots = List.of("world", "server.properties");
        String baseId = base == null ? null : base.id();
        Manifest baseManifest = base == null ? null : Manifest.readFrom(base.archive()).orElseThrow();

        Archiver.Result result = Archiver.create(
                archive,
                serverRoot,
                targets,
                1,
                new GlobMatcher(EXCLUDES),
                baseManifest,
                0L,
                (fileCount, originalBytes) -> repository.toYamlString(new BackupEntry(
                        id, archive, now, BackupType.MANUAL, "테스트", 0L, originalBytes, fileCount,
                        roots, List.of("world"), EXCLUDES, "test", false, true, baseId)),
                null,
                LOG
        );

        BackupEntry entry = new BackupEntry(id, archive, now, BackupType.MANUAL, "테스트",
                result.archiveBytes(), result.originalBytes(), result.fileCount(),
                roots, List.of("world"), EXCLUDES, "test", false, true, baseId);
        repository.writeMeta(entry);
        return entry;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).distinct().toList();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
