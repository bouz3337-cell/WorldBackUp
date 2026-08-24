package io.github.yj.worldbackup;

import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.BackupService;
import io.github.yj.worldbackup.backup.BackupType;
import io.github.yj.worldbackup.backup.ServerBridge;
import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.restore.PendingRestore;
import io.github.yj.worldbackup.restore.RestoreApplier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BackupService#execute} 의 <b>실패·정지 경로</b> 검증.
 *
 * <p>여기서 확인하는 것들은 전부 "잘못되면 조용히 데이터를 잃는" 분기다. 정상 흐름은
 * {@link BackupRestoreRoundTripTest} 가 실제 압축·복원으로 덮으므로, 이 파일은 오직
 * <b>무언가 잘못됐을 때 무엇을 지우지 않는가</b>에 집중한다.</p>
 *
 * <p>{@link ServerBridge} 를 가짜로 갈아 끼워 서버 없이 돌린다. 압축·저장소·설정은 전부
 * 진짜를 쓴다 - 가짜로 바꾼 것은 서버를 실제로 건드리는 동작뿐이다.</p>
 */
class BackupServiceTest {

    @TempDir
    Path tmp;

    private Path serverRoot;
    private Path dataFolder;
    private Path backupDir;
    private FakeServer server;
    private FakeWorld world;

    @BeforeEach
    void setUp() throws IOException {
        serverRoot = tmp.resolve("server");
        dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        backupDir = dataFolder.resolve("backups");
        Files.createDirectories(backupDir);

        Path worldFolder = serverRoot.resolve("world");
        Files.createDirectories(worldFolder.resolve("region"));
        Files.createDirectories(worldFolder.resolve("playerdata"));
        Files.writeString(worldFolder.resolve("level.dat"), "level", StandardCharsets.UTF_8);
        Files.writeString(worldFolder.resolve("region/r.0.0.mca"), "지형", StandardCharsets.UTF_8);
        Files.writeString(worldFolder.resolve("playerdata/aaa.dat"), "인벤토리", StandardCharsets.UTF_8);

        world = new FakeWorld("world", worldFolder);
        server = new FakeServer();
        server.worlds.add(world);
        configure(cfg -> {
        });
    }

    // ------------------------------------------------------------------
    // 복원 실패 정지 중의 보관 정리

    /**
     * 정지 중에는 백업 뒤 보관 정리가 돌지 않는다.
     *
     * <p>그 정지가 존재하는 이유가 "반쯤 복원된 월드가 백업되면서 멀쩡한 예전 백업이 정책에
     * 밀려 사라지는 것" 이다. 관리자가 상황을 보려고 {@code /wb backup} 을 한 번 치는 것으로
     * 바로 그 일이 일어나면 정지를 걸어 둔 의미가 없다.</p>
     */
    @Test
    void restoreFailureHoldStopsPruningAfterAManualBackup() throws Exception {
        configure(cfg -> cfg.set("retention.max-age-days", 1));
        seed("old", daysAgo(30));

        server.hold = true;
        new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        assertTrue(archiveIds().contains("old"),
                "정지 중에는 보관 기간이 지난 백업도 지우지 않는다");
    }

    /** 정지가 풀리면 평소대로 정리한다. 위 테스트가 "정리가 원래 됐어야 하는" 상황임을 못 박는다. */
    @Test
    void pruningResumesOnceTheHoldIsReleased() throws Exception {
        configure(cfg -> cfg.set("retention.max-age-days", 1));
        seed("old", daysAgo(30));

        server.hold = false;
        new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        assertFalse(archiveIds().contains("old"), "정지가 없으면 보관 정책이 정상 동작한다");
    }

    /**
     * 정지 중에 디스크가 모자라면 옛 백업을 지워 공간을 만들지 않고 그 백업만 실패한다.
     *
     * <p>반쯤 복원된 월드를 되돌릴 재료가 그 백업들이다. 이번 백업을 포기하는 쪽이 훨씬 싸다.</p>
     */
    @Test
    void heldServerFailsTheBackupInsteadOfFreeingSpace() throws Exception {
        configure(cfg -> cfg.set("retention.min-free-disk-gb", 999_999L));
        seed("old1", daysAgo(9));
        seed("old2", daysAgo(8));
        seed("old3", daysAgo(7));

        server.hold = true;
        Exception error = assertThrows(Exception.class,
                () -> new BackupService(server).runBlocking(BackupType.MANUAL, null, null));

        assertTrue(String.valueOf(error.getMessage()).contains("복원 실패 기록"),
                "왜 공간을 안 만들었는지가 메시지에 있어야 한다: " + error.getMessage());
        assertEquals(List.of("old1", "old2", "old3"), archiveIds().stream().sorted().toList(),
                "정지 중에는 공간이 급해도 옛 백업을 지우지 않는다");
    }

    /** 대조군 - 정지가 아니면 같은 상황에서 공간을 만들려고 옛 백업을 지운다. */
    @Test
    void unheldServerDoesFreeSpaceInTheSameSituation() throws Exception {
        configure(cfg -> {
            cfg.set("retention.min-free-disk-gb", 999_999L);
            cfg.set("retention.min-backups", 0);
        });
        seed("old1", daysAgo(9));
        seed("old2", daysAgo(8));
        seed("old3", daysAgo(7));

        server.hold = false;
        assertThrows(Exception.class,
                () -> new BackupService(server).runBlocking(BackupType.MANUAL, null, null));

        assertTrue(archiveIds().size() < 3, "정지가 아니면 공간 확보를 시도한다");
    }

