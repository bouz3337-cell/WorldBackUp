package io.github.yj.worldbackup;

import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.BackupService;
import io.github.yj.worldbackup.backup.BackupType;
import io.github.yj.worldbackup.backup.Manifest;
import io.github.yj.worldbackup.backup.ServerBridge;
import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.restore.PendingRestore;
import io.github.yj.worldbackup.restore.RestoreApplier;
import io.github.yj.worldbackup.restore.RestoreService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>다른 플러그인의 데이터가 백업되고, 되돌리면 그대로 돌아오는가.</b>
 *
 * <p>"아카이브에 들어갔다" 까지만 확인하는 것으로는 부족하다. 관리자가 알고 싶은 것은
 * <b>되돌렸을 때 경제 잔고와 보호구역이 그때로 돌아오느냐</b>다. 그 사이에는 차등 백업이
 * 끼어 있다 - 바뀌지 않은 플러그인 파일은 이번 zip 에 <b>담기지 않고</b> 기준 백업에서
 * 꺼내 오므로, 그 이음매가 어긋나면 파일이 조용히 사라진다.</p>
 *
 * <p>그래서 실제 서버 모양(다른 플러그인 셋 + 월드)을 만들어 놓고 백업 → 사고 → 복원을
 * 통째로 돌린다. 여기서 도는 것은 전부 실제 코드다 - {@link BackupService} 가 대상을
 * 고르고, {@link RestoreApplier} 가 되돌린다.</p>
 */
class PluginDataRoundTripTest {

    private static final Logger LOG = Logger.getLogger("PluginDataRoundTrip");

    @TempDir
    Path tmp;

    private Path serverRoot;
    private Path dataFolder;
    private Path backupDir;
    private FakeServer server;

    @BeforeEach
    void setUp() throws IOException {
        serverRoot = tmp.resolve("server");
        dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        backupDir = dataFolder.resolve("backups");
        Files.createDirectories(backupDir);

        // 월드
        write(serverRoot.resolve("world/level.dat"), "LEVEL");
        write(serverRoot.resolve("world/region/r.0.0.mca"), "지형");
        write(serverRoot.resolve("world/playerdata/uuid.dat"), "인벤토리");
        write(serverRoot.resolve("server.properties"), "motd=hello");

        // 상위 폴더(plugins/)에 있는 다른 플러그인들 - 실서버와 같은 모양
        write(serverRoot.resolve("plugins/MineProtect.jar"), "MINEPROTECT-JAR-v1");
        write(serverRoot.resolve("plugins/MineProtect/config.yml"), "잠금: 2개");
        write(serverRoot.resolve("plugins/MineProtect/locks.yml"), "잠금목록-원본");
        write(serverRoot.resolve("plugins/mopi.jar"), "MOPI-JAR-v1");
        write(serverRoot.resolve("plugins/mopi/data/players.yml"), "모피-플레이어-원본");
        write(serverRoot.resolve("plugins/EventSystem.jar"), "EVENT-JAR-v1");
        write(serverRoot.resolve("plugins/EventSystem/economy.db"), "잔고: 12345");
        write(dataFolder.resolve("config.yml"), "backup: {}");

        server = new FakeServer();
        configure(cfg -> {
        });
    }

    // ------------------------------------------------------------------

    /** 전체 백업에 다른 플러그인의 jar 와 데이터가 모두 들어간다. */
    @Test
    void aFullBackupCarriesEveryOtherPluginsJarAndData() throws Exception {
        BackupEntry entry = new BackupService(server).runBlocking(BackupType.MANUAL, null, null);
        Set<String> names = entriesOf(entry.archive());

        assertTrue(entry.roots().contains("plugins"), "plugins 폴더가 백업 대상이어야 한다");
        for (String path : List.of(
                "plugins/MineProtect.jar", "plugins/MineProtect/locks.yml",
                "plugins/mopi.jar", "plugins/mopi/data/players.yml",
                "plugins/EventSystem.jar", "plugins/EventSystem/economy.db")) {
            assertTrue(names.contains(path), "백업에 없다: " + path);
        }
    }

    /**
     * <b>이 시뮬레이션의 요점.</b> 사고가 나도 플러그인 데이터가 그대로 돌아온다.
     *
     * <p>경제 잔고가 날아가고, 보호구역이 지워지고, 플러그인 폴더 하나가 통째로 사라진
     * 상황을 만들어 놓고 되돌린다.</p>
     */
    @Test
    void everyPluginsDataComesBackAfterARestore() throws Exception {
        BackupEntry entry = new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        // ── 사고 ──
        write(serverRoot.resolve("plugins/EventSystem/economy.db"), "잔고: 0");        // 잔고가 날아감
        Files.delete(serverRoot.resolve("plugins/MineProtect/locks.yml"));            // 보호구역이 지워짐
        deleteTree(serverRoot.resolve("plugins/mopi"));                               // 폴더째 사라짐
        write(serverRoot.resolve("world/region/r.0.0.mca"), "테러당함");

        restore(entry);

        assertEquals("잔고: 12345", read(serverRoot.resolve("plugins/EventSystem/economy.db")),
                "경제 잔고가 그때로 돌아와야 한다");
        assertEquals("잠금목록-원본", read(serverRoot.resolve("plugins/MineProtect/locks.yml")),
                "지워진 보호구역 파일이 되살아나야 한다");
        assertEquals("모피-플레이어-원본", read(serverRoot.resolve("plugins/mopi/data/players.yml")),
                "폴더째 사라져도 되살아나야 한다");
        assertEquals("지형", read(serverRoot.resolve("world/region/r.0.0.mca")));
    }

