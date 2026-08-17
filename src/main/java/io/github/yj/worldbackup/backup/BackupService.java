package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.util.FileUtil;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/** 백업 실행을 담당한다. 파일 압축은 비동기, 월드 저장은 메인 스레드에서 처리한다. */
public final class BackupService {

    /** 메인 스레드 작업을 기다리는 최대 시간. */
    private static final long SYNC_TIMEOUT_SECONDS = 120L;

    private final ServerBridge server;
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

    /**
     * 변경이 없다고 보고 연속으로 건너뛴 자동 백업 주기 수.
     *
     * <p>{@code max-skipped-cycles} 의 하한을 재기 위한 값이다. 백업이 실제로 만들어지면
     * 종류와 상관없이 0으로 돌아간다 - 관리자가 {@code /wb backup} 을 한 번 쳤다면 그 시점의
     * 스냅샷이 이미 남았으므로 강제로 하나 더 뜰 이유가 없다.</p>
     */
    private final AtomicInteger skippedCycles = new AtomicInteger();

    public BackupService(ServerBridge server) {
        this.server = server;
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

    /**
     * 자동 백업을 건너뛰어도 되는 상황인지. (메인 스레드에서 호출)
     *
     * <p>건너뛰기로 판단하면 그 사실을 세어 둔다. {@code max-skipped-cycles} 를 채우면
     * 변경이 없어 보여도 한 번은 뜬다 - "변경 없음" 은 블록 설치·파괴·접속만 보고 내리는
     * 판단이라, 강제 로드된 청크의 농장이나 플러그인이 직접 쓰는 데이터는 잡히지 않는다.
     * 하한이 없으면 무인 기간의 백업이 통째로 비고, 하필 그 사이에 서버가 죽으면 되돌릴
     * 지점이 없다.</p>
     */
    public boolean shouldSkipScheduled() {
        BackupSettings settings = server.settings();
        if (!settings.skipIfNoPlayers()) return false;
        if (server.hasOnlinePlayers()) return false;
        if (worldChanged.get()) return false;

        int limit = settings.maxSkippedCycles();
        if (limit <= 0) return true;

        int skipped = skippedCycles.incrementAndGet();
        if (skipped < limit) return true;

        // "건너뛰었다" 고 하면 안 된다. 이 값은 판단 대상인 이번 주기까지 센 것이라, 실제로
        // 건너뛴 주기는 하나 적다. 백업이 limit 주기마다 떨어진다는 동작은 맞지만 (그래서
        // config.yml 의 "48 = 하루에 한 번" 도 맞다) 문장은 사실대로 적는다.
        server.logger().info("[백업] 변경이 없는 주기가 " + skipped
                + "번 이어졌습니다. 이번 주기는 백업합니다.");
        skippedCycles.set(0);
        return false;
    }

    /** 비동기 백업 시작. 이미 진행 중이면 실패한 future 를 반환한다. */
    public CompletableFuture<BackupEntry> startAsync(BackupType type, String label, CommandSender initiator) {
        CompletableFuture<BackupEntry> future = new CompletableFuture<>();
        if (!running.compareAndSet(false, true)) {
            future.completeExceptionally(new IllegalStateException("이미 백업이 진행 중입니다."));
            return future;
        }
        try {
            server.runAsync(() -> {
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
        server.logger().warning("[백업] 백업이 끝나지 않아 월드 자동 저장을 강제로 복구합니다.");
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

    /** 플레이어 데이터를 찾아볼 기준. 차원 폴더가 아니라 <b>월드 폴더</b>여야 한다. */
    private static List<Path> playerDataBases(List<WorldRef> worlds, Path serverRoot) {
        List<Path> bases = new ArrayList<>();
        for (WorldRef ref : worlds) {
            Path levelRoot = WorldLayout.levelRoot(ref.folder(), serverRoot);
            if (!bases.contains(levelRoot)) bases.add(levelRoot);
        }
        bases.add(serverRoot);
        return bases;
    }


    private BackupEntry execute(BackupType type, String label, CommandSender initiator) throws Exception {
        BackupSettings settings = server.settings();
        BackupRepository repo = server.repository();
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
                server.logger().warning("[백업] 기준 백업 " + baseEntry.id()
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
            server.logger().info("[백업] " + type.korean() + " 백업 시작 (" + backupId + ")");
        }

        // 아카이브가 최종 이름을 받았는지. 이게 true 가 된 뒤로는 실패해도 파일을 지우지 않는다.
        // 압축은 끝났는데 뒷정리에서 예외가 나면, 멀쩡히 만들어진 백업을 스스로 지우게 된다.
        boolean archived = false;

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
                // 26.2 는 차원 폴더(world/dimensions/minecraft/overworld)를 돌려준다.
                // 거기만 담으면 지형은 되돌아오는데 playerdata, level.dat, data 가 빠진다.
                Path levelRoot = WorldLayout.levelRoot(ref.folder(), serverRoot);
                if (FileUtil.relativize(serverRoot, levelRoot) == null) {
                    server.logger().warning("[백업] 월드 '" + ref.name() + "' 가 서버 폴더("
                            + serverRoot + ") 밖에 있어 백업할 수 없습니다: " + levelRoot);
                    continue;
                }
                if (!levelRoot.equals(ref.folder())) {
                    server.logger().info("[백업] 월드 '" + ref.name() + "' 는 차원 폴더라 실제 월드 폴더로 올라갑니다: "
                            + FileUtil.relativize(serverRoot, levelRoot));
                }
                targets.add(levelRoot);
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
                PlayerData.warnMissing(server.logger(), serverRoot, playerDataBases);
            }
            // 이 백업으로 인벤토리를 되돌릴 수 있는지 메타데이터에 남긴다. 복원할 때가 되어서야
            // 알게 되면 늦는다. 목록과 복원 확인창에서 미리 보여 줄 수 있어야 한다.
            final boolean hasPlayerData = playerData.inventory();

            for (String relative : settings.serverFiles()) {
                Path path = serverRoot.resolve(relative).normalize();
                if (Files.exists(path)) {
                    targets.add(path);
                } else {
                    server.logger().warning("[백업] server-files 파일이 없어 건너뜁니다: " + relative);
                }
            }
            for (String relative : settings.extraPaths()) {
                Path path = serverRoot.resolve(relative).normalize();
                if (Files.exists(path)) {
                    targets.add(path);
                } else {
                    server.logger().warning("[백업] extra-paths 경로를 찾을 수 없습니다: " + relative);
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
                    server.logger().warning("[백업] 다른 백업 대상에 이미 포함되어 있어 건너뜁니다: "
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
            //
            // 이 훑기는 트리를 통째로 한 번 더 돈다 - 파일마다 stat 을 하므로 캐시가 식어 있으면
            // 압축만큼 걸릴 수도 있다. 그런데 공간이 넉넉하다는 것을 이미 알 수 있다면 이 훑기의
            // 두 결과(디스크 판정·진행률 분모) 모두 필요하지 않다. 기준 백업의 스냅샷 크기가
            // 그 답을 들고 있다.
            long expectedBytes;
            if (base != null && hasAmpleRoom(settings, repo, base)) {
                // 분모는 기준 백업의 스냅샷 크기로 어림잡는다. 진행률은 99%에서 멈추도록
                // 되어 있으므로 조금 틀려도 문제가 없다.
                expectedBytes = base.originalBytes();
            } else {
                FileUtil.ChangeFilter changed = baseFiles == null ? null
                        : (relative, size, modified) -> !baseFiles.unchanged(relative, size, modified);

                FileUtil.Sizes sizes = FileUtil.Sizes.ZERO;
                for (Path target : targets) {
                    sizes = sizes.plus(FileUtil.measure(target, serverRoot, settings.exclude(), changed));
                }
                expectedBytes = sizes.totalBytes();
                ensureDiskSpace(settings, repo, sizes.changedBytes());
            }

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
                            server.serverVersion(), false, true, baseBackupId, hasPlayerData)),
                    text -> {
                        progressText = text;
                        server.logger().info("[백업] 진행률 " + text);
                    },
                    server.logger()
            );
            // 여기까지 왔으면 zip 은 정상적으로 닫히고 최종 이름까지 받았다.
            archived = true;

            BackupEntry entry = new BackupEntry(backupId, archivePath, created, type, cleanLabel,
                    result.archiveBytes(), result.originalBytes(), result.fileCount(),
                    finalRoots, finalWorlds, finalExcludes, server.serverVersion(),
                    false, true, baseBackupId, hasPlayerData);
            repo.writeMeta(entry);
            lastBackup = entry;
            lastError = null;
            forceFullBackup.set(false);
            // 종류를 가리지 않는다. 수동 백업이든 시작 백업이든 이 시점의 스냅샷이 남았으므로
            // 강제로 하나 더 뜰 이유가 없다.
            skippedCycles.set(0);

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
                server.logger().info("[백업] 완료: " + backupId + " (" + FileUtil.humanBytes(result.archiveBytes())
                        + ", " + FileUtil.humanMillis(elapsed) + ")");
            }
            if (base != null) {
                int reused = result.fileCount() - result.storedCount();
                server.logger().info("[백업] 차등 백업 (기준 " + base.id() + ") - 저장 "
                        + result.storedCount() + "개 " + FileUtil.humanBytes(result.storedBytes())
                        + " / 재사용 " + reused + "개");
            }
            if (result.plainBytes() > 0) {
                // region 파일처럼 이미 압축된 것을 다시 누르지 않았다는 뜻이다. 압축 레벨을
                // 올려도 백업이 더 작아지지 않는 이유가 여기 있으므로 눈에 보이게 남긴다.
                server.logger().info("[백업] 이미 압축된 파일 "
                        + FileUtil.humanBytes(result.plainBytes()) + " 는 재압축하지 않았습니다.");
            }
            if (result.skippedCount() > 0) {
                server.logger().warning("[백업] 읽지 못해 건너뛴 파일 " + result.skippedCount() + "개");
            }
            if (initiator != null) {
                server.logger().info("[백업] 요청자: " + initiator.getName());
            }

            pruneAfterBackup(settings, repo);
            return entry;

        } catch (Throwable t) {
            if (!archived) {
                // 얼릴 때 내려 둔 변경 플래그를 되돌린다. 이 백업은 아무것도 담지 못했으므로
                // 그대로 두면 다음 주기까지 "변경 없음"으로 건너뛰어 공백이 길어진다.
                worldChanged.set(true);
                // 실패한 zip 조각은 남기지 않는다. (Archiver 가 임시 파일을 지우지만 이중으로 확인한다)
                try {
                    Files.deleteIfExists(archivePath);
                } catch (IOException ignored) {
                }
            }
            server.logger().log(Level.SEVERE, "[백업] 실패: " + t.getMessage(), t);
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
     * 백업이 성공한 뒤 보관 정책을 적용한다.
     *
     * <p>두 가지를 지킨다.</p>
     * <ul>
     *   <li>복원 실패 정지 중에는 <b>돌지 않는다.</b> 그 정지가 존재하는 이유가 "반쯤 복원된
     *       월드가 백업되면서 멀쩡한 예전 백업이 정책에 밀려 사라지는 것" 인데, 관리자가 상황을
     *       보려고 {@code /wb backup} 을 한 번 치면 바로 그 일이 일어났다. 수동 백업은 계속
     *       쓸 수 있게 두되, 뒤따르는 정리만 멈춘다.</li>
     *   <li>여기서 난 예외가 <b>백업 자체를 실패로 만들지 않는다.</b> 압축은 이미 끝났고 파일도
     *       제자리에 있다. 정리에 실패했다고 성공한 백업을 실패로 보고하면(그리고 예전에는
     *       그 파일을 지우기까지 했다) 결과가 실제와 어긋난다.</li>
     * </ul>
     */
    private void pruneAfterBackup(BackupSettings settings, BackupRepository repo) {
        if (server.restoreFailureHold()) {
            server.logger().warning("[백업] 복원 실패 기록이 있어 이번 백업 뒤 보관 정리는 건너뜁니다. "
                    + "(월드를 확인한 뒤 표식을 지우고 /wb reload 하세요)");
            return;
        }
        try {
            repo.prune(settings);
        } catch (Exception e) {
            server.logger().log(Level.WARNING, "[백업] 백업은 끝났지만 보관 정리에 실패했습니다.", e);
        }
    }

    /**
     * 압축 후 크기를 어림잡는 비율. 디스크 여유 판단에만 쓰므로 보수적으로 잡는다.
     *
     * <p>레벨 0 은 무압축이라 원본 크기 그대로 쓰인다. 여기에 0.6 을 곱하면 필요한 공간을
     * 40% 적게 잡아, 정작 공간이 모자란 상황을 통과시켜 놓고 압축 중에 실패하게 된다.</p>
     */
    private static double compressionFactor(BackupSettings settings) {
        return settings.compressionLevel() == 0 ? 1.0 : 0.6;
    }

    /**
     * 이번 차등 백업의 기준으로 쓸 전체 백업을 고른다.
     *
     * <p>기준이 없거나, 딸린 차등 백업이 {@code full-every} 개를 채웠거나, 복원 직후라
     * 기준을 새로 잡아야 하면 null 을 돌려 전체 백업으로 진행한다.</p>
     */
    private BackupEntry chooseBase(BackupSettings settings, BackupRepository repo) {
        if (forceFullBackup.get()) {
            server.logger().info("[백업] 기준을 새로 잡기 위해 이번에는 전체 백업을 만듭니다.");
            return null;
        }
        List<BackupEntry> all = repo.list();
        BackupEntry base = all.stream()
                .filter(BackupEntry::complete)
                .filter(entry -> !entry.isDifferential())
                .findFirst()
                .orElse(null);
        if (base == null) {
            server.logger().info("[백업] 기준이 될 전체 백업이 없어 이번에는 전체 백업을 만듭니다.");
            return null;
        }
        if (settings.fullEvery() > 0 && repo.dependents(all, base.id()).size() >= settings.fullEvery()) {
            server.logger().info("[백업] 차등 백업이 " + settings.fullEvery()
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
        worldChanged.set(server.hasOnlinePlayers());
        server.savePlayers();

        List<WorldRef> refs = new ArrayList<>();
        long flushStart = System.currentTimeMillis();
        for (ServerBridge.WorldHandle world : server.worlds()) {
            if (!settings.includesWorld(world.name())) continue;
            // 원래 값을 먼저 적어 둔다. 아래 저장이 예외로 끝나도 원복할 재료가 남아야 한다.
            frozenWorlds.putIfAbsent(world.name(), world.autoSave());
            world.autoSave(false);
            try {
                // 청크 쓰기가 디스크에 끝날 때까지 기다린다. 기다리지 않으면 서버가 아직 쓰고
                // 있는 region 파일을 압축하게 되어, 헤더와 데이터가 어긋난 조각이 백업에 담긴다.
                // 복원해 보면 "Corrupt regionfile header" 가 뜨고 복구되지 않은 청크는 통째로
                // 재생성된다. 압축이 시작되기 전에 디스크 쓰기가 끝나야 한다.
                world.saveNow();
            } catch (Exception e) {
                server.logger().log(Level.WARNING, "[백업] 월드 저장 실패: " + world.name(), e);
            }
            refs.add(new WorldRef(world.name(), world.folder()));
        }

        long flushed = System.currentTimeMillis() - flushStart;
        if (flushed > 1000L) {
            // 이 시간만큼 서버가 멈춘다. 관리자가 원인을 알 수 있게 남긴다.
            server.logger().info("[백업] 청크 저장이 끝나기를 기다렸습니다: " + FileUtil.humanMillis(flushed));
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
            server.logger().log(Level.SEVERE,
                    "[백업] 월드 자동 저장 복구가 지연되고 있습니다. 스케줄러로 재시도합니다.", e);
            // 마지막 방어선: 결과를 기다리지 않고 메인 스레드에 밀어 넣는다.
            // 이마저 실패해도 주기적인 thawLeftovers() 점검이 다시 시도한다.
            frozenWorlds.putAll(snapshot);
            server.runSyncQuietly(() -> restoreAutoSave(drainFrozen()));
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
            ServerBridge.WorldHandle world = server.world(saved.getKey()).orElse(null);
            if (world == null) continue; // 그 사이 언로드됐다
            if (world.autoSave() != saved.getValue()) {
                world.autoSave(saved.getValue());
            }
        }
    }

    /**
     * 월드가 이만큼 커져 있을 수도 있다고 보고 잡는 여유. 측정을 건너뛸지 판단할 때만 쓴다.
     *
     * <p>기준 백업 이후 월드가 자랐을 수 있으므로 그 크기를 그대로 믿지 않는다. 절반이 더
     * 붙었다고 봐도 공간이 남는다면, 이번 백업이 공간 때문에 실패할 길은 없다.</p>
     */
    private static final double SNAPSHOT_GROWTH_MARGIN = 1.5;

    /**
     * 대상을 훑어 보지 <b>않고도</b> 공간이 넉넉하다고 단정할 수 있는지.
     *
     * <p>차등 백업이 쓰는 양은 아무리 많아도 스냅샷 전체를 넘지 못한다. 그 상한을 기준 백업의
     * {@code original-bytes} 에서 가져와, 넉넉히 잡은 최악의 경우에도 여유가 남으면 참을 돌려준다.
     * 그러면 호출자는 용량 측정 순회를 통째로 건너뛴다.</p>
     *
     * <p>거짓을 돌려주면 예전처럼 정확히 재고 필요하면 공간을 만든다. 즉 이 판단이 보수적으로
     * 틀리는 방향은 "조금 느려질 뿐" 이다. 반대로 참을 돌려주려면 실제로 여유가 있어야 하므로
     * 상한을 크게 잡는다.</p>
     */
    private boolean hasAmpleRoom(BackupSettings settings, BackupRepository repo, BackupEntry base) {
        return hasAmpleRoom(base.originalBytes(), compressionFactor(settings),
                settings.minFreeDiskBytes(), FileUtil.usableSpace(repo.directory()));
    }

    /**
     * 위 판단의 규칙만 떼어 낸 것. 디스크를 만지지 않으므로 경계를 그대로 검증할 수 있다.
     *
     * <p>{@code public} 인 이유는 하나뿐이다 - 이 규칙이 <b>잘못 참을 내면</b> 디스크가 빠듯한
     * 서버가 공간 확보도 없이 백업을 시작해 실패하므로, 경계를 테스트로 못 박아 두어야 한다.</p>
     *
     * @param snapshotBytes 기준 백업의 스냅샷 크기. 0 이하면 기록이 없는 옛 백업이다.
     */
    public static boolean hasAmpleRoom(long snapshotBytes, double compressionFactor,
                                       long minFreeBytes, long usableBytes) {
        if (snapshotBytes <= 0) return false; // 옛 백업이라 기록이 없다 - 재는 수밖에 없다

        long worstCase = (long) (snapshotBytes * SNAPSHOT_GROWTH_MARGIN * compressionFactor);
        long required = worstCase + minFreeBytes;
        if (required < 0) return false; // 설정이 터무니없이 커서 넘쳤다 - 정확히 재게 한다
        return usableBytes >= required;
    }

    /** @param plannedBytes 이번 아카이브에 실제로 저장할 원본 바이트 (차등 백업이면 바뀐 파일만) */
    private void ensureDiskSpace(BackupSettings settings, BackupRepository repo, long plannedBytes) {
        long estimatedArchive = Math.max(1L, (long) (plannedBytes * compressionFactor(settings)));
        long required = estimatedArchive + settings.minFreeDiskBytes();
        long free = FileUtil.usableSpace(repo.directory());
        if (free >= required) return;

        // 복원 실패 정지 중에는 공간을 만들겠다고 옛 백업을 지우면 안 된다. 그 백업들이
        // 반쯤 복원된 월드를 되돌릴 유일한 재료다. 이번 백업을 포기하는 쪽이 훨씬 싸다.
        if (server.restoreFailureHold()) {
            throw new IllegalStateException("디스크 여유 공간이 부족합니다. 필요: "
                    + FileUtil.humanBytes(required) + ", 남음: " + FileUtil.humanBytes(free)
                    + " (복원 실패 기록이 있어 오래된 백업을 지우지 않습니다)");
        }

        server.logger().warning("[백업] 디스크 여유 공간 부족 (" + FileUtil.humanBytes(free)
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
        server.runSyncQuietly(() -> server.broadcast(message, permission));
    }

    private <T> T callSync(Callable<T> callable) throws Exception {
        return server.callSync(callable, SYNC_TIMEOUT_SECONDS);
    }
}
