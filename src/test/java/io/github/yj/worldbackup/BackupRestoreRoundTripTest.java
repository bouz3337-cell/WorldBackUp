package io.github.yj.worldbackup;

import io.github.yj.worldbackup.backup.Archiver;
import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.BackupType;
import io.github.yj.worldbackup.backup.Manifest;
import io.github.yj.worldbackup.backup.UnreadableFile;
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
                3,
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

    /**
     * 복원이 실패하면 사람이 볼 표식을 남겨야 한다.
     * 이게 없으면 무인 서버는 반쯤 복원된 월드를 계속 백업하다 멀쩡한 백업을 밀어낸다.
     */
    @Test
    void failedRestoreLeavesAMarkerThatHoldsAutomaticWork() throws Exception {
        Path serverRoot = tmp.resolve("server12");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Files.createDirectories(dataFolder);

        assertTrue(RestoreApplier.failureMarkers(dataFolder).isEmpty(), "처음에는 표식이 없다");

        // 존재하지 않는 아카이브 -> 복원 실패
        new PendingRestore("missing", tmp.resolve("nowhere.zip"), null, "tester",
                System.currentTimeMillis(), false, 3, true, List.of(), List.of("world")).write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        List<Path> markers = RestoreApplier.failureMarkers(dataFolder);
        assertEquals(1, markers.size(), "복원 실패 표식이 남아야 한다");
        assertTrue(markers.get(0).getFileName().toString().startsWith(RestoreApplier.FAILURE_PREFIX));
        assertFalse(Files.exists(PendingRestore.processingFile(dataFolder)), "재시도 루프에 빠지지 않아야 한다");
    }

    /** 이후 복원이 성공하면 정지가 풀려야 한다. 기록 자체는 남는다. */
    @Test
    void successfulRestoreReleasesAnEarlierFailureHold() throws Exception {
        Path serverRoot = tmp.resolve("server13");
        Path world = serverRoot.resolve("world");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Path backupDir = dataFolder.resolve("backups");

        write(world.resolve("level.dat"), "LEVEL");
        write(serverRoot.resolve("server.properties"), "motd=hello");

        BackupRepository repository = new BackupRepository(backupDir, LOG);
        repository.ensureDirectory();
        BackupEntry entry = createBackup(repository, serverRoot, world, backupDir);

        // 지난번 복원이 실패해 정지된 상태를 재현한다.
        Path stale = dataFolder.resolve(RestoreApplier.FAILURE_PREFIX + "20260101-000000.yml");
        Files.writeString(stale, "id: broken\n", StandardCharsets.UTF_8);
        assertEquals(1, RestoreApplier.failureMarkers(dataFolder).size());

        write(world.resolve("level.dat"), "GRIEFED");
        new PendingRestore(entry.id(), entry.archive(), null, "tester", System.currentTimeMillis(),
                false, 3, true, concat(List.of("**/session.lock"), entry.excludes()), entry.roots())
                .write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("LEVEL", read(world.resolve("level.dat")), "복원은 성공해야 한다");
        assertTrue(RestoreApplier.failureMarkers(dataFolder).isEmpty(), "정지가 풀려야 한다");
        assertFalse(Files.exists(stale), "표식은 해제 꼬리표가 붙어 이름이 바뀐다");
        assertTrue(Files.isRegularFile(dataFolder.resolve(stale.getFileName() + ".resolved")),
                "기록 자체는 남아야 한다");
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
                false, 3, true, List.of(), List.of("world")).write(dataFolder);
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
                true, 3, true, List.of(), List.of("world")).write(dataFolder);
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
                true, 3, true, List.of(), List.of("world")).write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("LEVEL", read(world.resolve("level.dat")), "월드가 그대로 남아야 한다");
    }

    /**
     * 백업에 없는 경로가 섞여 있어도, 데이터가 있는 경로는 정상적으로 복원되어야 한다.
     *
     * <p>백업 시점에 비어 있던 {@code extra-paths} 폴더 하나 때문에 월드 복원까지 막히면
     * 정작 필요할 때 롤백을 못 한다. 반대로 그 경로를 비우기만 하고 채우지 않아도 안 된다.</p>
     */
    @Test
    void rootWithoutDataIsSkippedInsteadOfBlockingTheWholeRestore() throws Exception {
        Path serverRoot = tmp.resolve("server9");
        Path world = serverRoot.resolve("world");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Files.createDirectories(dataFolder);

        write(world.resolve("level.dat"), "LEVEL");
        write(serverRoot.resolve("config/keep-me.yml"), "설정은 그대로 남아야 한다");

        // world 데이터만 들어 있고 config/ 는 비어 있던 백업
        Path archive = tmp.resolve("world-only.zip");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("world/level.dat"));
            zip.write("LEVEL-BACKED-UP".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("config/"));   // 빈 폴더 엔트리뿐
            zip.closeEntry();
        }

        write(world.resolve("level.dat"), "GRIEFED");

        new PendingRestore("partial", archive, null, "tester", System.currentTimeMillis(),
                false, 3, true, List.of(), List.of("world", "config")).write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("LEVEL-BACKED-UP", read(world.resolve("level.dat")), "데이터가 있는 경로는 복원되어야 한다");
        assertEquals("설정은 그대로 남아야 한다", read(serverRoot.resolve("config/keep-me.yml")),
                "백업에 내용이 없는 경로는 비우지 않고 그대로 둔다");

        var report = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(dataFolder.resolve(PendingRestore.REPORT_NAME).toFile());
        assertTrue(report.getBoolean("success"), "일부 경로가 비어 있어도 복원 자체는 성공해야 한다");
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
                System.currentTimeMillis(), true, 3, true,
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
                System.currentTimeMillis(), false, 3, true,
                concat(List.of("**/session.lock"), diff.excludes()), diff.roots()).write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("LEVEL", read(world.resolve("level.dat")));
        assertTrue(Files.isDirectory(world.resolve("datapacks")), "유지된 빈 폴더는 복원되어야 한다");
        assertFalse(Files.exists(world.resolve("old_poi")),
                "차등 시점에 삭제된 빈 폴더가 기준 백업에서 되살아나면 안 된다");
    }

    /**
     * 폴더가 zip 에 남는 유일한 경로는 그 안의 파일 엔트리다.
     * 그래서 <b>제외 패턴에 전부 걸린 폴더</b>도 빈 폴더와 똑같이 따로 기록해 줘야 한다.
     * 그러지 않으면 복원 후 그 폴더가 통째로 사라진다.
     */
    @Test
    void directoryWhoseFilesAreAllExcludedStillSurvivesRestore() throws Exception {
        Path serverRoot = tmp.resolve("server10");
        Path world = serverRoot.resolve("world");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Path backupDir = dataFolder.resolve("backups");

        write(world.resolve("level.dat"), "LEVEL");
        write(serverRoot.resolve("server.properties"), "motd=hello");
        Files.createDirectories(world.resolve("empty_dir"));            // 진짜 빈 폴더
        write(world.resolve("locks_only/session.lock"), "LOCK");        // 내용이 전부 제외 대상

        BackupRepository repository = new BackupRepository(backupDir, LOG);
        repository.ensureDirectory();
        BackupEntry entry = createBackup(repository, serverRoot, world, backupDir);

        assertTrue(zipContains(entry.archive(), "world/empty_dir/"), "빈 폴더는 기록되어야 한다");
        assertTrue(zipContains(entry.archive(), "world/locks_only/"),
                "제외 파일만 든 폴더도 기록되어야 한다");
        assertFalse(zipContains(entry.archive(), "world/"),
                "내용이 있는 폴더는 굳이 엔트리를 만들지 않는다");

        FileUtil.deleteRecursively(world);

        new PendingRestore(entry.id(), entry.archive(), null, "tester", System.currentTimeMillis(),
                false, 3, true, concat(List.of("**/session.lock"), entry.excludes()), entry.roots())
                .write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("LEVEL", read(world.resolve("level.dat")));
        assertTrue(Files.isDirectory(world.resolve("empty_dir")), "빈 폴더가 복원되어야 한다");
        assertTrue(Files.isDirectory(world.resolve("locks_only")),
                "제외 파일만 있던 폴더도 복원되어야 한다");
    }

    /**
     * "그대로 둘 것" 으로 지정한 폴더는 비어 있어도 사라지지 않아야 한다.
     *
     * <p>기존 데이터를 비우는 쪽은 파일 단위로만 {@code preserve} 를 봤고, 빈 폴더는 따로
     * 지웠다. 그래서 안에 든 파일이 전부 보존 대상인 폴더는 파일만 남고 폴더 자체가
     * 지워지거나, 애초에 비어 있던 보존 폴더가 조용히 사라졌다. 백업에서 제외한 것을
     * 복원이 지우면, 제외했다는 사실이 곧 그 폴더를 잃는다는 뜻이 된다.</p>
     */
    @Test
    void preservedDirectoriesAreLeftAloneEvenWhenEmpty() throws Exception {
        Path serverRoot = tmp.resolve("server12");
        Path world = serverRoot.resolve("world");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Path backupDir = dataFolder.resolve("backups");

        write(world.resolve("level.dat"), "LEVEL");
        write(serverRoot.resolve("server.properties"), "motd=hello");

        BackupRepository repository = new BackupRepository(backupDir, LOG);
        repository.ensureDirectory();
        BackupEntry entry = createBackup(repository, serverRoot, world, backupDir);

        // 백업 이후에 생긴, 백업에서 제외되는 폴더들. 복원이 건드리면 안 된다.
        Files.createDirectories(world.resolve("logs"));                 // 보존 대상인데 비어 있다
        write(world.resolve("cache/blob.bin"), "CACHE");                // 안이 전부 보존 대상

        new PendingRestore(entry.id(), entry.archive(), null, "tester", System.currentTimeMillis(),
                false, 3, true,
                concat(List.of("**/logs/**", "**/cache/**"), entry.excludes()), entry.roots())
                .write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("LEVEL", read(world.resolve("level.dat")), "복원 자체는 정상이어야 한다");
        assertTrue(Files.isDirectory(world.resolve("logs")),
                "비어 있는 보존 폴더가 사라지면 안 된다");
        assertTrue(Files.isDirectory(world.resolve("cache")),
                "안이 전부 보존 대상인 폴더도 남아야 한다");
        assertEquals("CACHE", read(world.resolve("cache/blob.bin")), "보존 대상 파일은 그대로다");
    }

    /**
     * 백업 도중 읽다가 끊겨 <b>잘린 채로</b> zip 에 남은 파일은 복원하지 않는다.
     *
     * <p>zip 엔트리는 한 번 쓰면 되돌릴 수 없어서, 첫 조각을 읽은 뒤 I/O 오류가 나면
     * 잘린 엔트리가 아카이브에 남는다. 그걸 그대로 풀면 멀쩡했던 region 파일이
     * <b>깨진 파일로 덮어써진다.</b> 청크가 재생성되는 것(없는 파일)과 "Corrupt regionfile
     * header"(잘린 파일)는 전혀 다른 결과다.</p>
     *
     * <p>그래서 아카이브에 무엇이 들었는지는 zip 목록이 아니라 <b>매니페스트</b>가 정한다.
     * 매니페스트에는 끝까지 담은 파일만 적히기 때문이다.</p>
     */
    @Test
    void truncatedEntryIsNotRestoredOverGoodData() throws Exception {
        Path serverRoot = tmp.resolve("server13");
        Path world = serverRoot.resolve("world");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Path backupDir = dataFolder.resolve("backups");

        write(world.resolve("level.dat"), "LEVEL");
        write(serverRoot.resolve("server.properties"), "motd=hello");
        Path region = world.resolve("region/r.0.0.mca");
        Files.createDirectories(region.getParent());
        Files.write(region, new byte[300_000]);

        BackupRepository repository = new BackupRepository(backupDir, LOG);
        repository.ensureDirectory();

        // 첫 조각(128KB)까지만 읽히고 그 뒤가 끊긴다 = 잘린 엔트리가 남는다.
        BackupEntry entry = createBackup(repository, serverRoot, world, backupDir, null, null,
                UnreadableFile.failingAfterFirstChunk(region));

        assertTrue(zipContains(entry.archive(), "world/region/r.0.0.mca"),
                "이 테스트는 잘린 엔트리가 남은 상태를 재현해야 한다");
        assertFalse(Manifest.readFrom(entry.archive()).orElseThrow().stored("world/region/r.0.0.mca"),
                "끝까지 담지 못한 파일은 '꺼낼 수 있는 것' 에 들지 않는다");

        FileUtil.deleteRecursively(world);

        new PendingRestore(entry.id(), entry.archive(), null, "tester", System.currentTimeMillis(),
                false, 3, true, concat(List.of("**/session.lock"), entry.excludes()), entry.roots())
                .write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("LEVEL", read(world.resolve("level.dat")), "멀쩡한 파일은 복원되어야 한다");
        assertFalse(Files.exists(region),
                "잘린 파일을 풀면 깨진 region 파일이 된다. 없는 편이 낫다(청크가 재생성된다)");
    }

    /**
     * 차등본에서 잘린 파일은 <b>기준 백업의 예전 판</b>으로 되돌린다.
     *
     * <p>깨진 파일보다 조금 낡은 파일이 낫고, 없는 파일보다도 낫다. 차등 zip 이 그 파일을
     * 온전히 담았는지는 매니페스트로 판단하므로, 잘린 엔트리는 "차등이 담당한다" 로 세지
     * 않고 기준 쪽에서 꺼내 온다.</p>
     */
    @Test
    void truncatedDifferentialEntryFallsBackToTheBaseCopy() throws Exception {
        Path serverRoot = tmp.resolve("server14");
        Path world = serverRoot.resolve("world");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Path backupDir = dataFolder.resolve("backups");

        write(world.resolve("level.dat"), "LEVEL");
        write(serverRoot.resolve("server.properties"), "motd=hello");
        Path region = world.resolve("region/r.0.0.mca");
        Files.createDirectories(region.getParent());
        Files.write(region, filled(300_000, (byte) 'A'));   // 기준 백업에 담기는 판

        BackupRepository repository = new BackupRepository(backupDir, LOG);
        repository.ensureDirectory();
        BackupEntry full = createBackup(repository, serverRoot, world, backupDir, null, "full");

        // 파일이 바뀌었으니 차등본이 새로 담아야 하는데, 하필 읽다가 끊긴다.
        Thread.sleep(1100);
        Files.write(region, filled(300_000, (byte) 'B'));
        BackupEntry diff = createBackup(repository, serverRoot, world, backupDir, full, "diff",
                UnreadableFile.failingAfterFirstChunk(region));
        Manifest diffManifest = Manifest.readFrom(diff.archive()).orElseThrow();
        assertFalse(diffManifest.stored("world/region/r.0.0.mca"),
                "이 테스트는 차등본에 잘린 엔트리가 남은 상태를 재현해야 한다");
        assertTrue(diffManifest.contains("world/region/r.0.0.mca"),
                "그 시점에 파일이 있었다는 사실은 남아야 기준의 예전 판을 찾을 수 있다");

        FileUtil.deleteRecursively(world);

        new PendingRestore(diff.id(), diff.archive(), full.archive(), "tester", System.currentTimeMillis(),
                false, 3, true, concat(List.of("**/session.lock"), diff.excludes()), diff.roots())
                .write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertTrue(Files.exists(region), "기준 백업에 예전 판이 있으므로 비어 두지 않는다");
        byte[] restored = Files.readAllBytes(region);
        assertEquals(300_000, restored.length, "온전한 파일이어야 한다 - 잘린 판이 아니다");
        assertEquals((byte) 'A', restored[0], "기준 백업의 예전 판으로 되돌아간다");
        assertEquals((byte) 'A', restored[restored.length - 1]);
    }

    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    /**
     * 복원 전 공간 점검의 경계.
     *
     * <p>이 판단이 <b>잘못 거짓을 내면</b> 되돌릴 수 있었던 복원을 막는다. 반쯤 복원된 월드도
     * 나쁘지만 "되돌릴 방법이 아예 없다" 는 더 나쁠 수 있어서, 여유분은 작게 잡고 확실히
     * 모자랄 때만 막는다.</p>
     */
    @Test
    void theRestoreSpaceCheckOnlyBlocksWhenItIsCertainlyShort() {
        long gb = 1024L * 1024 * 1024;
        long headroom = 64L * 1024 * 1024;

        assertTrue(RestoreApplier.hasRoomToRestore(10 * gb, 20 * gb), "넉넉하면 통과");
        assertTrue(RestoreApplier.hasRoomToRestore(10 * gb, 10 * gb + headroom), "딱 맞아도 통과");
        assertFalse(RestoreApplier.hasRoomToRestore(10 * gb, 10 * gb), "여유분을 못 채우면 막는다");
        assertFalse(RestoreApplier.hasRoomToRestore(10 * gb, 5 * gb));

        assertTrue(RestoreApplier.hasRoomToRestore(0L, 0L), "쓸 것이 없으면 막을 이유가 없다");
        assertTrue(RestoreApplier.hasRoomToRestore(-1L, 0L), "크기를 모르면 막지 않는다");
        assertTrue(RestoreApplier.hasRoomToRestore(Long.MAX_VALUE, 1L), "넘치면 막을 근거가 없다");
    }

    /**
     * 공간이 넉넉한 평소에는 이 점검이 복원을 막지 않는다.
     *
     * <p>점검을 넣다가 정상 복원을 깨뜨리는 것이 가장 흔한 실수다. 실제 복원 왕복으로 확인한다.</p>
     */
    @Test
    void theSpaceCheckDoesNotGetInTheWayOfANormalRestore() throws Exception {
        Path serverRoot = tmp.resolve("server15");
        Path world = serverRoot.resolve("world");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Path backupDir = dataFolder.resolve("backups");

        write(world.resolve("level.dat"), "LEVEL");
        write(world.resolve("region/r.0.0.mca"), "REGION");
        write(serverRoot.resolve("server.properties"), "motd=hello");

        BackupRepository repository = new BackupRepository(backupDir, LOG);
        repository.ensureDirectory();
        BackupEntry entry = createBackup(repository, serverRoot, world, backupDir);

        FileUtil.deleteRecursively(world);
        new PendingRestore(entry.id(), entry.archive(), null, "tester", System.currentTimeMillis(),
                true, 3, true, concat(List.of("**/session.lock"), entry.excludes()), entry.roots())
                .write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("REGION", read(world.resolve("region/r.0.0.mca")), "평소 복원은 그대로 된다");
        assertEquals("LEVEL", read(world.resolve("level.dat")));
    }

    /**
     * 옛 버전이 남긴 복원 예약 파일도 읽을 수 있어야 한다.
     *
     * <p>예약 파일은 <b>서버 재시작을 건너 살아남는</b> 유일한 지시다. 플러그인을 올리는
     * 도중에 이미 예약이 걸려 있을 수 있는데, 새 키를 못 읽어 예약이 무시되면 관리자는
     * 복원이 됐다고 믿고 서버를 열게 된다.</p>
     */
    @Test
    void aPendingFileFromAnOlderVersionIsStillUnderstood() throws Exception {
        Path dataFolder = tmp.resolve("server16/plugins/WorldBackUp");
        Files.createDirectories(dataFolder);
        // keep-replaced-max 키가 없던 시절의 예약 파일
        write(dataFolder.resolve(PendingRestore.FILE_NAME), String.join("\n",
                "id: 20260101-000000",
                "archive: " + tmp.resolve("server16/backups/wb-20260101-000000.zip"),
                "requested-by: admin",
                "requested-at: 1",
                "keep-replaced: true",
                "verify-archive: false",
                "preserve: []",
                "roots:",
                "- world"));

        PendingRestore pending = PendingRestore.read(PendingRestore.file(dataFolder)).orElseThrow();

        assertEquals("20260101-000000", pending.id());
        assertTrue(pending.keepReplaced());
        assertEquals(3, pending.keepReplacedMax(), "적혀 있지 않으면 기본값으로 본다");
        assertEquals(List.of("world"), pending.roots());
    }

    /** 매니페스트를 한 줄씩 흘려 읽도록 바꿨으므로, 왕복이 정확한지 확인한다. */
    @Test
    void manifestSurvivesAStreamingRoundTrip() throws Exception {
        Path serverRoot = tmp.resolve("server11");
        Path world = serverRoot.resolve("world");
        Path backupDir = serverRoot.resolve("plugins/WorldBackUp/backups");

        write(world.resolve("level.dat"), "LEVEL");
        write(world.resolve("region/공백 있는 이름.mca"), "SPACED");  // 경로에 공백·한글
        write(serverRoot.resolve("server.properties"), "motd=hello");
        for (int i = 0; i < 500; i++) {                                // 여러 청크에 걸치도록
            write(world.resolve("playerdata/uuid-" + i + ".dat"), "P" + i);
        }

        BackupRepository repository = new BackupRepository(backupDir, LOG);
        repository.ensureDirectory();
        BackupEntry entry = createBackup(repository, serverRoot, world, backupDir);

        Manifest manifest = Manifest.readFrom(entry.archive()).orElseThrow();
        assertEquals(entry.fileCount(), manifest.paths().size(), "모든 파일이 목록에 있어야 한다");
        assertTrue(manifest.contains("world/region/공백 있는 이름.mca"), "공백이 든 경로도 살아야 한다");
        assertTrue(manifest.contains("world/playerdata/uuid-499.dat"));

        long size = Files.size(world.resolve("level.dat"));
        long modified = Files.getLastModifiedTime(world.resolve("level.dat")).toMillis();
        assertTrue(manifest.unchanged("world/level.dat", size, modified), "크기·수정 시각이 보존되어야 한다");
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
        return createBackup(repository, serverRoot, world, backupDir, base, suffix, Files::newInputStream);
    }

    /** @param opener 읽기 실패를 주입할 때만 쓴다. {@link UnreadableFile} */
    private BackupEntry createBackup(BackupRepository repository, Path serverRoot, Path world, Path backupDir,
                                     BackupEntry base, String suffix,
                                     Archiver.FileOpener opener) throws IOException {
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
                        roots, List.of("world"), EXCLUDES, "test", false, true, baseId, true)),
                null,
                LOG,
                opener
        );

        BackupEntry entry = new BackupEntry(id, archive, now, BackupType.MANUAL, "테스트",
                result.archiveBytes(), result.originalBytes(), result.fileCount(),
                roots, List.of("world"), EXCLUDES, "test", false, true, baseId, true);
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