    /**
     * <b>차등 백업으로도 그대로 돌아온다.</b>
     *
     * <p>실서버가 실제로 쓰는 방식이다. 바뀌지 않은 플러그인 파일은 이번 zip 에 담기지
     * <b>않고</b> 기준 백업에서 꺼내 온다. 그 이음매가 어긋나면 "백업은 성공했는데 되돌리니
     * 파일이 없다" 가 된다.</p>
     */
    @Test
    void aDifferentialBackupStillRestoresUntouchedPluginData() throws Exception {
        BackupService service = new BackupService(server);
        BackupEntry base = service.runBlocking(BackupType.MANUAL, null, null);

        // 잔고만 바뀌었다. 나머지 플러그인 파일은 그대로다.
        write(serverRoot.resolve("plugins/EventSystem/economy.db"), "잔고: 99999");
        BackupEntry diff = service.runBlocking(BackupType.MANUAL, null, null);

        assertEquals(base.id(), diff.baseId(), "두 번째는 차등이어야 한다");

        Set<String> stored = entriesOf(diff.archive());
        assertTrue(stored.contains("plugins/EventSystem/economy.db"), "바뀐 파일은 담긴다");
        assertFalse(stored.contains("plugins/mopi/data/players.yml"),
                "바뀌지 않은 파일은 다시 담지 않는다 - 그게 차등의 이점이다");

        // 그래도 <b>목록</b>에는 있어야 한다. 없으면 복원이 그 파일을 되돌리지 않는다.
        Manifest manifest = Manifest.readFrom(diff.archive()).orElseThrow();
        assertTrue(manifest.contains("plugins/mopi/data/players.yml"),
                "담지 않았다고 목록에서도 빠지면, 복원은 그 파일을 없는 것으로 본다");

        // ── 사고 ──
        deleteTree(serverRoot.resolve("plugins/mopi"));
        write(serverRoot.resolve("plugins/EventSystem/economy.db"), "잔고: 0");

        restore(diff);

        assertEquals("모피-플레이어-원본", read(serverRoot.resolve("plugins/mopi/data/players.yml")),
                "차등본에 담기지 않은 파일은 기준 백업에서 꺼내 와야 한다");
        assertEquals("잔고: 99999", read(serverRoot.resolve("plugins/EventSystem/economy.db")),
                "차등본에 담긴 파일은 그쪽 판이 이겨야 한다");
    }

    /**
     * 플러그인 <b>jar</b> 는 되돌리지 않는다.
     *
     * <p>사흘 전으로 되돌릴 때 원하는 것은 대개 "지금 코드 + 그때 데이터" 이지 사흘 전 버전의
     * 플러그인이 아니다. 게다가 복원은 월드가 올라오기 전에 도는데, 그 시점에는 서버가 이미
     * 모든 jar 를 열어 둔 뒤다.</p>
     */
    @Test
    void pluginJarsAreBackedUpButNeverRestoredOverTheRunningOnes() throws Exception {
        BackupEntry entry = new BackupService(server).runBlocking(BackupType.MANUAL, null, null);
        assertTrue(entriesOf(entry.archive()).contains("plugins/mopi.jar"), "담기기는 한다");

        // 그 뒤 플러그인을 새 버전으로 올렸다
        write(serverRoot.resolve("plugins/mopi.jar"), "MOPI-JAR-v2");

        restore(entry);

        assertEquals("MOPI-JAR-v2", read(serverRoot.resolve("plugins/mopi.jar")),
                "지금 쓰는 jar 가 그대로 남아야 한다");
        assertEquals("모피-플레이어-원본", read(serverRoot.resolve("plugins/mopi/data/players.yml")),
                "데이터는 그때로 돌아간다");
    }

