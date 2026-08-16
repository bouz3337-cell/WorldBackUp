package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.WorldBackUpPlugin;
import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.util.FileUtil;
import io.github.yj.worldbackup.util.Msg;
import io.github.yj.worldbackup.util.Sched;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** 백업 실행을 담당한다. 파일 압축은 비동기, 월드 저장은 메인 스레드에서 처리한다. */
public final class BackupService {

    /** 메인 스레드 작업을 기다리는 최대 시간. */
    private static final long SYNC_TIMEOUT_SECONDS = 120L;

    private final WorldBackUpPlugin plugin;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean worldChanged = new AtomicBoolean(true);

    /**
     * 자동 저장을 꺼 둔 월드와 원래 값.
     *
     * <p>실행 흐름(반환값)과 <b>분리해서</b> 들고 있는 것이 중요하다. 얼리는 작업이 타임아웃이나
     * 예외로 끝나도 여기에는 기록이 남아 있어야 원복이 보장된다. 자동 저장이 꺼진 채 방치되면
     * 서버가 크래시했을 때 그 세션 전체가 날아간다.</p>
     */
    private final Map<String, Boolean> frozenWorlds = new ConcurrentHashMap<>();

    private volatile String progressText = "";
    private volatile BackupEntry lastBackup;
    private volatile String lastError;
    private volatile long nextRunAt;

    /** 복원 직후처럼 기준을 새로 잡아야 하는 상황에서 다음 백업을 전체 백업으로 강제한다. */
    private final AtomicBoolean forceFullBackup = new AtomicBoolean(false);

    public BackupService(WorldBackUpPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return running.get();
    }

    public String progressText() {
        return progressText;
    }

    public Optional<BackupEntry> lastBackup() {
        return Optional.ofNullable(lastBackup);
    }

    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    public long nextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(long millis) {
        this.nextRunAt = millis;
    }

    /**
     * 월드에 변화가 있었다고 표시한다.
     *
     * <p>블록 설치·파괴 이벤트마다 불리는, 이 플러그인에서 가장 뜨거운 경로다. 이미 켜져 있으면
     * 쓰지 않는다 - volatile 쓰기는 메모리 배리어를 동반하지만 읽기는 사실상 공짜다.</p>
     */
    public void markWorldChanged() {
        if (!worldChanged.get()) worldChanged.set(true);
    }

    /** 다음 백업을 차등이 아닌 전체 백업으로 만든다. */
    public void requireFullBackup() {
        forceFullBackup.set(true);
    }

    /** 자동 백업을 건너뛰어도 되는 상황인지. (메인 스레드에서 호출) */
    public boolean shouldSkipScheduled() {
        BackupSettings settings = plugin.settings();
        if (!settings.skipIfNoPlayers()) return false;
        if (!Bukkit.getOnlinePlayers().isEmpty()) return false;
        return !worldChanged.get();
    }

    /** 비동기 백업 시작. 이미 진행 중이면 실패한 future 를 반환한다. */
    public CompletableFuture<BackupEntry> startAsync(BackupType type, String label, CommandSender initiator) {
        CompletableFuture<BackupEntry> future = new CompletableFuture<>();
        if (!running.compareAndSet(false, true)) {
            future.completeExceptionally(new IllegalStateException("이미 백업이 진행 중입니다."));
            return future;
        }
        try {
            Sched.async(plugin, () -> {
                try {
                    future.complete(execute(type, label, initiator));
                } catch (Throwable t) {
                    lastError = t.getMessage();
                    future.completeExceptionally(t);
                } finally {
                    running.set(false);
                    progressText = "";
                }
            });
        } catch (Throwable t) {
            // 스케줄러가 등록을 거부했다(대개 서버 종료 중). 여기서 플래그를 되돌리지 않으면
            // running 이 영원히 true 로 남아 이후 모든 백업은 물론 /wb restore 까지
            // "백업이 진행 중입니다" 로 막히고, 재시작 말고는 풀 방법이 없다.
            // future 도 완결시켜야 호출자의 whenComplete 가 무응답으로 끝나지 않는다.
            running.set(false);
            progressText = "";
            lastError = t.getMessage();
            future.completeExceptionally(t);
        }
        return future;
    }

