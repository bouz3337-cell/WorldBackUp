package io.github.yj.worldbackup;

import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.BackupService;
import io.github.yj.worldbackup.backup.BackupType;
import io.github.yj.worldbackup.backup.OneBack;
import io.github.yj.worldbackup.backup.PlayerData;
import io.github.yj.worldbackup.backup.WorldLayout;
import io.github.yj.worldbackup.backup.WorldScan;
import io.github.yj.worldbackup.command.WorldBackUpCommand;
import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.config.ConfigMigrator;
import io.github.yj.worldbackup.listener.ActivityListener;
import io.github.yj.worldbackup.restore.PendingRestore;
import io.github.yj.worldbackup.restore.RestoreApplier;
import io.github.yj.worldbackup.restore.RestoreService;
import io.github.yj.worldbackup.restore.UserListSync;
import io.github.yj.worldbackup.restore.UserLists;
import io.github.yj.worldbackup.update.UpdateService;
import io.github.yj.worldbackup.util.Clock;
import io.github.yj.worldbackup.util.FileUtil;
import io.github.yj.worldbackup.util.Sched;
import org.bukkit.World;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public final class WorldBackUpPlugin extends JavaPlugin {

    // 메인 스레드(/wb reload)가 갈아 끼우고 비동기 백업 스레드가 읽는다.
    private volatile BackupSettings settings;
    private volatile BackupRepository repository;
    private BackupService backupService;
    private RestoreService restoreService;
    private ScheduledTask scheduleTask;
    private ScheduledTask watchdogTask;
    private ScheduledTask oneBackTask;

    /**
     * 복원 실패 기록이 남아 있어 자동 작업을 멈춘 상태.
     *
     * <p>반쯤 복원된 월드를 계속 백업하면, 멀쩡한 예전 백업이 보관 정책에 밀려 사라진다.
     * 무인 서버에서는 아무도 콘솔을 보지 않아 그 연쇄가 끝까지 진행되므로, 사람이 확인할
     * 때까지 자동 백업과 보관 정리를 멈춘다. 수동 명령(/wb backup, /wb restore)은 그대로 쓴다.</p>
     */
    private volatile boolean restoreFailureHold;

    /**
     * 플레이어 데이터 탐색 결과 캐시.
     *
     * <p>{@link PlayerData#search} 는 정해진 후보에서 못 찾으면 서버 폴더를 훑는다. 그런데
     * {@code /wb status} 가 이걸 <b>메인 스레드에서</b> 부르므로, 캐시가 없으면 명령 한 번마다
     * 디스크를 훑게 된다. 하필 못 찾는 서버에서만 그렇게 되어 문제 있는 환경이 더 느려진다.</p>
     */
    private static final long PLAYER_DATA_CACHE_MILLIS = 60_000L;

    private volatile PlayerData.Located playerDataCache;
    private volatile long playerDataCachedAt;

    /**
     * 접속을 계기로 한 재탐색을 이미 써 버렸는지.
     *
     * <p>재탐색이 필요한 이유는 하나뿐이다 - 플레이어 데이터 폴더는 <b>첫 접속 때</b> 생긴다.
     * 그런데 못 찾은 상태로 굳어 있으면 접속할 때마다 캐시가 풀려, 그 뒤의 {@code /wb status}
     * 가 메인 스레드에서 서버 폴더를 다시 훑는다. 하필 문제가 있는 환경에서만, 그리고 사람이
     * 몰리는 순간에 그렇게 된다. 한 번이면 충분하고, 그래도 못 찾았다면 폴더가 생겨서 될 일이
     * 아니라 설정을 손봐야 하는 상황이므로 {@code /wb reload} 가 다시 열어 준다.</p>
     */
    private volatile boolean playerDataRescanUsed;

    /**
     * 이번 부팅의 복원이 되돌려 놓은, 서버가 이미 메모리에 올려 둔 목록 파일들.
     *
     * <p>{@code onLoad} 에서 채워 {@code onEnable} 에서 쓴다. 서버는 {@code ops.json} 같은
     * 파일을 플러그인보다 <b>먼저</b> 읽으므로, 복원이 파일을 되돌려 놔도 이번 세션에는
     * 아무것도 달라지지 않는다. 월드가 올라온 뒤에 서버 쪽 목록을 맞춰 넣어야 한다.
     * 자세한 이유는 {@link UserLists}.</p>
     */
    private Set<String> restoredUserLists = Set.of();

    /**
     * 월드가 로드되기 <b>전</b>에 호출된다. 예약된 복원은 반드시 이 시점에 처리해야
     * region 파일이 잠기지 않은 상태에서 안전하게 교체할 수 있다.
     */
    @Override
    public void onLoad() {
        try {
            Path dataFolder = getDataFolder().toPath();
            if (Files.isDirectory(dataFolder)) {
                restoredUserLists = RestoreApplier.applyIfPending(
                        dataFolder, resolveServerRoot(), getLogger());
            }
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "복원 처리 중 예기치 못한 오류가 발생했습니다.", t);
        }
    }

    @Override
    public void onEnable() {
        // 복원이 되돌린 op·화이트리스트·밴 목록을 <b>지금 돌고 있는 서버</b>에 반영한다.
        // 다른 초기화보다 먼저 한다 - 이 뒤로 등록되는 명령어와 리스너가 곧바로 올바른
        // 권한으로 판단해야 하고, 접속이 열리기 전에 밴/화이트리스트가 제자리에 있어야 한다.
        applyRestoredUserLists();

        saveDefaultConfig();
        // 새 버전에서 생긴 설정을 읽기 전에 채워 넣는다.
        migrateConfig();
        // 업데이트가 jar 교체로 이루어지므로, 옛 jar 가 함께 남아 있으면 여기서 잡는다.
        warnAboutDuplicateJars();
        reloadPlugin();

        registerCommands();

        Bukkit.getPluginManager().registerEvents(new ActivityListener(this), this);

        reportLastRestore();
        reportPlayerData();
        runStartupHousekeeping();
        checkForUpdate();
        checkWorlds();

        // 안전망: 백업이 돌지 않는데 자동 저장이 꺼진 월드가 남아 있으면 1분마다 되돌린다.
        watchdogTask = Sched.syncTimer(this, () -> backupService.thawLeftovers(), 20L * 60, 20L * 60);

        if (settings.onStartup() && !restoreFailureHold) {
            Sched.syncLater(this, () ->
                    backupService.startAsync(BackupType.STARTUP, "서버 시작", null)
                            .exceptionally(error -> null), 20L * 10);
        }

        getLogger().info("WorldBackUp 활성화 완료 - 자동 백업 "
                + (settings.enabled() ? settings.intervalMinutes() + "분 주기" : "꺼짐")
                + ", 저장 위치: " + settings.backupDir());
    }

    @Override
    public void onDisable() {
        if (scheduleTask != null) {
            scheduleTask.cancel();
            scheduleTask = null;
        }
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
        if (oneBackTask != null) {
            oneBackTask.cancel();
            oneBackTask = null;
        }
        if (restoreService != null) {
            restoreService.shutdownTasks();
        }
        // 진행 중이던 백업 때문에 자동 저장이 꺼져 있으면 종료 시 청크 저장이 통째로 스킵될 수 있다.
        // 우리는 지금 메인 스레드에 있으므로 여기서 즉시 되돌린다.
        if (backupService != null) {
            backupService.forceThawOnMainThread();
        }

        boolean restorePending = PendingRestore.exists(getDataFolder().toPath());
        if (settings != null && settings.onShutdown() && !restorePending
                && !restoreFailureHold && backupService != null) {
            getLogger().info("[백업] 서버 종료 백업을 실행합니다. 잠시 기다려 주세요...");
            try {
                backupService.runBlocking(BackupType.SHUTDOWN, "서버 종료", null);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "[백업] 종료 백업에 실패했습니다.", e);
            }
        }
        Sched.cancelAll(this);
    }

    /**
     * 새 버전에서 생긴 설정을 기존 {@code config.yml} 에 <b>주석째</b> 끼워 넣는다.
     *
     * <p>{@code saveDefaultConfig()} 는 파일이 없을 때만 쓴다. 그래서 플러그인만 갈아 끼운
     * 서버에서는 새 설정이 파일에 나타나지 않고, 관리자는 그런 설정이 생긴 줄도 모른다.
     * 여기서 채워 넣으므로 업그레이드 뒤 파일을 열면 새 설정과 그 설명이 제자리에 있다.</p>
     *
     * <p>실패해도 시작을 막지 않는다. 설정이 파일에 없어도 코드 기본값으로 도는 데는
     * 문제가 없으니, 여기서 서버를 세울 이유가 없다.</p>
     */
    private void migrateConfig() {
        Path file = getDataFolder().toPath().resolve("config.yml");
        try (InputStream shipped = getResource("config.yml")) {
            if (shipped == null || !Files.isRegularFile(file)) return;

            // 이미 깨져 있는 파일이면 손대지 않는다. 여기서 무언가를 끼워 넣으면 관리자가
            // 고쳐야 할 자리가 늘어날 뿐이다.
            String user = Files.readString(file, StandardCharsets.UTF_8);
            new YamlConfiguration().loadFromString(user);

            ConfigMigrator.Result result = ConfigMigrator.merge(
                    new String(shipped.readAllBytes(), StandardCharsets.UTF_8), user);
            if (!result.changed()) return;

            // 합친 결과가 YAML 로 읽히지 않으면 관리자의 파일에 손대지 않는다.
            new YamlConfiguration().loadFromString(result.text());

            Path temp = file.resolveSibling("config.yml.tmp");
            Files.writeString(temp, result.text(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }

            announceMigration(result);
        } catch (Exception e) {
            getLogger().log(Level.WARNING,
                    "config.yml 에 새 설정을 추가하지 못했습니다. 기본값으로 동작합니다.", e);
        }
    }

    /**
     * 업데이트가 설정 파일에 무엇을 했는지 알린다.
     *
     * <p>관리자는 jar 하나만 갈아 끼운다. 그래서 <b>콘솔의 이 몇 줄이 릴리스 노트다.</b>
     * 특히 "새 기능이 파일에는 들어왔는데 꺼져 있다" 는 사실은 조용히 지나가면 안 된다 -
     * 그러면 켤 수 있는 것이 있는 줄도 모른 채 몇 달이 지난다.</p>
     */
    private void announceMigration(ConfigMigrator.Result result) {
        getLogger().info("==================================================================");
        getLogger().info("업데이트된 버전으로 시작합니다. config.yml 에 새 설정을 넣었습니다.");
        getLogger().info("  " + String.join(", ", result.added()));
        if (!result.guarded().isEmpty()) {
            getLogger().info("");
            getLogger().info("이 중 아래는 <b>지금 동작을 그대로 두는 값</b>으로 넣었습니다."
                    .replace("<b>", "").replace("</b>", ""));
            for (String path : result.guarded()) {
                getLogger().info("  - " + path);
            }
            getLogger().info("jar 를 바꾸는 것만으로 백업이 지워지거나 커지지 않게 하기 위함입니다.");
            getLogger().info("쓰시려면 config.yml 의 해당 설명을 읽고 값을 바꾼 뒤 /wb reload 하세요.");
        }
        getLogger().info("==================================================================");
    }

    /**
     * {@code plugins/} 에 이 플러그인의 jar 가 둘 이상 있으면 알린다.
     *
     * <p>업데이트는 jar 를 덮어쓰는 것으로 한다. 그런데 파일 이름에 버전이 들어 있어서,
     * 새 jar 를 <b>넣기만</b> 하면 옛 jar 가 그대로 남는다. 그러면 같은 플러그인이 두 벌
     * 올라가려 하고, 어느 쪽이 이길지는 서버가 폴더를 읽는 순서에 달렸다 - 업데이트한 줄
     * 알았는데 옛 버전이 돌고 있을 수 있다. 여기서 잡지 않으면 그 사실을 알 방법이 없다.</p>
     *
     * <p>지우지는 않는다. 지금 돌고 있는 jar 는 서버가 열어 두었고, 무엇이 진짜 옛 버전인지는
     * 파일 이름만으로 단정할 수 없다. 사람이 판단할 수 있게 이름만 늘어놓는다.</p>
     */
    private void warnAboutDuplicateJars() {
        Path own;
        try {
            own = getFile().toPath().toAbsolutePath().normalize();
        } catch (Throwable t) {
            return; // jar 위치를 알 수 없으면 판단할 근거도 없다
        }
        Path folder = own.getParent();
        if (folder == null || !Files.isDirectory(folder)) return;

        List<Path> others = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(folder)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".jar") && name.contains("worldbackup");
                    })
                    .filter(path -> !path.toAbsolutePath().normalize().equals(own))
                    .forEach(others::add);
        } catch (IOException e) {
            return;
        }
        if (others.isEmpty()) return;

        getLogger().severe("==================================================================");
        getLogger().severe("plugins 폴더에 WorldBackUp jar 가 " + (others.size() + 1) + "개 있습니다.");
        getLogger().severe("  지금 돌고 있는 것: " + own.getFileName());
        for (Path other : others) {
            getLogger().severe("  그 외          : " + other.getFileName());
        }
        getLogger().severe("업데이트할 때 옛 jar 를 지우지 않으면 어느 쪽이 올라올지 알 수 없습니다.");
        getLogger().severe("서버를 끄고 쓰지 않는 jar 를 지운 뒤 다시 켜세요.");
        getLogger().severe("==================================================================");
    }

    /** config.yml 을 다시 읽고 스케줄러를 재구성한다. */
    public void reloadPlugin() {
        reloadConfig();
        Path serverRoot = resolveServerRoot();
        settings = BackupSettings.load(getConfig(), getDataFolder().toPath(), serverRoot);

        // 시각을 적는 시계를 먼저 세운다. 아래 어떤 것도 시각을 찍기 전에.
        Clock.use(settings.timezone());

        // 저장 위치가 그대로면 저장소를 <b>재사용한다.</b> 새로 만들면 진행 중인 차등 백업이
        // 걸어 둔 pin 이 옛 객체에 남는데, 백업 스레드는 그 옛 객체를 계속 붙잡고 있으므로
        // 새 저장소에서 도는 보관 정리는 "고정된 백업이 없다"고 보고 기준 백업을 지워 버린다.
        // 방금 만든 차등본이 태어나자마자 복원 불가가 되는, pin 이 막으려던 바로 그 상황이다.
        // (위치가 바뀐 경우는 백업 스레드가 옛 폴더만 건드리므로 새로 만들어도 안전하다)
        BackupRepository current = repository;
        if (current == null || !current.directory().equals(settings.backupDir())) {
            repository = new BackupRepository(settings.backupDir(), getLogger());
        }
        try {
            repository.ensureDirectory();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "백업 폴더를 만들지 못했습니다: " + settings.backupDir(), e);
        }

        if (backupService == null) backupService = new BackupService(new PaperServerBridge(this));
        if (restoreService == null) restoreService = new RestoreService(this);

        for (String warning : settings.tierWarnings()) {
            getLogger().warning("[백업] " + warning);
        }

        playerDataCache = null; // 설정이 바뀌면 대상 월드도 바뀔 수 있다
        playerDataRescanUsed = false; // 관리자가 경로를 고쳤을 수 있으니 재탐색을 다시 열어 준다
        checkRestoreFailureHold();
        startSchedule();
        startOneBackSchedule();
    }

    /**
     * 복원이 되돌린 op·화이트리스트·밴 목록을 지금 돌고 있는 서버에 반영한다.
     *
     * <p>여기서 실패해도 서버는 떠야 한다. 복원 직후는 되돌릴 수단이 가장 필요한 순간인데,
     * 목록 하나 때문에 예외가 밖으로 나가면 백업 플러그인 자체가 꺼진 서버가 된다.</p>
     */
    private void applyRestoredUserLists() {
        if (restoredUserLists.isEmpty()) return;
        try {
            UserListSync.apply(restoredUserLists, resolveServerRoot(), getLogger());
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[복원] 되돌린 목록을 서버에 반영하지 못했습니다. "
                    + "파일은 되돌아가 있으니 서버를 한 번 더 재시작하면 적용됩니다.", t);
        } finally {
            restoredUserLists = Set.of(); // 한 번만 한다
        }
    }

    /** 처리되지 않은 복원 실패 기록이 있으면 자동 작업을 멈춘다. */
    private void checkRestoreFailureHold() {
        List<Path> markers = RestoreApplier.failureMarkers(getDataFolder().toPath());
        restoreFailureHold = !markers.isEmpty();
        if (!restoreFailureHold) return;

        getLogger().severe("==================================================================");
        getLogger().severe("처리되지 않은 복원 실패 기록이 " + markers.size() + "개 있습니다.");
        for (Path marker : markers) {
            getLogger().severe("  - " + marker.getFileName());
        }
        getLogger().severe("월드가 온전하지 않을 수 있어 자동 백업과 보관 정리를 멈춥니다.");
        getLogger().severe("그대로 두면 깨진 상태가 백업되면서 멀쩡한 백업이 밀려납니다.");
        getLogger().severe("월드를 확인한 뒤 위 파일을 지우고 /wb reload 를 실행하세요.");
        getLogger().severe("(수동 /wb backup, /wb restore 는 그대로 쓸 수 있습니다)");
        getLogger().severe("==================================================================");
    }

    /** 복원 실패 기록 때문에 자동 작업이 멈춰 있는지. */
    public boolean restoreFailureHold() {
        return restoreFailureHold;
    }

    /**
     * 서버 시작 시 한 번 실행하는 정리 작업.
     *
     * <p>보관 정책은 원래 "백업이 성공한 직후"에만 적용됐다. 자동 백업을 꺼 두거나 접속자가 없어
     * 계속 건너뛰면 오래된 백업이 영원히 남는 문제가 있어, 시작할 때도 한 번 정리한다.</p>
     */
    private void runStartupHousekeeping() {
        BackupRepository repo = repository;
        BackupSettings snapshot = settings;
        if (repo == null || snapshot == null) return;

        Sched.async(this, () -> {
            try {
                repo.cleanupOrphans(); // .tmp 조각과 짝 잃은 사이드카만 지우므로 언제나 안전하다
                // OneBack 조각은 서버 폴더 크기만 하다. 여기서 치우지 않으면 아무도 치우지 않는다.
                if (snapshot.oneBackEnabled()) {
                    OneBack.cleanupTemp(snapshot.oneBackDir(), getLogger());
                }
                if (restoreFailureHold) {
                    // replaced/ 에는 복원이 밀어낸 옛 월드가 들어 있다. 복원이 실패한 상황에서는
                    // 그게 유일한 복구 재료이므로 정리하지 않는다.
                    getLogger().warning("[백업] 복원 실패 기록이 있어 보관 정리와 replaced 정리를 건너뜁니다.");
                    return;
                }
                RestoreApplier.cleanupReplaced(getDataFolder().toPath(), snapshot.keepReplacedMax(), getLogger());
                repo.prune(snapshot);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "시작 시 정리 작업에 실패했습니다.", e);
            }
        });
    }

    /**
     * Brigadier 로 명령어를 등록한다.
     *
     * <p>{@code plugin.yml} 의 {@code commands:} 블록 대신 Paper 의 라이프사이클 이벤트를 쓴다.
     * 플러그인 로딩 방식 자체는 그대로라, 복원이 월드 로드 전에 실행되는 보장은 유지된다.</p>
     */
    @SuppressWarnings("UnstableApiUsage")
    private void registerCommands() {
        try {
            WorldBackUpCommand handler = new WorldBackUpCommand(this);
            LifecycleEventManager<Plugin> lifecycle = getLifecycleManager();
            lifecycle.registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                    handler.build(),
                    "월드와 플레이어 데이터를 백업하고 되돌립니다",
                    List.of("wb", "wbackup")));
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "명령어를 등록하지 못했습니다.", t);
        }
    }

    /** 보관 정책을 비동기로 적용한다. 디스크를 읽으므로 메인 스레드에서 돌리면 안 된다. */
    public void pruneAsync() {
        BackupRepository repo = repository;
        BackupSettings snapshot = settings;
        if (repo == null || snapshot == null) return;
        if (restoreFailureHold) return; // 깨졌을지 모르는 상태에서 멀쩡한 백업을 지우지 않는다
        try {
            Sched.async(this, () -> repo.prune(snapshot));
        } catch (Exception ignored) {
            // 종료 중이면 스케줄러가 거부한다.
        }
    }

    /**
     * OneBack 자동 생성 주기를 건다. {@code interval-hours: 0} 이면 걸지 않는다.
     *
     * <p>기본값이 0 인 이유는 크기다 - 한 번에 서버 폴더 크기만큼 쓴다. 자동으로 도는 것이
     * 기본이면, 디스크가 작은 서버에서 관리자가 알아채기 전에 차 버린다. 이 플러그인이 다른
     * 곳에서 내내 경고하는 바로 그 상황이다.</p>
     */
    private void startOneBackSchedule() {
        if (oneBackTask != null) {
            oneBackTask.cancel();
            oneBackTask = null;
        }
        BackupSettings snapshot = settings;
        if (snapshot == null || !snapshot.oneBackEnabled() || snapshot.oneBackIntervalHours() <= 0) return;
        if (restoreFailureHold) return;

        long periodTicks = snapshot.oneBackIntervalHours() * 60L * 60L * 20L;
        oneBackTask = Sched.syncTimer(this, () -> {
            if (restoreService.isCountingDown()) return;
            if (backupService.isRunning()) return; // 평소 백업이 도는 중이면 다음 주기에
            backupService.startOneBackAsync("자동")
                    .exceptionally(error -> null);
        }, periodTicks, periodTicks);
        getLogger().info("[OneBack] 자동 생성 " + snapshot.oneBackIntervalHours() + "시간 주기 · 저장 위치: "
                + snapshot.oneBackDir());
    }

    private void startSchedule() {
        if (scheduleTask != null) {
            scheduleTask.cancel();
            scheduleTask = null;
        }
        if (!settings.enabled()) return;
        if (restoreFailureHold) {
            getLogger().severe("[백업] 복원 실패 기록이 있어 자동 백업을 시작하지 않습니다.");
            return;
        }

        long periodTicks = settings.intervalMinutes() * 60L * 20L;
        long delayTicks = Math.max(20L * 10, settings.initialDelayMinutes() * 60L * 20L);
        backupService.setNextRunAt(System.currentTimeMillis() + delayTicks * 50L);

        scheduleTask = Sched.syncTimer(this, () -> {
            backupService.setNextRunAt(System.currentTimeMillis() + periodTicks * 50L);
            if (restoreService.isCountingDown()) return;
            if (backupService.isRunning()) {
                getLogger().warning("[백업] 이전 백업이 아직 진행 중이라 이번 주기는 건너뜁니다.");
                return;
            }
            if (backupService.shouldSkipScheduled()) {
                getLogger().info("[백업] 접속자와 변경사항이 없어 이번 주기는 건너뜁니다.");
                pruneAsync(); // 백업을 건너뛰어도 보관 기간이 지난 백업은 정리한다.
                return;
            }
            backupService.startAsync(BackupType.SCHEDULED, null, null).exceptionally(error -> null);
        }, delayTicks, periodTicks);
    }

    /** 직전 부팅에서 복원이 수행됐다면 결과를 콘솔에 알리고, 차등 백업의 기준을 새로 잡게 한다. */
    private void reportLastRestore() {
        Path report = getDataFolder().toPath().resolve(PendingRestore.REPORT_NAME);
        if (!Files.isRegularFile(report)) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(report.toFile());
            long finishedAt = report.toFile().lastModified();
            boolean success = yaml.getBoolean("success", false);

            // 보고서는 지워지지 않고 남으므로, 알림은 갓 끝난 복원에 대해서만 한 번 띄운다.
            if (System.currentTimeMillis() - finishedAt <= 10 * 60 * 1000L) {
                getLogger().info("[복원] 직전 복원 결과: " + (success ? "성공" : "실패")
                        + " (백업 " + yaml.getString("backup-id") + ", 파일 "
                        + yaml.getInt("restored-files") + "개 복원, " + yaml.getInt("failed-files") + "개 실패)");
            }

            // 월드 내용이 통째로 바뀌었으니 차등 백업의 기준을 새로 잡는다.
            //
            // 이 판단은 위 알림용 시간 창과 <b>분리해야 한다.</b> 복원 직후 백업을 한 번도 만들지
            // 않은 채 서버를 껐다가 한참 뒤에 켜면 재설정이 통째로 빠지는데, 그러면 다음 차등본이
            // 낡은 기준 위에 얹힌다. 복원은 zip 의 초 단위 타임스탬프로 수정 시각을 되돌리므로
            // 거의 모든 파일이 "바뀜"으로 판정되어, 그 차등본은 월드 전체를 담고서도 쓸모없어진
            // 기준까지 붙잡아 둔다. 디스크에 월드가 두 벌 남는 셈이다.
            //
            // 복원 이후에 만들어진 백업이 이미 있다면 기준은 그 백업이 새로 잡았으므로 건너뛴다.
            if (success && backupService != null && !hasBackupNewerThan(finishedAt)) {
                backupService.requireFullBackup();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 백업 대상 월드들과 서버 루트에서 플레이어 데이터를 찾는다.
     *
     * <p>{@code onEnable} 시점에는 월드가 이미 로드되어 있어 실제 폴더 위치를 알 수 있다.</p>
     */
    public PlayerData.Located locatePlayerData() {
        PlayerData.Located cached = playerDataCache;
        if (cached != null && System.currentTimeMillis() - playerDataCachedAt < PLAYER_DATA_CACHE_MILLIS) {
            return cached;
        }
        BackupSettings snapshot = settings;
        PlayerData.Located located = snapshot == null
                ? PlayerData.locate(List.of())
                : PlayerData.search(snapshot.serverRoot(), playerDataBases(snapshot));
        playerDataCache = located;
        playerDataCachedAt = System.currentTimeMillis();
        return located;
    }

    /**
     * 첫 접속으로 플레이어 데이터 폴더가 막 생겼을 수 있다.
     *
     * <p>못 찾은 상태였을 때만, 그리고 <b>서버가 켜진 뒤 한 번만</b> 캐시를 버린다.
     * 이미 찾아 둔 경우에는 접속마다 다시 뒤질 이유가 없고, 여전히 못 찾는 경우라면 폴더가
     * 생겨서 해결될 일이 아니라 설정을 손봐야 하는 상황이다. 사람이 몰리는 순간마다 서버
     * 폴더를 훑는 것이 그 진단에 도움이 되지도 않는다. 다시 열려면 {@code /wb reload}.</p>
     */
    public void refreshPlayerDataIfMissing() {
        if (playerDataRescanUsed) return;
        PlayerData.Located cached = playerDataCache;
        if (cached == null || cached.inventory()) return;
        playerDataRescanUsed = true;
        playerDataCache = null;
    }

    /**
     * 플레이어 데이터를 찾아볼 기준 경로들.
     *
     * <p>{@code getWorldFolder()} 가 차원 폴더를 돌려주는 버전이 있어서, 그대로 쓰면
     * 그 위에 있는 {@code playerdata} 를 영영 못 찾는다. 월드 폴더까지 올라가서 본다.</p>
     */
    private List<Path> playerDataBases(BackupSettings snapshot) {
        List<Path> bases = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (!snapshot.includesWorld(world.getName())) continue;
            Path folder = world.getWorldPath().toAbsolutePath().normalize();
            Path levelRoot = WorldLayout.levelRoot(folder, snapshot.serverRoot());
            if (!bases.contains(levelRoot)) bases.add(levelRoot);
        }
        bases.add(snapshot.serverRoot());
        return bases;
    }

    /**
     * 인벤토리가 백업에 들어가는지 시작할 때 한 번 확인해 알린다.
     *
     * <p>첫 백업을 기다렸다가 알게 되거나, 최악의 경우 롤백할 때 알게 되는 것을 막는다.</p>
     */
    private void reportPlayerData() {
        PlayerData.Located located = locatePlayerData();
        if (!located.inventory()) {
            PlayerData.warnMissing(getLogger(), settings.serverRoot(), playerDataBases(settings));
            return;
        }
        List<String> shown = new ArrayList<>();
        for (Path path : located.paths()) {
            String relative = FileUtil.relativize(settings.serverRoot(), path);
            shown.add(relative == null ? path.toString() : relative);
        }
        getLogger().info("[백업] 플레이어 데이터 포함: " + String.join(", ", shown));
    }

    /** 이 시각 이후에 만들어진 백업이 있는지. */
    private boolean hasBackupNewerThan(long millis) {
        BackupRepository repo = repository;
        if (repo == null) return false;
        return repo.list().stream().anyMatch(entry -> entry.createdAt().toEpochMilli() > millis);
    }

    /**
     * 서버 루트 폴더.
     *
     * <p>{@code server.properties}, {@code ops.json}, {@code plugins/} 가 있는 <b>실행 디렉터리</b>를
     * 기준으로 삼는다. 한때 {@code Bukkit.getWorldContainer()} 를 썼지만, bukkit.yml 의
     * {@code settings.world-container} 나 {@code --universe} 로 월드 폴더를 옮겨 둔 서버에서는
     * 서버 설정 파일들이 조용히 백업에서 빠지는 문제가 있었다. 또 이 값은 월드가 로드되기 전
     * ({@code onLoad}) 에도 동일하게 구해져야 백업/복원의 기준 경로가 어긋나지 않는다.</p>
     */
    public static Path resolveServerRoot() {
        return Paths.get("").toAbsolutePath().normalize();
    }

    /**
     * 새 버전이 있는지 시작할 때 한 번 확인한다.
     *
     * <p>이 플러그인은 jar 하나만 갈아 끼우면 업데이트된다. 그런데 <b>새 버전이 나온 줄
     * 모르면</b> 그 편의가 소용없다 - 고쳐 놓은 결함을 그대로 안고 도는 서버가 된다.</p>
     *
     * <p>확인만 한다. {@code update.auto-download} 를 켠 서버에서만 실제로 내려받는다.
     * 켜지 않은 서버에서 플러그인이 스스로 코드를 받아 오는 일은 없다.</p>
     *
     * <p><b>비동기</b>다. 깃허브가 늦게 답하는 동안 서버가 멈추면 안 된다. 그리고 실패는
     * 조용히 넘긴다 - 인터넷이 없는 서버에서 부팅마다 빨간 줄이 뜰 이유가 없다.</p>
     */
    private void checkForUpdate() {
        BackupSettings snapshot = settings;
        if (snapshot == null || !snapshot.updateCheck()) return;

        String current = getPluginMeta().getVersion();
        try {
            Sched.async(this, () -> {
                UpdateService service = new UpdateService(snapshot.updateRepository(), "WorldBackUp");
                UpdateService.Outcome outcome = snapshot.updateAutoDownload()
                        ? service.download(current, updateFolder(), jarName())
                        : service.check(current);
                announceUpdate(outcome, current);
            });
        } catch (Throwable ignored) {
            // 스케줄러가 거부했다(종료 중). 업데이트 확인 때문에 시작을 흔들 이유는 없다.
        }
    }

    private void announceUpdate(UpdateService.Outcome outcome, String current) {
        if (outcome instanceof UpdateService.Outcome.Available available) {
            getLogger().warning("==================================================================");
            getLogger().warning("새 버전이 있습니다: " + current
                    + " -> " + available.release().version());
            getLogger().warning("/wb update 를 치면 받아 두고, 다음 재시작에 적용됩니다.");
            getLogger().warning("" + available.release().pageUrl());
            getLogger().warning("==================================================================");
        } else if (outcome instanceof UpdateService.Outcome.Staged staged) {
            getLogger().warning("==================================================================");
            getLogger().warning("새 버전 " + staged.release().version() + " 을 받아 두었습니다.");
            getLogger().warning("서버를 다시 켜면 자동으로 갈아 끼워집니다.");
            getLogger().warning("(update.auto-download 가 켜져 있습니다)");
            getLogger().warning("==================================================================");
        } else if (outcome instanceof UpdateService.Outcome.Failed failed) {
            // 인터넷이 없는 서버가 흔하다. 부팅마다 경고를 띄울 일이 아니다.
            getLogger().fine("업데이트 확인 실패: " + failed.reason());
        }
    }

    /**
     * 버킷이 다음 시작 때 {@code plugins/} 로 옮겨 주는 폴더.
     *
     * <p>새 jar 를 여기 놓는다. 돌고 있는 jar 를 그 자리에서 바꾸면 윈도우에서는 잠겨서
     * 실패하고, 리눅스에서는 성공해도 이미 올라간 클래스는 그대로라 반쯤 새 버전인 서버가
     * 된다. 이 폴더를 쓰면 옛 jar 가 함께 남는 문제까지 서버가 알아서 없애 준다.</p>
     */
    /**
     * 시작할 때 디스크의 월드를 훑어, 백업이 놓치고 있는 것을 알린다.
     *
     * <p>이것이 없으면 두 가지가 <b>조용히</b> 일어난다.</p>
     * <ul>
     *   <li>언로드된 월드가 백업에서 빠진다. 백업은 성공으로 끝나고 아무 말이 없다.</li>
     *   <li>지형이나 플레이어 데이터가 사라진 월드가 <b>계속 백업된다.</b> 그러면 망가진
     *       상태의 백업이 쌓이면서 멀쩡한 예전 백업이 보관 정책에 밀려 사라진다 -
     *       복원 실패 정지({@link #restoreFailureHold})가 막으려는 것과 같은 연쇄다.</li>
     * </ul>
     *
     * <p>크기는 재지 않는다. 그러면 월드 전체를 훑게 되어 부팅이 그만큼 늦어진다.
     * 자세히 보려면 {@code /wb check}.</p>
     */
    private void checkWorlds() {
        BackupSettings snapshot = settings;
        if (snapshot == null) return;
        try {
            Sched.async(this, () -> {
                List<WorldScan.World> onDisk = WorldScan.findOnDisk(snapshot.serverRoot());
                if (onDisk.isEmpty()) return;

                List<WorldScan.World> missing =
                        WorldScan.missingFromBackup(onDisk, backedUpWorldFolders());
                for (WorldScan.World world : onDisk) {
                    for (String problem : WorldScan.problems(world)) {
                        getLogger().warning("[점검] 월드 '" + world.name() + "' - " + problem);
                    }
                }
                if (missing.isEmpty()) return;

                getLogger().severe("==================================================================");
                getLogger().severe("백업에 담기지 않는 월드가 " + missing.size() + "개 있습니다.");
                for (WorldScan.World world : missing) {
                    String relative = FileUtil.relativize(snapshot.serverRoot(), world.folder());
                    getLogger().severe("  - " + (relative == null ? world.name() : relative));
                }
                getLogger().severe("백업은 로드된 월드만 봅니다."
                        + " 플러그인이 만들어 두고 언로드한 월드는 빠집니다.");
                getLogger().severe("담으시려면 config.yml 의 targets.extra-paths 에 위 경로를 넣고");
                getLogger().severe("/wb reload 를 실행하세요. 자세히 보려면 /wb check.");
                getLogger().severe("==================================================================");
            });
        } catch (Throwable ignored) {
            // 스케줄러가 거부했다(종료 중). 점검 때문에 시작을 흔들 이유는 없다.
        }
    }

    /**
     * 지금 백업이 담고 있는 월드 폴더들.
     *
     * <p>{@code /wb check} 가 "디스크에는 있는데 백업에는 없는 월드" 를 가려내는 데 쓴다.
     * 백업 대상은 로드된 월드에서 오므로, 언로드된 월드는 여기 없다 - 그 차이가 곧
     * <b>조용히 빠지는 월드</b>다.</p>
     *
     * <p>{@code extra-paths} 로 직접 넣어 둔 경로도 함께 센다. 그것으로 언로드된 월드를
     * 담아 두었다면 이미 백업되고 있는 것이므로 경고할 이유가 없다.</p>
     */
    public Set<Path> backedUpWorldFolders() {
        BackupSettings snapshot = settings;
        Set<Path> folders = new java.util.LinkedHashSet<>();
        if (snapshot == null) return folders;

        for (World world : Bukkit.getWorlds()) {
            if (!snapshot.includesWorld(world.getName())) continue;
            folders.add(WorldLayout.levelRoot(
                    world.getWorldPath().toAbsolutePath().normalize(), snapshot.serverRoot()));
        }
        for (String relative : snapshot.extraPaths()) {
            folders.add(snapshot.serverRoot().resolve(relative).toAbsolutePath().normalize());
        }
        return folders;
    }

    /** 지금 돌고 있는 jar 의 파일 이름. 업데이트를 놓을 때 이 이름이어야 갈아 끼워진다. */
    public String jarName() {
        return getFile().getName();
    }

    public Path updateFolder() {
        return getServer().getUpdateFolderFile().toPath();
    }

    public BackupSettings settings() {
        return settings;
    }

    public BackupRepository repository() {
        return repository;
    }

    public BackupService backupService() {
        return backupService;
    }

    public RestoreService restoreService() {
        return restoreService;
    }
}
