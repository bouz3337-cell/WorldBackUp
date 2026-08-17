package io.github.yj.worldbackup;

import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.BackupService;
import io.github.yj.worldbackup.backup.BackupType;
import io.github.yj.worldbackup.backup.ServerBridge;
import io.github.yj.worldbackup.config.BackupSettings;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

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
    // 도우미

    private void configure(Consumer<YamlConfiguration> tweak) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("backup.broadcast", false);
        cfg.set("backup.compression-level", 0); // 테스트를 빠르게
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

        private final Logger log = Logger.getLogger("BackupServiceTest");
        private final List<FakeWorld> worlds = new ArrayList<>();

        private BackupSettings settings;
        private BackupRepository repository = new BackupRepository(backupDir, log);
        private boolean hold;
        private boolean online;

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

        @Override
        public void saveNow() {
            autoSaveWhileSaving = autoSave;
            if (saveFailure != null) throw saveFailure;
        }
    }
}