    // ------------------------------------------------------------------
    // 압축이 끝난 뒤의 실패

    /** 보관 정리가 터져도 백업 자체는 성공이다. 압축은 이미 끝났고 파일도 제자리에 있다. */
    @Test
    void aFailingPruneDoesNotFailTheBackup() throws Exception {
        server.repository = new BackupRepository(backupDir, server.log) {
            @Override
            public PruneResult prune(BackupSettings settings) {
                throw new IllegalStateException("정리 중 터졌다");
            }
        };

        BackupEntry entry = new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        assertTrue(Files.isRegularFile(entry.archive()), "백업 파일은 제자리에 있어야 한다");
    }

    /**
     * 뒷정리에서 무엇이 튀어나오든 <b>완성된 zip 은 지우지 않는다.</b>
     *
     * <p>{@code pruneAfterBackup} 은 {@code Exception} 만 잡으므로 {@code Error} 는 바깥
     * {@code catch (Throwable)} 까지 올라간다. 예전에는 거기서 archivePath 를 지웠다 -
     * 정상적으로 만들어진 백업을 뒷정리 실패 때문에 스스로 지운 셈이다.</p>
     */
    @Test
    void anErrorAfterArchivingNeverDeletesTheFinishedBackup() throws Exception {
        server.repository = new BackupRepository(backupDir, server.log) {
            @Override
            public PruneResult prune(BackupSettings settings) {
                throw new AssertionError("압축이 끝난 뒤에 터지는 무언가");
            }
        };

        assertThrows(Error.class,
                () -> new BackupService(server).runBlocking(BackupType.MANUAL, null, null));

        assertEquals(1, archiveIds().size(),
                "실패로 보고되더라도 완성된 백업 파일은 남아 있어야 한다");
    }

    // ------------------------------------------------------------------
    // 자동 저장 원복

    /** 백업 중에는 자동 저장이 꺼져 있고, 끝나면 원래 값으로 돌아온다. */
    @Test
    void oneBackDoesNotStealTheChangedFlagFromTheOrdinaryBackup() throws Exception {
        configure(cfg -> {
            cfg.set("backup.skip-if-no-players", true);
            cfg.set("backup.max-skipped-cycles", 0); // 하한 때문에 통과하는 것을 막는다
            cfg.set("oneback.directory", tmp.resolve("OneBack").toString());
        });
        BackupService service = new BackupService(server);

        // 접속자가 무언가 바꾸고 나갔다. 이 변경은 아직 어떤 백업에도 담기지 않았다.
        server.online = false;
        service.markWorldChanged();

        service.startOneBackAsync("tester").join();

        assertFalse(service.shouldSkipScheduled(),
                "OneBack 은 /wb restore 목록에 오르지 않는다. 그것을 이유로 자동 백업을 건너뛰면 "
                        + "그 사이의 변경은 되돌릴 방법이 없는 곳에만 남는다");
    }

    /** 반대로 평소 백업은 <b>복원 지점을 만들었으므로</b> 플래그를 내린다. 이쪽 동작은 그대로다. */
    @Test
    void anOrdinaryBackupStillClearsTheChangedFlag() throws Exception {
        configure(cfg -> {
            cfg.set("backup.skip-if-no-players", true);
            cfg.set("backup.max-skipped-cycles", 0);
        });
        BackupService service = new BackupService(server);
        server.online = false;
        service.markWorldChanged();

        service.runBlocking(BackupType.MANUAL, null, null);

        assertTrue(service.shouldSkipScheduled(),
                "복원 지점이 방금 만들어졌으니 다음 주기는 건너뛰어도 된다");
    }

    @Test
    void autoSaveIsOffWhileArchivingAndRestoredAfterwards() throws Exception {
        world.autoSave = true;

        new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        assertFalse(world.autoSaveWhileSaving, "저장을 기다리는 동안에는 자동 저장이 꺼져 있어야 한다");
        assertTrue(world.autoSave, "끝나면 원래 값으로 돌아와야 한다");
    }

    /**
     * 월드 저장이 실패해도 자동 저장은 반드시 원복된다.
     *
     * <p>자동 저장이 꺼진 채 방치되면 서버가 크래시했을 때 그 세션 전체가 날아간다.
     * 원래 값을 반환값이 아니라 별도 맵에 즉시 적어 두는 이유가 이것이다.</p>
     */
    @Test
    void autoSaveIsRestoredEvenWhenTheWorldFailsToSave() throws Exception {
        world.autoSave = true;
        world.saveFailure = new RuntimeException("디스크가 응답하지 않는다");

        new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        assertTrue(world.autoSave, "월드 저장이 실패해도 자동 저장은 되돌린다");
    }

    /** 백업이 통째로 실패해도 마찬가지다. */
    @Test
    void autoSaveIsRestoredEvenWhenTheWholeBackupFails() {
        world.autoSave = true;
        configure(cfg -> cfg.set("retention.min-free-disk-gb", 999_999L));
        server.hold = true; // 공간 확보 없이 즉시 실패한다

        assertThrows(Exception.class,
                () -> new BackupService(server).runBlocking(BackupType.MANUAL, null, null));

        assertTrue(world.autoSave, "실패 경로에서도 자동 저장은 되돌린다");
    }

    // ------------------------------------------------------------------
    // 용량 측정 순회를 건너뛰는 경로

