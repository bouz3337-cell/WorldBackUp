package io.github.yj.worldbackup;

import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.BackupService;
import io.github.yj.worldbackup.backup.BackupType;
import io.github.yj.worldbackup.command.WorldBackUpCommand;
import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.listener.ActivityListener;
import io.github.yj.worldbackup.restore.PendingRestore;
import io.github.yj.worldbackup.restore.RestoreApplier;
import io.github.yj.worldbackup.restore.RestoreService;
import io.github.yj.worldbackup.util.Sched;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;

public final class WorldBackUpPlugin extends JavaPlugin {

    private BackupSettings settings;
    private BackupRepository repository;
    private BackupService backupService;
    private RestoreService restoreService;
    private ScheduledTask scheduleTask;
    private ScheduledTask watchdogTask;

    /**
     * 월드가 로드되기 <b>전</b>에 호출된다. 예약된 복원은 반드시 이 시점에 처리해야
     * region 파일이 잠기지 않은 상태에서 안전하게 교체할 수 있다.
     */
    @Override
    public void onLoad() {
        try {
            Path dataFolder = getDataFolder().toPath();
            if (Files.isDirectory(dataFolder)) {
                RestoreApplier.applyIfPending(dataFolder, resolveServerRoot(), getLogger());
            }
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "복원 처리 중 예기치 못한 오류가 발생했습니다.", t);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPlugin();

        registerCommands();

        Bukkit.getPluginManager().registerEvents(new ActivityListener(this), this);

        reportLastRestore();
        runStartupHousekeeping();

        // 안전망: 백업이 돌지 않는데 자동 저장이 꺼진 월드가 남아 있으면 1분마다 되돌린다.
        watchdogTask = Sched.syncTimer(this, () -> backupService.thawLeftovers(), 20L * 60, 20L * 60);

        if (settings.onStartup()) {
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
        if (restoreService != null) {
            restoreService.shutdownTasks();
        }
        // 진행 중이던 백업 때문에 자동 저장이 꺼져 있으면 종료 시 청크 저장이 통째로 스킵될 수 있다.
        // 우리는 지금 메인 스레드에 있으므로 여기서 즉시 되돌린다.
        if (backupService != null) {
            backupService.forceThawOnMainThread();
        }

        boolean restorePending = PendingRestore.exists(getDataFolder().toPath());
        if (settings != null && settings.onShutdown() && !restorePending && backupService != null) {
            getLogger().info("[백업] 서버 종료 백업을 실행합니다. 잠시 기다려 주세요...");
            try {
                backupService.runBlocking(BackupType.SHUTDOWN, "서버 종료", null);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "[백업] 종료 백업에 실패했습니다.", e);
            }
        }
        Sched.cancelAll(this);
    }

    /** config.yml 을 다시 읽고 스케줄러를 재구성한다. */
    public void reloadPlugin() {
        reloadConfig();
        Path serverRoot = resolveServerRoot();
        settings = BackupSettings.load(getConfig(), getDataFolder().toPath(), serverRoot);

        repository = new BackupRepository(settings.backupDir(), getLogger());
        try {
            repository.ensureDirectory();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "백업 폴더를 만들지 못했습니다: " + settings.backupDir(), e);
        }

        if (backupService == null) backupService = new BackupService(this);
        if (restoreService == null) restoreService = new RestoreService(this);

        startSchedule();
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
                repo.cleanupOrphans();
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
        try {
            Sched.async(this, () -> repo.prune(snapshot));
        } catch (Exception ignored) {
            // 종료 중이면 스케줄러가 거부한다.
        }
    }

    private void startSchedule() {
        if (scheduleTask != null) {
            scheduleTask.cancel();
            scheduleTask = null;
        }
        if (!settings.enabled()) return;

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

    /** 직전 부팅에서 복원이 수행됐다면 결과를 콘솔에 알린다. */
    private void reportLastRestore() {
        Path report = getDataFolder().toPath().resolve(PendingRestore.REPORT_NAME);
        if (!Files.isRegularFile(report)) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(report.toFile());
            long finishedAt = report.toFile().lastModified();
            if (System.currentTimeMillis() - finishedAt > 10 * 60 * 1000L) return; // 10분 이내 결과만 표시
            boolean success = yaml.getBoolean("success", false);
            getLogger().info("[복원] 직전 복원 결과: " + (success ? "성공" : "실패")
                    + " (백업 " + yaml.getString("backup-id") + ", 파일 "
                    + yaml.getInt("restored-files") + "개 복원, " + yaml.getInt("failed-files") + "개 실패)");
            // 월드 내용이 통째로 바뀌었으니 차등 백업의 기준을 새로 잡는다.
            if (success && backupService != null) backupService.requireFullBackup();
        } catch (Exception ignored) {
        }
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