    /** 메인 스레드에서 동기 실행한다(서버 종료 시 사용). */
    public BackupEntry runBlocking(BackupType type, String label, CommandSender initiator) throws Exception {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("이미 백업이 진행 중입니다.");
        }
        try {
            return execute(type, label, initiator);
        } finally {
            running.set(false);
            progressText = "";
        }
    }

    /**
     * 서버 종료 직전에 호출한다. 남아 있는 자동 저장 OFF 상태를 <b>메인 스레드에서 즉시</b> 되돌린다.
     *
     * <p>이걸 하지 않으면 서버 종료 시 청크 저장이 통째로 스킵될 수 있다.</p>
     */
    public void forceThawOnMainThread() {
        Map<String, Boolean> snapshot = drainFrozen();
        if (snapshot.isEmpty()) return;
        plugin.getLogger().warning("[백업] 백업이 끝나지 않아 월드 자동 저장을 강제로 복구합니다.");
        restoreAutoSave(snapshot);
    }

    /**
     * 안전망. 백업이 돌고 있지 않은데 자동 저장이 꺼진 월드가 남아 있으면 되돌린다.
     *
     * <p>메인 스레드 작업이 타임아웃된 직후 뒤늦게 실행되는 아주 좁은 경우를 막기 위한
     * 주기적 점검이다. 평소에는 맵이 비어 있어 비용이 없다.</p>
     */
    public void thawLeftovers() {
        if (frozenWorlds.isEmpty() || running.get()) return;
        forceThawOnMainThread();
    }

    // ------------------------------------------------------------------

    private record WorldRef(String name, Path folder) {
    }

    /** 백업 대상 월드들과 서버 루트를 후보로 삼아 플레이어 데이터를 찾는다. */
    private static List<Path> playerDataBases(List<WorldRef> worlds, Path serverRoot) {
        List<Path> bases = new ArrayList<>();
        for (WorldRef ref : worlds) bases.add(ref.folder());
        bases.add(serverRoot);
        return bases;
    }


    private BackupEntry execute(BackupType type, String label, CommandSender initiator) throws Exception {
        BackupSettings settings = plugin.settings();
        BackupRepository repo = plugin.repository();
        Path serverRoot = settings.serverRoot();
        long startedAt = System.currentTimeMillis();

        repo.ensureDirectory();

        Instant now = Instant.now();
        String id = BackupEntry.newId(now);
        Path archive = repo.directory().resolve(BackupEntry.archiveName(id));
        int dedupe = 1;
        while (Files.exists(archive)) {
            id = BackupEntry.newId(now) + "-" + (++dedupe);
            archive = repo.directory().resolve(BackupEntry.archiveName(id));
        }
        final String backupId = id;
        final Path archivePath = archive;

        // 차등 백업의 기준을 정한다. 기준이 없거나 주기가 찼으면 전체 백업으로 되돌아간다.
        BackupEntry baseEntry = settings.differential() ? chooseBase(settings, repo) : null;
        Manifest baseManifest = null;
        if (baseEntry != null) {
            baseManifest = Manifest.readFrom(baseEntry.archive()).orElse(null);
            if (baseManifest == null) {
                plugin.getLogger().warning("[백업] 기준 백업 " + baseEntry.id()
                        + " 에서 파일 목록을 읽지 못해 전체 백업으로 진행합니다.");
                baseEntry = null;
            }
        }
        final BackupEntry base = baseEntry;
        final Manifest baseFiles = baseManifest;
        final String baseBackupId = base == null ? null : base.id();

        if (settings.broadcast()) {
            broadcastSafe("<gray>" + type.korean() + " 백업을 시작합니다...</gray>", settings.broadcastPermission());
        } else {
            plugin.getLogger().info("[백업] " + type.korean() + " 백업 시작 (" + backupId + ")");
        }

        // 얼리는 작업부터 원복까지 통째로 감싼다. freezeWorlds 가 실패하거나 타임아웃 나더라도
        // finally 는 반드시 실행되고, 원복에 필요한 정보는 frozenWorlds 에 이미 들어 있다.
        try {
            // 기준 백업을 "사용 중"으로 못 박는다. 아래 ensureDiskSpace 가 공간을 확보하려고
            // 백업을 지울 수 있는데, 하필 이 기준을 지우면 지금 만드는 차등본은 태어나자마자
            // 복원 불가([손상])가 된다. 백업했다고 믿는 쪽이 더 위험하므로 어떤 경로로도 막는다.
            if (base != null) repo.pin(base.id());

            // 1) 메인 스레드: 플레이어/월드 저장 후 자동 저장을 잠시 끈다.
            List<WorldRef> frozen = callSync(() -> freezeWorlds(settings));

            // 2) 백업 대상 수집
            List<Path> targets = new ArrayList<>();
            List<String> worldNames = new ArrayList<>();
            for (WorldRef ref : frozen) {
                if (FileUtil.relativize(serverRoot, ref.folder()) == null) {
                    plugin.getLogger().warning("[백업] 월드 '" + ref.name() + "' 가 서버 폴더("
                            + serverRoot + ") 밖에 있어 백업할 수 없습니다: " + ref.folder());
                    continue;
                }
                targets.add(ref.folder());
                worldNames.add(ref.name());
            }

            // 플레이어 데이터는 위치를 전제하지 않고 찾아서 넣는다. 월드 폴더 안에 있으면
            // 아래 dedupeTargets 가 중복을 걸러 주므로 그냥 넣어도 안전하다.
            List<Path> playerDataBases = playerDataBases(frozen, serverRoot);
            PlayerData.Located playerData = PlayerData.search(serverRoot, playerDataBases);
            for (Path path : playerData.paths()) {
                if (FileUtil.relativize(serverRoot, path) == null) continue; // 서버 폴더 밖
                targets.add(path);
            }
            if (!playerData.inventory()) {
                PlayerData.warnMissing(plugin.getLogger(), serverRoot, playerDataBases);
            }

            for (String relative : settings.serverFiles()) {
                Path path = serverRoot.resolve(relative).normalize();
                if (Files.exists(path)) {
                    targets.add(path);
                } else {
                    plugin.getLogger().warning("[백업] server-files 파일이 없어 건너뜁니다: " + relative);
                }
            }
            for (String relative : settings.extraPaths()) {
                Path path = serverRoot.resolve(relative).normalize();
                if (Files.exists(path)) {
                    targets.add(path);
                } else {
                    plugin.getLogger().warning("[백업] extra-paths 경로를 찾을 수 없습니다: " + relative);
                }
            }
            if (targets.isEmpty()) {
                throw new IllegalStateException("백업할 대상이 없습니다. config.yml 의 targets 설정을 확인하세요.");
            }

            // 대상이 서로 겹치면 zip 엔트리가 중복되어 백업 전체가 실패한다. 상위 경로만 남긴다.
            List<Path> merged = FileUtil.dedupeTargets(targets);
            if (merged.size() != targets.size()) {
                for (Path dropped : targets) {
                    if (merged.contains(dropped)) continue;
                    plugin.getLogger().warning("[백업] 다른 백업 대상에 이미 포함되어 있어 건너뜁니다: "
                            + FileUtil.relativize(serverRoot, dropped));
                }
                targets = merged;
            }

            List<String> roots = new ArrayList<>();
            for (Path target : targets) {
                String relative = FileUtil.relativize(serverRoot, target);
                if (relative != null && !roots.contains(relative)) roots.add(relative);
            }

            // 3) 용량 확인 (제외 패턴을 반영해야 진행률과 예상 용량이 실제와 맞는다)
            //
            // 진행률은 스냅샷 전체 기준이지만, 디스크 여유는 "이번에 실제로 쓸 양" 으로 봐야 한다.
            // 차등 백업에 전체 크기를 들이대면 200MB 를 쓰면서 10GB 를 요구하게 되고,
            // ensureDiskSpace 가 그 공간을 만들겠다며 멀쩡한 백업을 지운 뒤 결국 실패한다.
            FileUtil.ChangeFilter changed = baseFiles == null ? null
                    : (relative, size, modified) -> !baseFiles.unchanged(relative, size, modified);

            FileUtil.Sizes sizes = FileUtil.Sizes.ZERO;
            for (Path target : targets) {
                sizes = sizes.plus(FileUtil.measure(target, serverRoot, settings.exclude(), changed));
            }
            long expectedBytes = sizes.totalBytes();
            ensureDiskSpace(settings, repo, sizes.changedBytes());

            // 4) 압축
            final List<String> finalWorlds = List.copyOf(worldNames);
            final List<String> finalRoots = List.copyOf(roots);
            final List<String> finalExcludes = settings.excludePatterns();
            final Instant created = now;
            final String cleanLabel = (label == null || label.isBlank()) ? null : label.trim();

            Archiver.Result result = Archiver.create(
                    archivePath,
                    serverRoot,
                    targets,
                    settings.compressionLevel(),
                    settings.exclude(),
                    baseManifest,
                    expectedBytes,
                    (fileCount, originalBytes) -> repo.toYamlString(new BackupEntry(
                            backupId, archivePath, created, type, cleanLabel,
                            0L, originalBytes, fileCount, finalRoots, finalWorlds, finalExcludes,
                            Bukkit.getBukkitVersion(), false, true, baseBackupId)),
                    text -> {
                        progressText = text;
                        plugin.getLogger().info("[백업] 진행률 " + text);
                    },
                    plugin.getLogger()
            );

            BackupEntry entry = new BackupEntry(backupId, archivePath, created, type, cleanLabel,
                    result.archiveBytes(), result.originalBytes(), result.fileCount(),
                    finalRoots, finalWorlds, finalExcludes, Bukkit.getBukkitVersion(), false, true, baseBackupId);
            repo.writeMeta(entry);
            lastBackup = entry;
            lastError = null;
            forceFullBackup.set(false);

            long elapsed = System.currentTimeMillis() - startedAt;
            double ratio = result.originalBytes() > 0
                    ? (100.0 * result.archiveBytes() / result.originalBytes()) : 100.0;

            String kind = base == null ? "" : " <gray>|</gray> <yellow>차등</yellow>";
            String summary = "<green>" + type.korean() + " 백업 완료</green> <gray>|</gray> <white>" + backupId + "</white>"
                    + kind
                    + " <gray>|</gray> <aqua>" + FileUtil.humanBytes(result.archiveBytes()) + "</aqua>"
                    + " <gray>(원본 " + FileUtil.humanBytes(result.originalBytes())
                    + ", " + String.format(java.util.Locale.ROOT, "%.0f", ratio) + "%)</gray>"
                    + " <gray>|</gray> <white>" + FileUtil.humanMillis(elapsed) + "</white>";
            if (settings.broadcast()) {
                broadcastSafe(summary, settings.broadcastPermission());
            } else {
                plugin.getLogger().info("[백업] 완료: " + backupId + " (" + FileUtil.humanBytes(result.archiveBytes())
                        + ", " + FileUtil.humanMillis(elapsed) + ")");
            }
            if (base != null) {
                int reused = result.fileCount() - result.storedCount();
                plugin.getLogger().info("[백업] 차등 백업 (기준 " + base.id() + ") - 저장 "
                        + result.storedCount() + "개 " + FileUtil.humanBytes(result.storedBytes())
                        + " / 재사용 " + reused + "개");
            }
            if (result.skippedCount() > 0) {
                plugin.getLogger().warning("[백업] 읽지 못해 건너뛴 파일 " + result.skippedCount() + "개");
            }
            if (initiator != null) {
                plugin.getLogger().info("[백업] 요청자: " + initiator.getName());
            }

            repo.prune(settings);
            return entry;

        } catch (Throwable t) {
            // 얼릴 때 내려 둔 변경 플래그를 되돌린다. 이 백업은 아무것도 담지 못했으므로
            // 그대로 두면 다음 주기까지 "변경 없음"으로 건너뛰어 공백이 길어진다.
            worldChanged.set(true);
            // 실패한 zip 조각은 남기지 않는다. (Archiver 가 임시 파일을 지우지만 이중으로 확인한다)
            try {
                Files.deleteIfExists(archivePath);
            } catch (IOException ignored) {
            }
            plugin.getLogger().log(Level.SEVERE, "[백업] 실패: " + t.getMessage(), t);
            if (settings.broadcast()) {
                broadcastSafe("<red>백업에 실패했습니다. 콘솔 로그를 확인하세요.</red>", settings.broadcastPermission());
            }
            throw t;
        } finally {
            // 5) 기준 백업 고정 해제 + 메인 스레드에서 자동 저장 원복
            repo.unpin();
            thawWorlds();
        }
    }

    /**
     * 이번 차등 백업의 기준으로 쓸 전체 백업을 고른다.
     *
     * <p>기준이 없거나, 딸린 차등 백업이 {@code full-every} 개를 채웠거나, 복원 직후라
     * 기준을 새로 잡아야 하면 null 을 돌려 전체 백업으로 진행한다.</p>
     */
    private BackupEntry chooseBase(BackupSettings settings, BackupRepository repo) {
        if (forceFullBackup.get()) {
            plugin.getLogger().info("[백업] 기준을 새로 잡기 위해 이번에는 전체 백업을 만듭니다.");
            return null;
        }
        List<BackupEntry> all = repo.list();
        BackupEntry base = all.stream()
                .filter(BackupEntry::complete)
                .filter(entry -> !entry.isDifferential())
                .findFirst()
                .orElse(null);
        if (base == null) {
            plugin.getLogger().info("[백업] 기준이 될 전체 백업이 없어 이번에는 전체 백업을 만듭니다.");
            return null;
        }
        if (settings.fullEvery() > 0 && repo.dependents(all, base.id()).size() >= settings.fullEvery()) {
            plugin.getLogger().info("[백업] 차등 백업이 " + settings.fullEvery()
                    + "개를 채워 전체 백업을 새로 만듭니다.");
            return null;
        }
        return base;
    }

    /**
     * 월드를 저장하고 자동 저장을 끈다. <b>반드시 메인 스레드에서 실행된다.</b>
     * 원래 값은 반환값이 아니라 {@link #frozenWorlds} 에 즉시 기록한다.
     */
    private List<WorldRef> freezeWorlds(BackupSettings settings) {
        // 스냅샷 경계는 바로 여기다. 이 시점 이후의 변경은 다음 백업이 담당하므로 플래그를 내린다.
        // 압축이 끝난 뒤에 내리면, 압축 중(대형 월드에서는 수 분)에 생긴 변경이 "변경 없음"으로
        // 묻혀 다음 주기가 통째로 건너뛰어진다. 접속자가 있으면 계속 변하는 중이므로 유지한다.
        worldChanged.set(!Bukkit.getOnlinePlayers().isEmpty());
        Bukkit.savePlayers();
        List<WorldRef> refs = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (!settings.includesWorld(world.getName())) continue;
            frozenWorlds.putIfAbsent(world.getName(), world.isAutoSave());
            world.setAutoSave(false);
            try {
                world.save();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[백업] 월드 저장 실패: " + world.getName(), e);
            }
            refs.add(new WorldRef(world.getName(), world.getWorldFolder().toPath().toAbsolutePath().normalize()));
        }
        return refs;
    }

    /** 자동 저장을 원래대로 되돌린다. 어떤 경로로 실패하든 반드시 시도한다. */
    private void thawWorlds() {
        Map<String, Boolean> snapshot = drainFrozen();
        if (snapshot.isEmpty()) return;
        try {
            callSync(() -> {
                restoreAutoSave(snapshot);
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "[백업] 월드 자동 저장 복구가 지연되고 있습니다. 스케줄러로 재시도합니다.", e);
            // 마지막 방어선: 결과를 기다리지 않고 메인 스레드에 밀어 넣는다.
            // 이마저 실패해도 주기적인 thawLeftovers() 점검이 다시 시도한다.
            frozenWorlds.putAll(snapshot);
            Sched.syncQuietly(plugin, () -> restoreAutoSave(drainFrozen()));
        }
    }

    private Map<String, Boolean> drainFrozen() {
        if (frozenWorlds.isEmpty()) return Map.of();
        Map<String, Boolean> snapshot = new LinkedHashMap<>(frozenWorlds);
        frozenWorlds.clear();
        return snapshot;
    }

    private void restoreAutoSave(Map<String, Boolean> snapshot) {
        for (Map.Entry<String, Boolean> saved : snapshot.entrySet()) {
            World world = Bukkit.getWorld(saved.getKey());
            if (world == null) continue;
            if (world.isAutoSave() != saved.getValue()) {
                world.setAutoSave(saved.getValue());
            }
        }
    }

    /** @param plannedBytes 이번 아카이브에 실제로 저장할 원본 바이트 (차등 백업이면 바뀐 파일만) */
    private void ensureDiskSpace(BackupSettings settings, BackupRepository repo, long plannedBytes) {
        long estimatedArchive = Math.max(1L, (long) (plannedBytes * 0.6)); // 압축률을 보수적으로 가정
        long required = estimatedArchive + settings.minFreeDiskBytes();
        long free = FileUtil.usableSpace(repo.directory());
        if (free >= required) return;

        plugin.getLogger().warning("[백업] 디스크 여유 공간 부족 (" + FileUtil.humanBytes(free)
                + " < " + FileUtil.humanBytes(required) + "). 오래된 백업을 정리합니다.");
        repo.prune(settings);
        repo.freeUpSpace(settings, required - free);

        free = FileUtil.usableSpace(repo.directory());
        if (free < required) {
            throw new IllegalStateException("디스크 여유 공간이 부족합니다. 필요: "
                    + FileUtil.humanBytes(required) + ", 남음: " + FileUtil.humanBytes(free));
        }
    }

    /** 온라인 플레이어 목록 접근은 메인 스레드에서 하도록 보장한다. */
    private void broadcastSafe(String message, String permission) {
        runSyncQuietly(() -> Msg.broadcast(message, permission));
    }

    /** 결과가 필요 없는 메인 스레드 작업. 서버가 종료 중이면 조용히 포기한다. */
    private void runSyncQuietly(Runnable task) {
        Sched.syncQuietly(plugin, task);
    }

    private <T> T callSync(Callable<T> callable) throws Exception {
        return Sched.callSync(plugin, callable, SYNC_TIMEOUT_SECONDS);
    }
}