    /**
     * 공간이 넉넉하면 차등 백업은 용량 측정 순회를 건너뛴다. 그래도 <b>담기는 내용은 같아야 한다.</b>
     *
     * <p>그 순회는 디스크 판정과 진행률 분모를 위해서만 있는데, 트리를 통째로 한 번 더 돈다.
     * 건너뛰는 것이 결과에 영향을 주면 안 된다.</p>
     */
    @Test
    void differentialSkipsTheMeasureWalkWhenSpaceIsAmpleButStoresTheSameFiles() throws Exception {
        configure(cfg -> {
            cfg.set("backup.mode", "differential");
            cfg.set("retention.min-free-disk-gb", 0); // 공간은 넉넉하다
        });

        BackupEntry full = new BackupService(server).runBlocking(BackupType.MANUAL, "전체", null);
        assertFalse(full.isDifferential(), "첫 백업은 전체여야 한다");
        assertTrue(full.originalBytes() > 0, "건너뛸 판단의 근거가 되는 값이다");

        Thread.sleep(1100); // 수정 시각이 초 단위로 갈리도록
        Files.writeString(serverRoot.resolve("world/region/r.0.0.mca"), "바뀐 지형", StandardCharsets.UTF_8);

        BackupEntry diff = new BackupService(server).runBlocking(BackupType.MANUAL, "차등", null);

        assertTrue(diff.isDifferential(), "기준을 둔 차등 백업이어야 한다");
        assertEquals(full.id(), diff.baseId());
        assertEquals(full.fileCount(), diff.fileCount(), "스냅샷 파일 수는 그대로다");

        // 바뀐 파일만 담기고, 나머지는 기준에서 재사용된다.
        assertEquals(List.of("world/region/r.0.0.mca"), storedNames(diff),
                "측정을 건너뛰어도 담는 파일 판정은 그대로여야 한다");
    }

    /**
     * 공간이 모자라면 건너뛰지 않고 예전처럼 정확히 재고 거부한다.
     *
     * <p>건너뛰기 판단이 잘못 참을 내면 디스크가 꽉 찬 서버에서 백업이 공간 확보도 없이
     * 진행되다 실패한다. 차등 백업에서도 그 브레이크가 살아 있는지 확인한다.</p>
     */
    @Test
    void differentialStillEnforcesTheDiskCheckWhenSpaceIsTight() throws Exception {
        configure(cfg -> cfg.set("backup.mode", "differential"));
        BackupEntry full = new BackupService(server).runBlocking(BackupType.MANUAL, "전체", null);
        assertFalse(full.isDifferential());

        // 이제 여유 공간 요구를 터무니없이 올린다. 건너뛸 수 없어야 한다.
        configure(cfg -> {
            cfg.set("backup.mode", "differential");
            cfg.set("retention.min-free-disk-gb", 999_999L);
            cfg.set("retention.min-backups", 0);
        });
        server.hold = true; // 공간을 만들지 않고 즉시 실패하는 경로

        Exception error = assertThrows(Exception.class,
                () -> new BackupService(server).runBlocking(BackupType.MANUAL, null, null));
        assertTrue(String.valueOf(error.getMessage()).contains("디스크 여유 공간이 부족"),
                "디스크 판정이 살아 있어야 한다: " + error.getMessage());
        assertTrue(archiveIds().contains(full.id()), "정지 중에는 옛 백업을 지우지 않는다");
    }

    /** 기준 백업에 스냅샷 크기 기록이 없으면(옛 백업) 건너뛰지 않고 재야 한다. */
    @Test
    void aBaseWithoutASnapshotSizeFallsBackToMeasuring() throws Exception {
        configure(cfg -> cfg.set("backup.mode", "differential"));
        BackupEntry full = new BackupService(server).runBlocking(BackupType.MANUAL, "전체", null);

        // original-bytes 를 0 으로 지워 옛 백업을 흉내 낸다.
        server.repository.writeMeta(new BackupEntry(full.id(), full.archive(), full.createdAt(),
                full.type(), full.label(), full.archiveBytes(), 0L, full.fileCount(),
                full.roots(), full.worlds(), full.excludes(), full.serverVersion(),
                full.locked(), true, full.baseId(), full.playerData()));

        Thread.sleep(1100);
        Files.writeString(serverRoot.resolve("world/level.dat"), "level-2", StandardCharsets.UTF_8);

        BackupEntry diff = new BackupService(server).runBlocking(BackupType.MANUAL, null, null);
        assertTrue(diff.isDifferential(), "재는 경로로 돌아가도 차등 백업은 정상 동작한다");
        assertEquals(List.of("world/level.dat"), storedNames(diff));
    }

