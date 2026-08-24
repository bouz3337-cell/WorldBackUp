package io.github.yj.worldbackup;

import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.Manifest;
import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.config.ConfigMigrator;
import io.github.yj.worldbackup.restore.PendingRestore;
import io.github.yj.worldbackup.restore.RestoreApplier;
import io.github.yj.worldbackup.util.FileUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>jar 만 갈아 끼웠을 때 서버에서 실제로 무슨 일이 일어나는가.</b>
 *
 * <p>1.0.0 이 돌던 서버 폴더를 그대로 만들어 놓고(설정·백업 48개·월드), 플러그인이 올라오는
 * 순서 그대로 지금 코드를 태운다 - 설정 마이그레이션 → 설정 로드 → 시작 경고 → 보관 정리 →
 * 복원. 각 단계에서 무엇이 달라지는지 숫자로 남긴다.</p>
 *
 * <p>실서버를 띄우지는 못하지만, 여기서 도는 것은 <b>전부 실제 코드</b>다
 * ({@link ConfigMigrator} · {@link BackupSettings} · {@link BackupRepository#prune} ·
 * {@link RestoreApplier}). 서버 인스턴스가 필요한 것은 이 경로에 없다.</p>
 *
 * <p>이 시뮬레이션이 지키는 약속은 하나다 - <b>업그레이드가 되돌릴 수단을 조용히 줄이지
 * 않는다.</b> 줄인다면 몇 개를 줄이는지 여기 적혀 있어야 한다.</p>
 */
class UpgradeFromOneZeroSimulationTest {

    private static final Logger LOG = Logger.getLogger("UpgradeSim");

    /** 1.0.0 기본값 그대로: 30분 주기, 최대 48개 -> 정상 서버는 24시간치를 들고 있다. */
    private static final int BACKUP_COUNT = 48;
    private static final int INTERVAL_MINUTES = 30;

    private static final DateTimeFormatter ID =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------

    /**
     * 1.0.0 기본 설정({@code mode: full}) 서버에 jar 만 올린다.
     *
     * <p>가장 흔한 경우다 - 1.0.0 은 {@code full} 이 기본값이었고, 대부분은 그것을 그대로 썼다.</p>
     */
    @Test
    void swappingOnlyTheJarOnADefaultOneZeroServer() throws Exception {
        Path server = tmp.resolve("server-full");
        Path dataFolder = server.resolve("plugins/WorldBackUp");
        Path backups = dataFolder.resolve("backups");
        buildLegacyServer(server, false);

        report("1) jar 를 바꾸기 전 - 1.0.0 설정 그대로");
        BackupSettings before = loadSettings(dataFolder, server);
        List<BackupEntry> beforeList = new BackupRepository(backups, LOG).list();
        describe(before, beforeList);
        assertEquals(BACKUP_COUNT, beforeList.size(), "1.0.0 서버는 48개를 들고 있다");
        assertTrue(before.tiers().isEmpty(), "1.0.0 설정에는 계단이 없다");

        report("2) jar 교체 - 플러그인이 config.yml 에 새 설정을 끼워 넣는다");
        List<String> added = migrate(dataFolder);
        report("   추가된 설정: " + String.join(", ", added));
        assertTrue(added.contains("retention.tiers"), "새 설정은 파일에 들어온다");

        report("3) 새 설정으로 다시 읽는다");
        BackupSettings after = loadSettings(dataFolder, server);
        describe(after, beforeList);
        assertTrue(after.tiers().isEmpty(),
                "계단은 파일에 들어오되 <b>비어서</b> 들어온다 - 켜는 것은 관리자 몫이다");
        assertEquals(before.maxBackups(), after.maxBackups(), "옛 값이 그대로 살아 있다");
        assertEquals(before.maxAgeDays(), after.maxAgeDays());
        assertEquals(BackupSettings.Plugins.ALL, after.plugins(),
                "plugins/ 는 이제 함께 담긴다 - 백업이 커지는 것은 되돌릴 수 있지만 "
                        + "백업에 없는 플러그인 데이터는 되돌릴 수 없다");
        assertTrue(after.tierWarnings().isEmpty(),
                "동작이 그대로이므로 경고할 것도 없다");

        report("4) 첫 보관 정리가 도는 순간");
        Path copy = copyOf(backups, tmp.resolve("after-full"));
        BackupRepository repo = new BackupRepository(copy, LOG);
        BackupRepository.PruneResult pruned = repo.prune(after);
        int survivors = repo.list().size();
        report("   삭제 " + pruned.deleted() + "개 (" + FileUtil.humanBytes(pruned.freedBytes())
                + ") · 남은 백업 " + survivors + "개");
        report("   되돌릴 수 있는 시점: " + BACKUP_COUNT + "개 -> " + survivors + "개");

        // 같은 백업 폴더를 1.0.0 정책으로 정리했다면 무엇이 남았을지
        Path control = copyOf(backups, tmp.resolve("control-full"));
        BackupRepository controlRepo = new BackupRepository(control, LOG);
        BackupRepository.PruneResult controlPruned = controlRepo.prune(before);
        report("   (비교) 1.0.0 정책이었다면 - 삭제 " + controlPruned.deleted()
                + "개 · 남은 백업 " + controlRepo.list().size() + "개");

        assertEquals(controlPruned.deleted(), pruned.deleted(),
                "<b>이 시뮬레이션의 요점</b> - jar 만 바꿨는데 백업이 더 지워지면 안 된다");
        assertEquals(BACKUP_COUNT, survivors, "되돌릴 수 있는 시점이 하나도 줄지 않아야 한다");

        report("5) 가장 오래된 백업으로 복원해 본다");
        BackupEntry oldest = repo.list().get(repo.list().size() - 1);
        restoreAndVerify(server, dataFolder, oldest, repo);
        report("   복원 성공: " + oldest.id() + " (" + oldestAge(repo.list()) + " 전)");
    }

    /**
     * 관리자가 {@code mode: differential} 로 바꿔 두었던 서버.
     *
     * <p>차등은 기준 백업이 함께 살아 있어야 풀린다. 보관 정책이 바뀌면서 기준을 지워 버리면
     * 남은 차등본은 <b>목록에는 보이는데 복원은 안 되는</b> 상태가 된다.</p>
     */
    @Test
    void swappingOnlyTheJarOnADifferentialOneZeroServer() throws Exception {
        Path server = tmp.resolve("server-diff");
        Path dataFolder = server.resolve("plugins/WorldBackUp");
        Path backups = dataFolder.resolve("backups");
        buildLegacyServer(server, true);

        report("1) jar 를 바꾸기 전 - mode: differential 로 쓰던 1.0.0 서버");
        loadSettings(dataFolder, server);
        List<BackupEntry> beforeList = new BackupRepository(backups, LOG).list();
        long fulls = beforeList.stream().filter(e -> e.baseId() == null).count();
        report("   백업 " + beforeList.size() + "개 (전체 " + fulls + "개 + 차등 "
                + (beforeList.size() - fulls) + "개)");

        report("2) jar 교체 후 첫 보관 정리");
        migrate(dataFolder);
        BackupSettings after = loadSettings(dataFolder, server);
        Path copy = copyOf(backups, tmp.resolve("after-diff"));
        BackupRepository repo = new BackupRepository(copy, LOG);
        BackupRepository.PruneResult pruned = repo.prune(after);
        List<BackupEntry> survivors = repo.list();
        report("   삭제 " + pruned.deleted() + "개 · 남은 백업 " + survivors.size() + "개");

        report("3) 남은 차등본이 전부 복원 가능한가 (기준 백업이 살아 있는가)");
        List<String> orphans = new ArrayList<>();
        for (BackupEntry entry : survivors) {
            if (entry.baseId() == null) continue;
            if (repo.base(entry).isEmpty()) orphans.add(entry.id());
        }
        report("   기준을 잃은 차등본: " + (orphans.isEmpty() ? "없음" : orphans.toString()));
        assertTrue(orphans.isEmpty(),
                "기준이 사라지면 그 차등본은 목록에 보이면서 복원은 안 되는 백업이 된다");

        report("4) 살아남은 차등본 하나를 실제로 복원");
        BackupEntry diff = survivors.stream().filter(e -> e.baseId() != null).findFirst().orElse(null);
        if (diff != null) {
            restoreAndVerify(server, dataFolder, diff, repo);
            report("   복원 성공: " + diff.id() + " (기준 " + diff.baseId() + ")");
        }
    }

    /**
     * 꺼진 채로 들어온 새 기능을 <b>관리자가 켤 수 있는가.</b>
     *
     * <p>업그레이드가 동작을 바꾸지 않는 것은 절반일 뿐이다. 나머지 절반은 "쓰고 싶을 때
     * 쓸 수 있어야 한다" 는 것이다. 켜는 방법이 파일 그 자리에 적혀 있어야 하고, 켠 뒤에는
     * 다음 부팅에서 마이그레이터가 다시 꺼 놓지 않아야 한다.</p>
     */
    @Test
    void theAdminCanTurnOnWhatTheUpgradeLeftOff() throws Exception {
        Path server = tmp.resolve("server-optin");
        Path dataFolder = server.resolve("plugins/WorldBackUp");
        buildLegacyServer(server, false);
        migrate(dataFolder);

        Path config = dataFolder.resolve("config.yml");
        String text = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(text.contains("#        - { every: 0,"),
                "켜는 방법(배포 기본값)이 그 자리에 주석으로 남아 있어야 한다");

        // 관리자가 계단을 켠다
        Files.writeString(config, text.replace("  tiers: []",
                "  tiers:\n    - { every: 0, keep: 8 }\n    - { every: 24h, keep: 3 }"),
                StandardCharsets.UTF_8);

        BackupSettings settings = loadSettings(dataFolder, server);
        assertEquals(2, settings.tiers().size(), "켠 계단이 적용된다");

        // 다음 부팅에서 마이그레이터가 도로 꺼 놓으면 안 된다
        List<String> addedAgain = migrate(dataFolder);
        assertFalse(addedAgain.contains("retention.tiers"),
                "이미 있는 설정은 손대지 않는다");
        assertEquals(2, loadSettings(dataFolder, server).tiers().size(),
                "재부팅해도 관리자가 켠 대로 남아 있어야 한다");
        report("꺼진 채로 들어온 계단식을 관리자가 켜면 그대로 유지됨");
    }

    /**
     * {@code /wb lock} 으로 잠가 둔 백업은 업그레이드 정리에서도 살아남아야 한다.
     *
     * <p>잠금은 "어떤 정책으로도 지우지 않는다" 는 약속이다. 정책이 <b>바뀌는</b> 순간이야말로
     * 그 약속이 시험대에 오르는 때인데, 하필 그때 깨지면 영구 보관하려던 백업이 업그레이드
     * 한 번에 사라진다.</p>
     */
    @Test
    void aLockedBackupSurvivesTheUpgradeCleanup() throws Exception {
        Path server = tmp.resolve("server-locked");
        Path dataFolder = server.resolve("plugins/WorldBackUp");
        Path backups = dataFolder.resolve("backups");
        buildLegacyServer(server, false);

        // 가장 오래된 백업을 잠근다 - 계단식이라면 가장 먼저 지울 후보다
        BackupRepository probe = new BackupRepository(backups, LOG);
        List<BackupEntry> all = probe.list();
        String lockedId = all.get(all.size() - 1).id();
        Files.writeString(backups.resolve(BackupEntry.lockName(lockedId)), "", StandardCharsets.UTF_8);

        migrate(dataFolder);
        BackupRepository repo = new BackupRepository(backups, LOG);
        repo.prune(loadSettings(dataFolder, server));

        assertTrue(repo.list().stream().anyMatch(e -> e.id().equals(lockedId)),
                "잠근 백업이 업그레이드 정리에 지워지면 잠금은 약속이 아니다");
        report("잠근 백업 " + lockedId + " 은 업그레이드 정리 후에도 남음");
    }

    /**
     * 1.0.0 백업으로 복원하면 새 버전의 <b>op 목록 반영</b>이 걸리는가.
     *
     * <p>op 를 되돌리는 기능은 이번에 생겼다. 그런데 그 신호는 "복원이 {@code ops.json} 을
     * 실제로 되돌렸다" 는 것인데, 옛 백업에도 그 파일이 들어 있다. 여기가 끊기면 옛 백업으로
     * 복원했을 때만 op 가 예전처럼 그대로 남는다.</p>
     */
    @Test
    void restoringAnOldBackupStillTriggersTheOpListSync() throws Exception {
        Path server = tmp.resolve("server-ops");
        Path dataFolder = server.resolve("plugins/WorldBackUp");
        Path backups = dataFolder.resolve("backups");
        Files.createDirectories(backups);
        Files.writeString(dataFolder.resolve("config.yml"), legacyConfig(), StandardCharsets.UTF_8);

        write(server.resolve("ops.json"), "[]");
        write(server.resolve("banned-players.json"), "[{\"uuid\":\"x\",\"name\":\"griefer\"}]");
        write(server.resolve("world/level.dat"), "LEVEL");

        // ops.json 과 밴 목록이 함께 든 1.0.0 백업
        Instant at = Instant.now().minus(Duration.ofHours(1));
        String id = ID.format(at);
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("world/level.dat", "LEVEL@" + id);
        contents.put("ops.json", "[{\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"admin\"}]");
        contents.put("banned-players.json", "[]");
        writeLegacyBackupWith(backups, id, at, contents,
                List.of("world", "ops.json", "banned-players.json"));

        BackupRepository repo = new BackupRepository(backups, LOG);
        BackupEntry entry = repo.list().get(0);
        new PendingRestore(entry.id(), entry.archive(), null, "admin", System.currentTimeMillis(),
                false, 3, true, List.of("banned-players.json", "banned-ips.json"), entry.roots())
                .write(dataFolder);

        var restored = RestoreApplier.applyIfPending(dataFolder, server, LOG);

        assertTrue(restored.contains("ops.json"),
                "옛 백업으로 복원해도 op 목록 반영이 걸려야 한다");
        assertTrue(read(server.resolve("ops.json")).contains("admin"), "파일도 되돌아간다");
        assertEquals("[{\"uuid\":\"x\",\"name\":\"griefer\"}]",
                read(server.resolve("banned-players.json")),
                "밴은 옛 백업으로 복원해도 그대로 - 테러범은 밴인 채로 남는다");
        report("옛 백업 복원 -> op 반영 걸림, 밴은 유지됨");
    }

    /** 1.0.0 이 남긴 데이터 폴더에서 시작해도 아무 데서도 터지지 않는가. */
    @Test
    void nothingInTheOldDataFolderBreaksStartup() throws Exception {
        Path server = tmp.resolve("server-state");
        Path dataFolder = server.resolve("plugins/WorldBackUp");
        buildLegacyServer(server, false);

        // 1.0.0 이 남겼을 법한 것들
        Files.createDirectories(dataFolder.resolve("replaced/20260101-000000/world"));
        Files.writeString(dataFolder.resolve("last-restore.yml"),
                "backup-id: 20260101-000000\nsuccess: true\nrestored-files: 10\nfailed-files: 0\n",
                StandardCharsets.UTF_8);

        migrate(dataFolder);
        BackupSettings settings = loadSettings(dataFolder, server);

        // 시작 시 도는 정리 작업들
        BackupRepository repo = new BackupRepository(dataFolder.resolve("backups"), LOG);
        repo.cleanupOrphans();
        RestoreApplier.cleanupReplaced(dataFolder, settings.keepReplacedMax(), LOG);
        assertTrue(RestoreApplier.failureMarkers(dataFolder).isEmpty());
        assertEquals(BACKUP_COUNT, repo.list().size(), "정리 작업이 멀쩡한 백업을 건드리면 안 된다");

        // 예약이 걸린 채 업그레이드된 경우 - 옛 형식 예약 파일도 읽혀야 한다
        assertFalse(PendingRestore.exists(dataFolder));
        report("옛 데이터 폴더(replaced/, last-restore.yml)로 시작해도 백업 48개 그대로");
    }

    // ------------------------------------------------------------------
    // 1.0.0 서버 만들기

    private void buildLegacyServer(Path server, boolean differential) throws IOException {
        Path dataFolder = server.resolve("plugins/WorldBackUp");
        Path backups = dataFolder.resolve("backups");
        Files.createDirectories(backups);

        // 월드와 서버 파일
        write(server.resolve("world/level.dat"), "LEVEL");
        write(server.resolve("world/region/r.0.0.mca"), "REGION-NOW");
        write(server.resolve("world/playerdata/uuid.dat"), "INVENTORY-NOW");
        write(server.resolve("ops.json"), "[]");
        write(server.resolve("server.properties"), "motd=live");

        // 1.0.0 의 config.yml 을 그대로 둔다
        Files.writeString(dataFolder.resolve("config.yml"), legacyConfig(), StandardCharsets.UTF_8);

        // 30분 간격으로 48개. 최신이 지금.
        Instant now = Instant.now();
        String currentBase = null;
        for (int i = BACKUP_COUNT - 1; i >= 0; i--) {
            Instant at = now.minus(Duration.ofMinutes((long) i * INTERVAL_MINUTES));
            String id = ID.format(at);
            // full-every: 24 -> 차등 서버는 24개마다 전체 백업
            boolean full = !differential || (BACKUP_COUNT - 1 - i) % 24 == 0;
            String baseId = full ? null : currentBase;
            if (full) currentBase = id;
            writeLegacyBackup(backups, id, at, baseId);
        }
    }

    /** 내용과 대상 경로를 지정해 1.0.0 형식 백업을 만든다. */
    private void writeLegacyBackupWith(Path backups, String id, Instant at,
                                       Map<String, String> contents, List<String> roots) throws IOException {
        StringBuilder manifest = new StringBuilder();
        for (Map.Entry<String, String> file : contents.entrySet()) {
            manifest.append(file.getValue().getBytes(StandardCharsets.UTF_8).length).append(' ')
                    .append(at.toEpochMilli()).append(' ').append(file.getKey()).append('\n');
        }
        String meta = legacyMeta(id, at, null).replace("roots:\n- world\n",
                "roots:\n" + roots.stream().map(r -> "- " + r + "\n").reduce("", String::concat));

        Path archive = backups.resolve(BackupEntry.archiveName(id));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(Manifest.ENTRY));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(BackupEntry.META_ENTRY));
            zip.write(meta.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.writeString(backups.resolve(BackupEntry.metaName(id)), meta, StandardCharsets.UTF_8);
    }

    /** 1.0.0 형식의 백업 하나(zip + 사이드카). */
    private void writeLegacyBackup(Path backups, String id, Instant at, String baseId) throws IOException {
        Map<String, String> contents = new LinkedHashMap<>();
        if (baseId == null) {
            contents.put("world/level.dat", "LEVEL@" + id);
            contents.put("world/region/r.0.0.mca", "REGION@" + id);
            contents.put("world/playerdata/uuid.dat", "INVENTORY@" + id);
        } else {
            contents.put("world/region/r.0.0.mca", "REGION@" + id); // 바뀐 것만
        }
        List<String> listed = List.of(
                "world/level.dat", "world/region/r.0.0.mca", "world/playerdata/uuid.dat");

        Path archive = backups.resolve(BackupEntry.archiveName(id));
        StringBuilder manifest = new StringBuilder();
        for (String path : listed) {
            String body = contents.getOrDefault(path, "LEVEL@" + (baseId == null ? id : baseId));
            manifest.append(body.getBytes(StandardCharsets.UTF_8).length).append(' ')
                    .append(at.toEpochMilli()).append(' ').append(path).append('\n');
        }

        String meta = legacyMeta(id, at, baseId);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(Manifest.ENTRY));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(BackupEntry.META_ENTRY));
            zip.write(meta.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.writeString(backups.resolve(BackupEntry.metaName(id)), meta, StandardCharsets.UTF_8);
    }

    /** {@code player-data} 키가 없는 1.0.0 메타데이터. */
    private static String legacyMeta(String id, Instant at, String baseId) {
        return "id: " + id + '\n'
                + "created-at: " + at.toEpochMilli() + '\n'
                + "created-at-text: " + BackupEntry.DISPLAY_FORMAT.format(at) + '\n'
                + "type: SCHEDULED\n"
                + "label: null\n"
                + "original-bytes: 1048576\n"
                + "file-count: 3\n"
                + "roots:\n- world\n"
                + "worlds:\n- world\n"
                + "excludes:\n- '**/session.lock'\n"
                + "server-version: 1.0.0-sim\n"
                + "locked: false\n"
                + "base-id: " + (baseId == null ? "null" : baseId) + '\n';
    }

    private static String legacyConfig() throws IOException {
        // 저장소에 커밋된 1.0.0 의 config.yml 을 그대로 쓴다.
        return Files.readString(Path.of("src/test/resources/config-1.0.0.yml"), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 플러그인이 시작할 때 하는 일

    /** {@code WorldBackUpPlugin#migrateConfig} 와 같은 일을 한다. */
    private static List<String> migrate(Path dataFolder) throws IOException {
        Path file = dataFolder.resolve("config.yml");
        String shipped = Files.readString(Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8);
        String user = Files.readString(file, StandardCharsets.UTF_8);
        ConfigMigrator.Result result = ConfigMigrator.merge(shipped, user);
        if (result.changed()) {
            Files.writeString(file, result.text(), StandardCharsets.UTF_8);
        }
        return result.added();
    }

    private static BackupSettings loadSettings(Path dataFolder, Path server) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(
                dataFolder.resolve("config.yml").toFile());
        return BackupSettings.load(cfg, dataFolder, server);
    }

    private void restoreAndVerify(Path server, Path dataFolder,
                                  BackupEntry entry, BackupRepository repo) throws IOException {
        Path base = repo.base(entry).map(BackupEntry::archive).orElse(null);
        new PendingRestore(entry.id(), entry.archive(), base, "admin", System.currentTimeMillis(),
                false, 3, true, List.of(), entry.roots()).write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, server, LOG);

        assertTrue(RestoreApplier.failureMarkers(dataFolder).isEmpty(),
                "1.0.0 백업 복원이 실패 표식을 남기면 안 된다");
        assertEquals("REGION@" + entry.id(), read(server.resolve("world/region/r.0.0.mca")));
        assertTrue(Files.isRegularFile(server.resolve("world/playerdata/uuid.dat")),
                "인벤토리가 돌아와야 한다");
    }

    // ------------------------------------------------------------------

    private static void describe(BackupSettings settings, List<BackupEntry> entries) {
        int tierKeep = settings.tiers().stream().mapToInt(t -> t.keep()).sum();
        report("   백업 " + entries.size() + "개 · " + oldestAge(entries) + " 치"
                + " | 정책=" + (settings.tiers().isEmpty() ? "예전(max-backups/max-age-days/keep-daily)"
                : "계단식 " + settings.tiers().size() + "단 keep 합계 " + tierKeep)
                + " | mode=" + (settings.differential() ? "differential" : "full"));
    }

    private static String oldestAge(List<BackupEntry> entries) {
        if (entries.isEmpty()) return "0시간";
        Instant oldest = entries.get(entries.size() - 1).createdAt();
        long hours = Duration.between(oldest, Instant.now()).toHours();
        return hours >= 48 ? (hours / 24) + "일" : hours + "시간";
    }

    private Path copyOf(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path to = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(path, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return target;
    }

    private static void report(String line) {
        System.out.println("[SIM] " + line);
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