    /**
     * 백업 뒤에 <b>새로 설치한</b> 플러그인의 데이터는 복원으로 사라진다.
     *
     * <p>그것이 "그 시점으로 되돌린다" 의 뜻이다. 다만 조용히 사라지면 안 되므로
     * {@code replaced/} 에 옮겨 두어 되찾을 수 있게 한다.</p>
     */
    @Test
    void dataFromAPluginInstalledAfterTheBackupIsMovedAsideNotLost() throws Exception {
        BackupEntry entry = new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        write(serverRoot.resolve("plugins/NewShop/shops.yml"), "새-상점-데이터");

        restore(entry, true); // keep-replaced 켜기

        assertFalse(Files.exists(serverRoot.resolve("plugins/NewShop/shops.yml")),
                "백업 시점에 없던 것은 그 시점으로 돌아가면 없어야 한다");
        assertTrue(findUnder(dataFolder.resolve(RestoreApplier.REPLACED_FOLDER), "shops.yml"),
                "그래도 replaced/ 에 남아 되찾을 수 있어야 한다");
    }

    /** 플러그인이 자기 폴더 안에 둔 라이브러리 jar 는 그 플러그인의 데이터다. 함께 되돌린다. */
    @Test
    void aLibraryJarInsideAPluginFolderIsTreatedAsItsData() throws Exception {
        write(serverRoot.resolve("plugins/mopi/libs/driver.jar"), "드라이버-원본");
        BackupEntry entry = new BackupService(server).runBlocking(BackupType.MANUAL, null, null);

        write(serverRoot.resolve("plugins/mopi/libs/driver.jar"), "망가짐");
        restore(entry);

        assertEquals("드라이버-원본", read(serverRoot.resolve("plugins/mopi/libs/driver.jar")),
                "plugins/ 바로 아래의 jar 만 지킨다. 그 안쪽은 데이터다");
    }

    // ------------------------------------------------------------------

    private void restore(BackupEntry entry) throws IOException {
        restore(entry, false);
    }

    /** {@code /wb confirm} 이 하는 일과 같은 예약을 걸고, 다음 부팅의 복원을 돌린다. */
    private void restore(BackupEntry entry, boolean keepReplaced) throws IOException {
        BackupSettings settings = server.settings();
        BackupRepository repository = server.repository();
        Path baseArchive = repository.base(entry).map(BackupEntry::archive).orElse(null);

        new PendingRestore(entry.id(), entry.archive(), baseArchive, "tester",
                System.currentTimeMillis(), keepReplaced, 3, true,
                RestoreService.restorePreserve(settings.preservePatterns(), entry.excludes()),
                entry.roots()).write(dataFolder);

        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);
        assertTrue(RestoreApplier.failureMarkers(dataFolder).isEmpty(), "복원이 실패 표식을 남겼다");
    }

    private void configure(Consumer<YamlConfiguration> tweak) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("backup.broadcast", false);
        cfg.set("backup.compression-level", 0);
        cfg.set("backup.flush-settle-seconds", 0);
        cfg.set("targets.worlds", List.of("*"));
        cfg.set("targets.server-files", List.of("server.properties"));
        cfg.set("retention.max-backups", 0);
        cfg.set("retention.min-backups", 0);
        cfg.set("retention.max-age-days", 0);
        cfg.set("retention.keep-daily", 0);
        cfg.set("retention.tiers", List.of());
        tweak.accept(cfg);
        server.settings = BackupSettings.load(cfg, dataFolder, serverRoot);
        server.repository = new BackupRepository(backupDir, LOG);
    }

    private static Set<String> entriesOf(Path archive) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (!entry.isDirectory()) names.add(entry.getName());
            }
        }
        return names;
    }

    private static boolean findUnder(Path root, String fileName) throws IOException {
        if (!Files.isDirectory(root)) return false;
        try (var walk = Files.walk(root)) {
            return walk.anyMatch(path -> path.getFileName().toString().equals(fileName));
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------

    /** 서버 없이 백업을 돌리기 위한 최소 구현. 월드는 파일로만 존재한다. */
    private final class FakeServer implements ServerBridge {

        private BackupSettings settings;
        private BackupRepository repository;

        @Override
        public Logger logger() {
            return LOG;
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
            return false;
        }

        @Override
        public boolean hasOnlinePlayers() {
            return false;
        }

        @Override
        public String serverVersion() {
            return "test";
        }

        @Override
        public void savePlayers() {
        }

        @Override
        public List<WorldHandle> worlds() {
            List<WorldHandle> handles = new ArrayList<>();
            handles.add(new FakeWorld("world", serverRoot.resolve("world")));
            return handles;
        }

        @Override
        public Optional<WorldHandle> world(String name) {
            return worlds().stream().filter(w -> w.name().equals(name)).findFirst();
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public void runSyncQuietly(Runnable task) {
            task.run();
        }

        @Override
        public <T> T callSync(Callable<T> callable, long timeoutSeconds) throws Exception {
            return callable.call();
        }

        @Override
        public void broadcast(String message, String permission) {
        }
    }

    private record FakeWorld(String name, Path folder) implements ServerBridge.WorldHandle {

        @Override
        public boolean autoSave() {
            return true;
        }

        @Override
        public void autoSave(boolean value) {
        }

        @Override
        public void saveQueued() {
        }

        @Override
        public void saveNow() {
        }
    }
}