    /**
     * 측정을 건너뛸지 정하는 규칙 자체.
     *
     * <p>이 판단이 <b>잘못 참을 내면</b> 디스크가 빠듯한 서버에서 공간 확보도 없이 백업을
     * 시작해 실패한다. 그래서 경계에서 확실히 보수적인지 못 박는다. 반대로 거짓을 내는 것은
     * 조금 느려질 뿐이라 안전한 방향이다.</p>
     */
    @Test
    void theSkipDecisionIsConservativeAtTheBoundary() {
        long gb = 1024L * 1024 * 1024;

        // 10GB 스냅샷 · 무압축 가정 → 최악 15GB. 여유 20GB 면 건너뛴다.
        assertTrue(BackupService.hasAmpleRoom(10 * gb, 1.0, 0L, 20 * gb));
        // 14GB 밖에 없으면 1.5배 여유를 못 채운다 → 재야 한다.
        assertFalse(BackupService.hasAmpleRoom(10 * gb, 1.0, 0L, 14 * gb));
        // 스냅샷만큼(10GB)만 남아도 건너뛰지 않는다 - 자란 만큼을 감당하지 못한다.
        assertFalse(BackupService.hasAmpleRoom(10 * gb, 1.0, 0L, 10 * gb));

        // min-free-disk-gb 는 최악의 경우 <b>위에</b> 더해서 지켜야 한다.
        assertTrue(BackupService.hasAmpleRoom(10 * gb, 1.0, 5 * gb, 20 * gb));
        assertFalse(BackupService.hasAmpleRoom(10 * gb, 1.0, 5 * gb, 19 * gb));

        // 압축을 감안하면 요구가 줄어든다.
        assertTrue(BackupService.hasAmpleRoom(10 * gb, 0.6, 0L, 10 * gb));

        // 기록이 없는 옛 백업은 절대 건너뛰지 않는다.
        assertFalse(BackupService.hasAmpleRoom(0L, 1.0, 0L, Long.MAX_VALUE));
        assertFalse(BackupService.hasAmpleRoom(-1L, 1.0, 0L, Long.MAX_VALUE));

        // 설정이 터무니없이 커서 넘치면 재는 쪽으로 되돌아간다.
        assertFalse(BackupService.hasAmpleRoom(10 * gb, 1.0, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    /** 이 아카이브가 <b>직접</b> 담고 있는 데이터 파일 이름들. */
    private List<String> storedNames(BackupEntry entry) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(entry.archive().toFile(),
                StandardCharsets.UTF_8)) {
            return zip.stream()
                    .map(java.util.zip.ZipEntry::getName)
                    .filter(name -> !name.endsWith("/"))
                    .filter(name -> !name.equals("worldbackup-meta.yml"))
                    .filter(name -> !name.equals("worldbackup-files.txt"))
                    .sorted()
                    .toList();
        }
    }

    // ------------------------------------------------------------------
    // 무인 서버에서 백업이 무기한 비지 않는가

    /**
     * 변경이 없어 보여도 연속 생략에는 하한이 있다.
     *
     * <p>"변경 없음" 은 블록 설치·파괴·접속만 보고 내리는 판단이다. 강제 로드된 청크에서 도는
     * 농장이나 플러그인이 직접 쓰는 데이터는 잡히지 않으므로, 하한이 없으면 무인 기간의 백업이
     * 통째로 비어 버린다. 하필 그 사이에 서버가 죽으면 되돌릴 지점이 없다.</p>
     */
    @Test
    void skippingHasAFloorSoQuietServersStillGetBackedUp() throws Exception {
        configure(cfg -> {
            cfg.set("backup.skip-if-no-players", true);
            cfg.set("backup.max-skipped-cycles", 3);
        });
        BackupService service = new BackupService(server);

        // 접속자 없이 백업을 한 번 돌리면 그 시점이 스냅샷 경계가 되어 "변경 없음" 이 된다.
        server.online = false;
        service.runBlocking(BackupType.SCHEDULED, null, null);

        assertTrue(service.shouldSkipScheduled(), "1번째 주기는 건너뛴다");
        assertTrue(service.shouldSkipScheduled(), "2번째 주기도 건너뛴다");
        assertFalse(service.shouldSkipScheduled(), "3번째는 변경이 없어 보여도 백업해야 한다");
        assertTrue(service.shouldSkipScheduled(), "그 뒤로는 다시 세기 시작한다");
    }

    /** 백업이 실제로 만들어지면 종류와 무관하게 카운터가 풀린다. */
    @Test
    void anyRealBackupResetsTheSkipCounter() throws Exception {
        configure(cfg -> {
            cfg.set("backup.skip-if-no-players", true);
            cfg.set("backup.max-skipped-cycles", 3);
        });
        BackupService service = new BackupService(server);

        server.online = false;
        service.runBlocking(BackupType.SCHEDULED, null, null);
        assertTrue(service.shouldSkipScheduled());
        assertTrue(service.shouldSkipScheduled());

        // 관리자가 직접 백업했다. 이 시점의 스냅샷이 남았으니 강제로 하나 더 뜰 이유가 없다.
        service.runBlocking(BackupType.MANUAL, null, null);

        assertTrue(service.shouldSkipScheduled(), "다시 처음부터 센다");
        assertTrue(service.shouldSkipScheduled());
        assertFalse(service.shouldSkipScheduled());
    }

    /**
     * 백업이 <b>실패하면</b> 하한을 기다리지 않고 다음 주기에 곧바로 다시 시도한다.
     *
     * <p>하한 카운터는 이름 그대로 "건너뛴 횟수" 만 센다. 실패한 백업은 건너뛴 것이 아니므로
     * 카운터를 건드리지 않는데, 그것만으로는 부족하다 - 실패해도 아무것도 남지 않은 건 마찬가지라
     * 다음 주기가 "변경 없음" 으로 또 건너뛰면 공백이 그대로 이어진다. 이 재시도는
     * {@link BackupService#execute} 의 {@code !archived} 분기가 변경 플래그를 되돌려 주기
     * 때문에 성립한다.</p>
     *
     * <p>두 장치는 서로 다른 이유로 각각 존재하고, 이 동작은 <b>둘이 맞물려야만</b> 나온다.
     * 한쪽을 고치면서 다른 쪽을 잊으면 백업이 계속 깨지는 무인 서버가 하한을 채울 때까지
     * (기본값으로 하루) 아무 시도도 하지 않게 된다. 그래서 못 박아 둔다.</p>
     */
    @Test
    void aFailedBackupIsRetriedNextCycleInsteadOfWaitingOutTheFloor() throws Exception {
        Consumer<YamlConfiguration> quietServer = cfg -> {
            cfg.set("backup.skip-if-no-players", true);
            cfg.set("backup.max-skipped-cycles", 3);
        };
        configure(quietServer);
        BackupService service = new BackupService(server);

        server.online = false;
        service.runBlocking(BackupType.SCHEDULED, null, null);
        assertTrue(service.shouldSkipScheduled(), "성공한 백업 뒤에는 건너뛴다");

        // 이번 주기는 압축을 시작하기도 전에 실패한다. 남는 백업이 없다.
        configure(quietServer.andThen(cfg -> cfg.set("retention.min-free-disk-gb", 999_999L)));
        server.hold = true;
        assertThrows(Exception.class,
                () -> service.runBlocking(BackupType.SCHEDULED, null, null));

        // 디스크 문제가 풀렸다. 이제 다음 주기의 판단만 본다.
        server.hold = false;
        configure(quietServer);

        assertFalse(service.shouldSkipScheduled(),
                "실패한 백업은 아무것도 남기지 못했으므로 하한을 기다리면 안 된다");
    }

    /** 0 은 하한 없음이다. 예전 동작 그대로 무한히 건너뛴다. */
    @Test
    void aZeroFloorKeepsSkippingForever() throws Exception {
        configure(cfg -> {
            cfg.set("backup.skip-if-no-players", true);
            cfg.set("backup.max-skipped-cycles", 0);
        });
        BackupService service = new BackupService(server);

        server.online = false;
        service.runBlocking(BackupType.SCHEDULED, null, null);

        for (int i = 0; i < 50; i++) {
            assertTrue(service.shouldSkipScheduled(), i + "번째 주기");
        }
    }

    // ------------------------------------------------------------------
    // 플러그인 자기 폴더

    /**
     * 자기 설정은 담고, 자기 상태는 담지 않는다.
     *
     * <p>{@code config.yml} 이 빠지면 서버를 되돌려도 <b>그 시점의 보관 정책은 되돌아오지
     * 않는다.</b> 반대로 상태 파일이 담기면 복원이 그것을 되살리는데, 그 결과가 파일마다 다르게
     * 나쁘다 - 복원 예약이 되살아나면 다음 부팅이 또 복원하고, 실패 표식이 되살아나면 아무도
     * 손대지 않은 서버가 영구히 정지 상태로 들어간다.</p>
     *
     * <p>{@code extra-paths} 에 {@code plugins} 를 통째로 넣은 서버가 가장 위험하므로 그 설정으로
     * 확인한다. 그때는 우리 폴더가 <b>다른 대상 안에서</b> 훑어지므로, 대상 목록을 고르는
     * 단계에서 빼는 것만으로는 막히지 않는다. 실제 zip 을 열어 본다.</p>
     */
    @Test
    void theOwnConfigTravelsWithTheBackupButItsStateDoesNot() throws Exception {
        Files.writeString(dataFolder.resolve("config.yml"), "backup:\n  interval-minutes: 30\n",
                StandardCharsets.UTF_8);

        // 플러그인이 스스로 만들어 쓰는 것들. 하나도 담기면 안 된다.
        Files.writeString(dataFolder.resolve(PendingRestore.FILE_NAME), "id: x", StandardCharsets.UTF_8);
        Files.writeString(dataFolder.resolve(PendingRestore.PROCESSING_NAME), "id: x", StandardCharsets.UTF_8);
        Files.writeString(dataFolder.resolve(PendingRestore.REPORT_NAME), "success: true", StandardCharsets.UTF_8);
        Files.writeString(dataFolder.resolve(RestoreApplier.FAILURE_PREFIX + "20260818-030000.yml"),
                "error: x", StandardCharsets.UTF_8);
        Files.writeString(backupDir.resolve("wb-20260101-000000.zip"), "옛 아카이브", StandardCharsets.UTF_8);
        Path oldWorld = dataFolder.resolve(RestoreApplier.REPLACED_FOLDER + "/20260818-030000/world");
        Files.createDirectories(oldWorld);
        Files.writeString(oldWorld.resolve("level.dat"), "복원이 밀어낸 옛 월드", StandardCharsets.UTF_8);

        configure(cfg -> cfg.set("targets.extra-paths", List.of("plugins")));
        List<String> names = entryNames(new BackupService(server).runBlocking(BackupType.MANUAL, null, null));

        assertTrue(names.contains("plugins/WorldBackUp/config.yml"),
                "이것이 빠지면 복원이 보관 정책을 되돌리지 못한다");
        for (String forbidden : List.of(
                "plugins/WorldBackUp/" + PendingRestore.FILE_NAME,
                "plugins/WorldBackUp/" + PendingRestore.PROCESSING_NAME,
                "plugins/WorldBackUp/" + PendingRestore.REPORT_NAME,
                "plugins/WorldBackUp/" + RestoreApplier.FAILURE_PREFIX + "20260818-030000.yml",
                "plugins/WorldBackUp/backups/wb-20260101-000000.zip",
                "plugins/WorldBackUp/replaced/20260818-030000/world/level.dat")) {
            assertFalse(names.contains(forbidden), forbidden + " 이 담기면 복원이 그것을 되살린다");
        }
    }

    /**
     * 아무도 우리 폴더를 담지 않으면 설정 파일 하나를 대상으로 올린다.
     *
     * <p>{@code targets.plugins} 를 꺼야 그 상황이 된다. 기본값처럼 켜져 있으면 플러그인
     * 폴더가 이미 우리 폴더를 품고 있고, 그때는 아래
     * {@link #theOwnConfigRidesAlongWithThePluginsFolder()} 쪽이 맞는 동작이다.</p>
     */
    @Test
    void theOwnConfigBecomesItsOwnTargetWhenNothingElseCoversIt() throws Exception {
        Files.writeString(dataFolder.resolve("config.yml"), "backup: {}", StandardCharsets.UTF_8);
        configure(cfg -> cfg.set("targets.plugins", "none"));

        BackupEntry entry = new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        assertTrue(entry.roots().contains("plugins/WorldBackUp/config.yml"),
                "복원이 이 파일을 되돌릴 수 있도록 대상 경로에도 올라야 한다");
        assertTrue(entryNames(entry).contains("plugins/WorldBackUp/config.yml"));
    }

    /**
     * 기본값에서는 플러그인 폴더가 통째로 담기고, 우리 설정 파일도 그 안에 함께 실린다.
     *
     * <p>따로 대상으로 올리지 <b>않는</b> 것이 중요하다. 겹쳐 올리면 {@code dedupeTargets} 가
     * 걸러 내면서 경고를 한 줄 남기는데, 관리자가 하지도 않은 설정을 지적하는 것처럼 보인다.</p>
     */
    @Test
    void theOwnConfigRidesAlongWithThePluginsFolder() throws Exception {
        Files.writeString(dataFolder.resolve("config.yml"), "backup: {}", StandardCharsets.UTF_8);
        Files.writeString(serverRoot.resolve("plugins/Economy.jar"), "jar", StandardCharsets.UTF_8);
        Files.createDirectories(serverRoot.resolve("plugins/Economy"));
        Files.writeString(serverRoot.resolve("plugins/Economy/balances.yml"), "잔고", StandardCharsets.UTF_8);

        BackupEntry entry = new BackupService(server).runBlocking(BackupType.MANUAL, null, null);
        List<String> names = entryNames(entry);

        assertTrue(entry.roots().contains("plugins"));
        assertFalse(entry.roots().contains("plugins/WorldBackUp/config.yml"),
                "플러그인 폴더가 이미 품고 있으므로 따로 올리지 않는다");
        assertTrue(names.contains("plugins/WorldBackUp/config.yml"));
        assertTrue(names.contains("plugins/Economy/balances.yml"),
                "월드만 되돌리면 플러그인이 든 상태가 월드와 어긋난다");
        assertTrue(names.contains("plugins/Economy.jar"), "기본값(all)은 jar 까지 담는다");
    }

    /**
     * {@code plugins: data} 는 설정과 데이터만 담고 jar 는 뺀다.
     *
     * <p>{@code plugins/} <b>바로 아래</b>만 뺀다. 플러그인이 자기 폴더 안에 두는 라이브러리
     * jar 는 그 플러그인의 데이터라서 함께 담아야 한다.</p>
     */
    @Test
    void pluginDataCanBeBackedUpWithoutTheJars() throws Exception {
        Files.writeString(serverRoot.resolve("plugins/Economy.jar"), "jar", StandardCharsets.UTF_8);
        Files.createDirectories(serverRoot.resolve("plugins/Economy/libs"));
        Files.writeString(serverRoot.resolve("plugins/Economy/balances.yml"), "잔고", StandardCharsets.UTF_8);
        Files.writeString(serverRoot.resolve("plugins/Economy/libs/driver.jar"), "라이브러리",
                StandardCharsets.UTF_8);
        configure(cfg -> cfg.set("targets.plugins", "data"));

        List<String> names = entryNames(
                new BackupService(server).runBlocking(BackupType.MANUAL, null, null));

        assertTrue(names.contains("plugins/Economy/balances.yml"));
        assertFalse(names.contains("plugins/Economy.jar"));
        assertTrue(names.contains("plugins/Economy/libs/driver.jar"));
    }

    /**
     * {@code plugins/} 아래를 가리키는 {@code extra-paths} 는 <b>조용히</b> 넘어간다.
     *
     * <p>targets.plugins 가 생기기 전에는 "plugins/LuckPerms" 를 거기 적는 것이 권장
     * 설정이었다. 그대로 두면 dedupeTargets 가 걸러 내면서 백업할 때마다 경고를 한 줄
     * 남긴다 - 30분 주기면 하루 48줄이고, 관리자가 하지도 않은 잘못을 지적하는 것처럼
     * 보인다. 담기는 내용은 어느 쪽이든 같다.</p>
     */
    @Test
    void anExtraPathInsideThePluginsFolderIsAbsorbedQuietly() throws Exception {
        Files.createDirectories(serverRoot.resolve("plugins/Economy"));
        Files.writeString(serverRoot.resolve("plugins/Economy/balances.yml"), "잔고",
                StandardCharsets.UTF_8);
        configure(cfg -> cfg.set("targets.extra-paths", List.of("plugins/Economy")));

        BackupEntry entry = new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        assertTrue(entryNames(entry).contains("plugins/Economy/balances.yml"),
                "조용히 넘어가되 내용은 그대로 담겨야 한다");
        assertFalse(entry.roots().contains("plugins/Economy"),
                "플러그인 폴더 하나로 합쳐진다");
        assertTrue(server.warnings.stream().noneMatch(w -> w.contains("plugins/Economy")),
                "이 겹침은 알릴 것이 아니다: " + server.warnings);
    }

    /**
     * 청크 flush 는 월드마다 <b>따로</b> 메인 스레드에 올린다.
     *
     * <p>워치독은 틱 하나의 길이를 본다. 월드 셋을 한 틱에 몰아 저장하면 그 합이 한 틱이 되어,
     * 월드마다 4초씩만 걸려도 12초짜리 틱이 되고 스레드 덤프가 찍힌다. 그 덤프에는 이 플러그인의
     * 스택이 남으므로 관리자는 이쪽을 의심하게 되고, 합이 치명 임계에 닿으면 서버가 죽는다.</p>
     */
    @Test
    void eachWorldIsFlushedInItsOwnTick() throws Exception {
        FakeWorld nether = new FakeWorld("world_nether", serverRoot.resolve("world_nether"));
        FakeWorld end = new FakeWorld("world_the_end", serverRoot.resolve("world_the_end"));
        for (FakeWorld extra : List.of(nether, end)) {
            Files.createDirectories(extra.folder.resolve("region"));
            Files.writeString(extra.folder.resolve("level.dat"), "level", StandardCharsets.UTF_8);
            server.worlds.add(extra);
        }
        server.syncTasks = 0;

        new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        // 얼리기 1 + 월드 3 + 원복 1 = 5. (예열은 이 검사에서 꺼져 있다)
        // 몰아서 저장하면 2가 된다.
        assertEquals(5, server.syncTasks,
                "월드마다 틱을 나누지 않으면 워치독이 보는 틱 하나가 그 합만큼 길어진다");
        for (FakeWorld world : server.worlds) {
            assertFalse(world.autoSaveWhileSaving,
                    world.name + ": 자동 저장을 끈 뒤에 저장해야 한다");
        }
    }

    /**
     * 기다리는 저장 <b>전에</b> 기다리지 않는 저장을 먼저 건다.
     *
     * <p>백업이 서버를 멈추는 시간의 대부분은 청크를 만드는 비용이 아니라 큐가 빠지기를
     * 기다리는 것이다. 순서가 뒤집히거나 예열이 빠지면 그 대기가 그대로 돌아온다.</p>
     */
    @Test
    void writesArePreWarmedBeforeTheBlockingSave() throws Exception {
        configure(cfg -> cfg.set("backup.flush-settle-seconds", 1));

        new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        assertEquals(1, world.queuedSaves, "월드마다 한 번 예열해야 한다");
        assertEquals(1, world.queuedBeforeSave,
                "예열이 진짜 저장보다 먼저 걸려야 큐가 빠질 시간이 생긴다");
    }

    /** {@code flush-settle-seconds: 0} 이면 예열하지 않는다. 예전 동작 그대로. */
    @Test
    void preWarmingCanBeTurnedOff() throws Exception {
        configure(cfg -> cfg.set("backup.flush-settle-seconds", 0));

        new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        assertEquals(0, world.queuedSaves);
    }

    /** {@code plugins: none} 이면 플러그인 폴더는 담지 않는다. */
    @Test
    void thePluginsFolderCanBeLeftOut() throws Exception {
        Files.createDirectories(serverRoot.resolve("plugins/Economy"));
        Files.writeString(serverRoot.resolve("plugins/Economy/balances.yml"), "잔고",
                StandardCharsets.UTF_8);
        configure(cfg -> cfg.set("targets.plugins", "none"));

        List<String> names = entryNames(
                new BackupService(server).runBlocking(BackupType.MANUAL, null, null));

        assertFalse(names.contains("plugins/Economy/balances.yml"));
    }

    /**
     * 관리자가 제외 패턴으로 뺐으면 <b>대상 경로에도 올리지 않는다.</b>
     *
     * <p>담기지도 않을 경로를 {@code roots} 에 올리면, 복원이 "이 경로에는 데이터가 없다" 는
     * 경고를 매번 찍는다. 관리자가 직접 뺀 것인데 무언가 잘못된 것처럼 보인다.</p>
     */
    @Test
    void anExcludedOwnConfigIsNotListedAsARestoreTarget() throws Exception {
        Files.writeString(dataFolder.resolve("config.yml"), "backup: {}", StandardCharsets.UTF_8);
        configure(cfg -> cfg.set("targets.exclude", List.of("plugins/WorldBackUp/config.yml")));

        BackupEntry entry = new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        assertFalse(entry.roots().contains("plugins/WorldBackUp/config.yml"));
        assertFalse(entryNames(entry).contains("plugins/WorldBackUp/config.yml"));
    }

    // ------------------------------------------------------------------
    // 도우미

    /** 만들어진 아카이브에 실제로 들어간 엔트리 이름들. */
    private List<String> entryNames(BackupEntry entry) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(entry.archive().toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) names.add(it.nextElement().getName());
        }
        return names;
    }

    private void configure(Consumer<YamlConfiguration> tweak) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("backup.broadcast", false);
        cfg.set("backup.compression-level", 0); // 테스트를 빠르게
        // 예열 대기는 진짜로 잠든다. 그것을 보는 검사만 따로 켠다.
        cfg.set("backup.flush-settle-seconds", 0);
        cfg.set("targets.worlds", List.of("*"));
        cfg.set("targets.server-files", List.of());
        cfg.set("retention.max-backups", 0);
        cfg.set("retention.min-backups", 0);
        cfg.set("retention.max-age-days", 0);
        cfg.set("retention.keep-daily", 0);
        cfg.set("retention.protect-manual", false);
        cfg.set("retention.max-protected", 0);
        cfg.set("retention.min-free-disk-gb", 0);
        tweak.accept(cfg);
        server.settings = BackupSettings.load(cfg, dataFolder, serverRoot);
    }

    /** 가짜 백업 하나를 심는다. 사이드카가 있으면 저장소는 zip 을 열지 않는다. */
    private void seed(String id, Instant createdAt) throws IOException {
        Path archive = backupDir.resolve(BackupEntry.archiveName(id));
        Files.writeString(archive, "payload-" + id, StandardCharsets.UTF_8);
        server.repository.writeMeta(new BackupEntry(id, archive, createdAt, BackupType.SCHEDULED, null,
                Files.size(archive), 0L, 0, List.of("world"), List.of("world"), List.of(),
                "test", false, true, null, true));
    }

    /** 백업 폴더에 실제로 남아 있는 zip 의 id. 저장소 캐시를 거치지 않고 디스크를 직접 본다. */
    private List<String> archiveIds() throws IOException {
        try (Stream<Path> files = Files.list(backupDir)) {
            return files.map(path -> BackupEntry.idFromArchive(path))
                    .filter(id -> id != null)
                    .toList();
        }
    }

    private static Instant daysAgo(int days) {
        return LocalDate.now(ZoneId.systemDefault())
                .minusDays(days).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();
    }

    // ------------------------------------------------------------------

    /** 서버를 건드리는 동작만 흉내 낸다. 판단은 하나도 들어 있지 않다. */
    private final class FakeServer implements ServerBridge {

        /**
         * 경고를 모아 둔다.
         *
         * <p>"무엇을 담았는가" 만큼 "무엇을 말했는가" 도 동작이다. 백업마다 되풀이되는 경고는
         * 관리자가 실제로 보는 것이고, 하지도 않은 잘못을 30분마다 지적하면 진짜 경고까지
         * 함께 흘려보내게 된다.</p>
         */
        private final List<String> warnings = new ArrayList<>();

        private final Logger log = new Logger("BackupServiceTest", null) {
            @Override
            public void warning(String message) {
                warnings.add(message);
            }
        };
        private final List<FakeWorld> worlds = new ArrayList<>();

        private BackupSettings settings;
        private BackupRepository repository = new BackupRepository(backupDir, log);
        private boolean hold;
        private boolean online;

        /**
         * 메인 스레드에 올린 작업 수.
         *
         * <p>워치독은 <b>틱 하나</b>가 얼마나 걸렸는지를 본다. 그래서 청크 flush 를 몇 개의
         * 작업으로 나눠 올렸는지가 곧 동작이다 - 한 작업에 몰면 그 합이 한 틱이 된다.</p>
         */
        private int syncTasks;

        @Override
        public Logger logger() {
            return log;
        }

        @Override
        public BackupSettings settings() {
            return settings;
        }

        @Override
        public BackupRepository repository() {
            return repository;
        }

        @Override
        public boolean restoreFailureHold() {
            return hold;
        }

        @Override
        public boolean hasOnlinePlayers() {
            return online;
        }

        @Override
        public String serverVersion() {
            return "test-server";
        }

        @Override
        public void savePlayers() {
        }

        @Override
        public List<WorldHandle> worlds() {
            return List.copyOf(worlds);
        }

        @Override
        public Optional<WorldHandle> world(String name) {
            return worlds.stream().filter(w -> w.name().equals(name)).map(w -> (WorldHandle) w).findFirst();
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public void runSyncQuietly(Runnable task) {
            // 실서버 구현과 같은 계약: 종료 중이면 조용히 포기한다.
            try {
                task.run();
            } catch (Exception ignored) {
            }
        }

        @Override
        public <T> T callSync(Callable<T> callable, long timeoutSeconds) throws Exception {
            syncTasks++;
            return callable.call();
        }

        @Override
        public void broadcast(String message, String permission) {
        }
    }

    /** 로드된 월드 하나. */
    private static final class FakeWorld implements ServerBridge.WorldHandle {

        private final String name;
        private final Path folder;

        private boolean autoSave = true;
        /** {@link #saveNow()} 가 불린 시점의 자동 저장 상태. 순서가 맞는지 보려고 남긴다. */
        private boolean autoSaveWhileSaving = true;
        private RuntimeException saveFailure;

        FakeWorld(String name, Path folder) {
            this.name = name;
            this.folder = folder.toAbsolutePath().normalize();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Path folder() {
            return folder;
        }

        @Override
        public boolean autoSave() {
            return autoSave;
        }

        @Override
        public void autoSave(boolean value) {
            this.autoSave = value;
        }

        /** 예열 호출 횟수. 진짜 저장 <b>전에</b> 걸렸는지 보려고 센다. */
        private int queuedSaves;

        /** {@link #saveNow()} 시점에 예열이 몇 번 걸려 있었는지. */
        private int queuedBeforeSave = -1;

        @Override
        public void saveQueued() {
            queuedSaves++;
        }

        @Override
        public void saveNow() {
            autoSaveWhileSaving = autoSave;
            queuedBeforeSave = queuedSaves;
            if (saveFailure != null) throw saveFailure;
        }
    }
}
