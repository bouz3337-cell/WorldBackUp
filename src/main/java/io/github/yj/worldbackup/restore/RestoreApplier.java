package io.github.yj.worldbackup.restore;

import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.Manifest;
import io.github.yj.worldbackup.util.FileUtil;
import io.github.yj.worldbackup.util.GlobMatcher;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 예약된 복원을 실제로 적용한다.
 *
 * <p><b>반드시 월드가 로드되기 전(플러그인 onLoad)에 호출해야 한다.</b>
 * 월드가 로드된 뒤에는 region 파일이 열려 있어 교체할 수 없다.</p>
 */
public final class RestoreApplier {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /**
     * 사람이 확인해야 하는 복원 실패 기록의 파일 이름 앞부분.
     *
     * <p>이 파일이 남아 있는 동안 플러그인은 자동 백업과 보관 정리를 하지 않는다.
     * 반쯤 복원된 월드를 계속 백업하다 보면 멀쩡한 예전 백업이 정책에 밀려 사라지기 때문이다.</p>
     */
    public static final String FAILURE_PREFIX = "restore-failed-";

    /**
     * 복원이 밀어낸 옛 파일을 모아 두는 폴더 이름.
     *
     * <p>여기에는 월드가 몇 벌씩 쌓인다. 백업 제외 패턴이 이 이름을 알아야 하므로
     * ({@link io.github.yj.worldbackup.config.BackupSettings}) 상수로 둔다 - 문자열을
     * 양쪽에 따로 적으면 한쪽만 바뀌었을 때 백업이 자기 잔재를 삼킨다.</p>
     */
    public static final String REPLACED_FOLDER = "replaced";

    /** 복원이 성공하면 옛 실패 기록에 이 꼬리표를 붙여 둔다. 기록은 남기되 정지는 풀기 위함이다. */
    private static final String RESOLVED_SUFFIX = ".resolved";

    private RestoreApplier() {
    }

    /** 아직 처리되지 않은 복원 실패 기록들. 비어 있지 않으면 자동 작업을 멈춰야 한다. */
    public static List<Path> failureMarkers(Path dataFolder) {
        if (!Files.isDirectory(dataFolder)) return List.of();
        try (Stream<Path> stream = Files.list(dataFolder)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(FAILURE_PREFIX) && name.endsWith(".yml");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static final class Stats {
        int removed;
        int preserved;
        int restored;
        int failed;
        long restoredBytes;
    }

    public static void applyIfPending(Path dataFolder, Path serverRoot, Logger log) {
        Path marker = PendingRestore.file(dataFolder);
        Path processing = PendingRestore.processingFile(dataFolder);

        if (Files.exists(processing)) {
            // 직전 복원이 중간에 끊겼다. 같은 작업을 반복하면 더 위험하므로 중단한다.
            Path failed = dataFolder.resolve(FAILURE_PREFIX + STAMP.format(Instant.now()) + ".yml");
            try {
                Files.move(processing, failed, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            log.severe("==================================================================");
            log.severe("[WorldBackUp] 이전 복원 작업이 정상적으로 끝나지 않았습니다.");
            log.severe("[WorldBackUp] 서버를 끄고 " + failed.getFileName() + " 와 월드 폴더를 확인하세요.");
            log.severe("==================================================================");
            PendingRestore.clear(dataFolder);
            return;
        }

        if (!Files.isRegularFile(marker)) return;

        try {
            Files.move(marker, processing, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.log(Level.SEVERE, "[WorldBackUp] 복원 예약 파일을 처리하지 못했습니다.", e);
            return;
        }

        PendingRestore pending = PendingRestore.read(processing).orElse(null);
        if (pending == null) {
            log.severe("[WorldBackUp] 복원 예약 파일이 손상되어 복원을 취소합니다.");
            deleteQuietly(processing);
            return;
        }

        long startedAt = System.currentTimeMillis();
        Stats stats = new Stats();
        String error = null;

        log.info("==================================================================");
        log.info("[WorldBackUp] 복원을 시작합니다 : " + pending.id());
        log.info("[WorldBackUp] 요청자          : " + pending.requestedBy());
        log.info("[WorldBackUp] 백업 파일       : " + pending.archive());
        log.info("==================================================================");

        try {
            if (!Files.isRegularFile(pending.archive())) {
                throw new IOException("백업 파일을 찾을 수 없습니다: " + pending.archive());
            }
            if (pending.baseArchive() != null && !Files.isRegularFile(pending.baseArchive())) {
                throw new IOException("차등 백업의 기준 파일을 찾을 수 없습니다: " + pending.baseArchive());
            }

            GlobMatcher preserve = new GlobMatcher(pending.preserve());
            List<String> roots = pending.roots();

            // 이 아카이브에 무엇이 들었는지는 zip 의 엔트리 목록이 아니라 <b>매니페스트</b>가
            // 정한다. 매니페스트에는 끝까지 담아낸 파일만 적히기 때문이다.
            //
            // zip 엔트리는 한 번 쓰면 되돌릴 수 없어서, 백업 중 파일을 읽다가 I/O 오류로
            // 끊기면 잘린 엔트리가 아카이브에 남는다. 그걸 그대로 풀면 멀쩡했던 파일이
            // 깨진 파일로 덮어써진다. (차등 백업이면 이 목록이 "그 시점의 전체 파일" 이기도
            // 해서, 목록에 있는데 차등 zip 에 없는 파일은 기준 백업에서 꺼내 온다.)
            Manifest manifest = Manifest.readFrom(pending.archive()).orElse(null);
            if (pending.baseArchive() != null && manifest == null) {
                throw new IOException("차등 백업에 파일 목록이 없어 복원할 수 없습니다.");
            }

            if (manifest != null) {
                warnAboutUnstored(manifest, pending.baseArchive() != null, log);
            }

            // 백업이 실제로 데이터를 갖고 있는 경로만 교체 대상으로 남긴다. 내용이 없는 경로를
            // 비우면 복원되지 않고 사라지기만 하기 때문이다. 반대로 그 하나 때문에 복원 전체를
            // 거부하면, 백업 시점에 비어 있던 extra-paths 폴더 하나가 월드 복원까지 막는다.
            if (!roots.isEmpty()) {
                roots = coveredRoots(pending.archive(), manifest, roots, log);
            }

            // 기존 데이터를 지우기 전에 아카이브가 멀쩡한지부터 확인한다.
            // 여기서 실패하면 아무것도 건드리지 않은 상태로 중단되므로 월드는 그대로 남는다.
            if (pending.verifyArchive()) {
                verify(pending.archive(), pending.baseArchive(), manifest, log);
            }

            // 공간도 지우기 전에 본다. 복원은 "비우고 푸는" 순서라, 중간에 공간이 떨어지면
            // 반쯤 복원된 월드가 남는다. 백업에는 min-free-disk-gb 브레이크가 있는데 정작
            // 위험이 큰 복원에는 없었다.
            ensureRoomToRestore(dataFolder, serverRoot, pending, manifest, roots, preserve, log);

            Path replacedDir = null;
            if (pending.keepReplaced()) {
                replacedDir = dataFolder.resolve(REPLACED_FOLDER).resolve(STAMP.format(Instant.now()));
                Files.createDirectories(replacedDir);
                log.info("[WorldBackUp] 교체되는 기존 파일은 " + replacedDir + " 로 옮깁니다.");
            }

            if (roots.isEmpty()) {
                log.warning("[WorldBackUp] 백업 메타데이터에 대상 경로 정보가 없어, 기존 파일을 지우지 않고 "
                        + "덮어쓰기만 수행합니다.");
            } else {
                for (String root : roots) {
                    Path target = serverRoot.resolve(root).normalize();
                    if (!target.startsWith(serverRoot)) {
                        log.warning("[WorldBackUp] 서버 폴더 밖의 대상은 건너뜁니다: " + root);
                        continue;
                    }
                    if (!Files.exists(target)) continue;
                    log.info("[WorldBackUp] 기존 데이터 정리: " + root);
                    if (Files.isDirectory(target)) {
                        clearDirectory(target, serverRoot, preserve, replacedDir, stats, log);
                    } else {
                        removeFile(target, serverRoot, preserve, replacedDir, stats, log);
                    }
                }
            }

            // 차등 백업은 "기준 -> 차등" 순서로 푼다. 두 아카이브가 겹치는 파일은 없지만,
            // 기준에서 꺼내는 건 매니페스트에 살아 있는 파일로만 제한한다.
            // (기준 이후 삭제된 파일이 되살아나면 안 된다)
            if (pending.baseArchive() != null) {
                // 차등 zip 이 <b>온전히</b> 담고 있는 파일만 "여기서 꺼낼 것" 으로 센다.
                // 잘린 엔트리는 여기서 빠지므로 기준 백업에 있던 예전 판이라도 꺼내 온다.
                // 깨진 파일보다, 그리고 없는 파일보다 조금 낡은 파일이 낫다.
                Set<String> fromDiff = new HashSet<>(dataEntryNames(pending.archive()));
                fromDiff.retainAll(manifest.storedPaths());
                extract(pending.baseArchive(), serverRoot, roots, preserve, stats, log,
                        name -> manifest.contains(name) && !fromDiff.contains(name));
            }
            extract(pending.archive(), serverRoot, roots, preserve, stats, log, restorable(manifest));

        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            log.log(Level.SEVERE, "[WorldBackUp] 복원 중 오류가 발생했습니다.", t);
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        log.info("==================================================================");
        if (error == null) {
            log.info("[WorldBackUp] 복원 완료! 복원 " + stats.restored + "개 파일 ("
                    + FileUtil.humanBytes(stats.restoredBytes) + "), 제거 " + stats.removed
                    + "개, 유지 " + stats.preserved + "개, 실패 " + stats.failed + "개, "
                    + FileUtil.humanMillis(elapsed));
        } else {
            log.severe("[WorldBackUp] 복원이 완전하지 않습니다: " + error);
        }
        log.info("==================================================================");

        writeReport(dataFolder, pending, stats, elapsed, error, log);
        if (error == null) {
            resolveFailureMarkers(dataFolder, log);
        } else {
            writeFailureMarker(dataFolder, pending, error, log);
        }
        deleteQuietly(processing);
    }

    /**
     * 복원이 실패했음을 파일로 남긴다.
     *
     * <p>여기까지 왔다는 건 기존 데이터를 이미 지웠을 수 있다는 뜻이다. 무인 서버라면 아무도
     * 콘솔을 보지 않으므로, 이 표식이 없으면 반쯤 복원된 월드가 그대로 계속 백업되고
     * 멀쩡한 예전 백업이 보관 정책에 밀려 사라진다. 표식이 있는 동안 플러그인은
     * 자동 백업과 보관 정리를 멈춘다.</p>
     */
    private static void writeFailureMarker(Path dataFolder, PendingRestore pending, String error, Logger log) {
        Path marker = dataFolder.resolve(FAILURE_PREFIX + STAMP.format(Instant.now()) + ".yml");
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("id", pending.id());
            yaml.set("archive", pending.archive().toString());
            yaml.set("requested-by", pending.requestedBy());
            yaml.set("failed-at", BackupEntry.DISPLAY_FORMAT.format(Instant.now()));
            yaml.set("error", error);
            yaml.set("안내", "이 파일이 있는 동안 자동 백업과 보관 정리가 멈춥니다. "
                    + "월드를 확인한 뒤 이 파일을 지우고 /wb reload 를 실행하세요.");
            Files.writeString(marker, yaml.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.log(Level.SEVERE, "[WorldBackUp] 복원 실패 기록을 남기지 못했습니다.", e);
            return;
        }
        log.severe("==================================================================");
        log.severe("[WorldBackUp] 복원이 실패해 " + marker.getFileName() + " 을 남겼습니다.");
        log.severe("[WorldBackUp] 이 파일이 있는 동안 자동 백업과 보관 정리를 멈춥니다.");
        log.severe("[WorldBackUp] 월드를 확인한 뒤 파일을 지우고 /wb reload 를 실행하세요.");
        log.severe("==================================================================");
    }

    /** 복원이 성공했으면 옛 실패 표식을 해제한다. 기록은 남기고 정지만 푼다. */
    private static void resolveFailureMarkers(Path dataFolder, Logger log) {
        for (Path marker : failureMarkers(dataFolder)) {
            Path resolved = marker.resolveSibling(marker.getFileName() + RESOLVED_SUFFIX);
            try {
                Files.move(marker, resolved, StandardCopyOption.REPLACE_EXISTING);
                log.info("[WorldBackUp] 복원이 성공해 이전 실패 기록을 해제했습니다: " + resolved.getFileName());
            } catch (IOException e) {
                log.warning("[WorldBackUp] 이전 실패 기록을 해제하지 못했습니다: " + marker.getFileName());
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * 이 아카이브에서 꺼내도 되는 엔트리인지 판단하는 조건.
     *
     * <p>매니페스트에 적힌 파일만 꺼낸다. 거기에는 끝까지 담아낸 파일만 적히므로, 백업 도중
     * 읽다가 끊겨 <b>잘린 채로</b> 남은 엔트리가 이 문턱에서 걸린다. 없는 파일과 깨진 파일은
     * 전혀 다른 결과다 - region 파일이 없으면 그 청크가 재생성되지만, 잘려 있으면
     * "Corrupt regionfile header" 가 뜨고 그 파일이 담당한 지역이 통째로 어그러진다.
     * {@code keep-replaced-files} 를 켜 두었다면 원래 파일은 {@code replaced/} 에 남아 있다.</p>
     *
     * <p>폴더 엔트리는 그냥 통과시킨다. 매니페스트는 파일만 담으므로 빈 폴더를 되살리는
     * 유일한 수단이 그 엔트리다. 매니페스트가 아예 없으면 그것을 기록하지 않던 시절의
     * 백업이므로 예전처럼 전부 꺼낸다.</p>
     */
    private static Predicate<String> restorable(Manifest manifest) {
        if (manifest == null) return name -> true;
        return name -> name.endsWith("/") || manifest.stored(name);
    }

    /**
     * 백업 당시 담지 못한 파일을 알린다.
     *
     * <p>{@link #restorable} 이 걸러 주므로 복원이 데이터를 깨뜨리지는 않지만, 그 파일이
     * 이번 복원으로 <b>어떻게 되는지</b>는 알아야 한다. 조용히 넘어가면 복원이 "완료" 로
     * 끝나 놓고 정작 그 지역만 비어 있게 된다.</p>
     */
    private static void warnAboutUnstored(Manifest manifest, boolean hasBase, Logger log) {
        Set<String> stored = manifest.storedPaths();
        List<String> missing = new ArrayList<>();
        for (String name : manifest.paths()) {
            if (!stored.contains(name)) missing.add(name);
        }
        if (missing.isEmpty()) return;

        missing.sort(null);
        log.warning("[WorldBackUp] 백업 당시 끝까지 읽지 못한 파일이 " + missing.size()
                + "개 있습니다. (서버가 그 파일에 쓰고 있었거나 디스크 오류)");
        log.warning(hasBase
                ? "[WorldBackUp] 기준 백업에 예전 판이 있으면 그것으로 되돌리고, 없으면 그대로 둡니다."
                : "[WorldBackUp] 이 파일들은 복원하지 않습니다. 잘린 파일을 풀면 데이터가 깨집니다.");
        for (String name : missing.subList(0, Math.min(missing.size(), UNSTORED_LOG_LIMIT))) {
            log.warning("[WorldBackUp]   - " + name);
        }
        if (missing.size() > UNSTORED_LOG_LIMIT) {
            log.warning("[WorldBackUp]   … " + (missing.size() - UNSTORED_LOG_LIMIT) + "개 더");
        }
    }

    /** 담지 못한 파일 이름을 콘솔에 몇 개까지 늘어놓을지. */
    private static final int UNSTORED_LOG_LIMIT = 20;

    /**
     * 백업이 실제로 데이터를 갖고 있는 root 만 골라낸다.
     *
     * <p>zip 의 중앙 디렉터리만 읽으므로 {@link #verify} 와 달리 거의 비용이 없다.
     * 그래서 {@code verify-archive} 를 꺼 두어도 항상 수행한다 - 이 판단이 빠지면
     * "비우기만 하고 채우지 않는" 경로가 생겨 데이터가 사라지기 때문이다.</p>
     *
     * @return 복원 대상으로 삼을 root 목록 (원래 순서 유지)
     * @throws IOException 어떤 root 에도 복원할 데이터가 없을 때
     */
    private static List<String> coveredRoots(Path archive, Manifest manifest,
                                             List<String> roots, Logger log) throws IOException {
        // 매니페스트가 있으면 그것이 정답이다. 다만 <b>담아낸</b> 파일만 센다 - 백업 당시
        // 읽지 못한 파일은 여기서 복원해 줄 수 없으므로, 그것만 든 경로를 "데이터가 있다"고
        // 보고 비워 버리면 되돌리지도 못한 채 지우기만 하게 된다.
        Set<String> files = manifest != null ? manifest.storedPaths() : dataEntryNames(archive);

        // root 마다 전체 파일 목록을 다시 훑으면 (파일 수 × root 수) 가 된다. 50만 파일짜리
        // 월드에 root 가 열몇 개면 그것만으로 수백만 번이다. 한 번만 훑으면서 어느 root 가
        // 덮였는지 표시하고, 전부 찾으면 거기서 멈춘다.
        Set<String> withData = new HashSet<>();
        for (String path : files) {
            if (withData.size() == roots.size()) break;
            for (String root : roots) {
                if (withData.contains(root)) continue;
                if (path.equals(root) || path.startsWith(root + "/")) {
                    withData.add(root);
                    break;
                }
            }
        }

        List<String> covered = new ArrayList<>(); // roots 의 원래 순서를 지킨다
        for (String root : roots) {
            if (withData.contains(root)) {
                covered.add(root);
            } else {
                log.warning("[WorldBackUp] 백업에 '" + root + "' 데이터가 없어 이 경로는 건드리지 않습니다. "
                        + "(백업 시점에 비어 있었을 수 있습니다)");
            }
        }
        if (covered.isEmpty()) {
            throw new IOException("백업에 복원할 대상 데이터가 하나도 없습니다: " + String.join(", ", roots)
                    + " (이 백업으로는 해당 경로를 복원할 수 없습니다)");
        }
        return covered;
    }

    /**
     * 복원이 도중에 공간 부족으로 끊기지 않을지 <b>지우기 전에</b> 확인한다.
     *
     * <p>복원은 "기존 데이터 비우기 → 압축 해제" 순서다. 푸는 도중에 디스크가 차면 반쯤
     * 복원된 월드와 실패 표식만 남는다. 백업 쪽에는 {@code min-free-disk-gb} 브레이크가 있는데
     * 정작 위험이 큰 이쪽에는 없었다.</p>
     *
     * <p>{@code keep-replaced-files} 가 켜져 있으면(기본값) 기존 파일을 지우지 않고
     * {@code replaced/} 로 <b>옮긴다.</b> 같은 파일시스템 안의 이동은 이름만 바뀌므로 공간이
     * 하나도 돌아오지 않는다 - 즉 푸는 만큼이 그대로 더 필요하다. 이 경우에만 막는다.</p>
     *
     * <p>꺼져 있으면 기존 데이터를 <b>지우면서</b> 그만큼 공간이 돌아오므로, 지금 여유가
     * 적어도 복원이 성공할 수 있다. 그런 복원을 막으면 되돌릴 방법을 없애는 셈이라 경고만
     * 한다 - 여기서 걸러 내려는 것은 "거의 확실히 도중에 끊길" 상황뿐이다.</p>
     *
     * @throws IOException 공간이 모자라 시작하지 않는 편이 나을 때
     */
    private static void ensureRoomToRestore(Path dataFolder,
                                            Path serverRoot,
                                            PendingRestore pending,
                                            Manifest manifest,
                                            List<String> roots,
                                            GlobMatcher preserve,
                                            Logger log) throws IOException {
        long needed = restoredBytes(pending.archive(), manifest, roots, preserve);
        if (needed <= 0) return;

        long free = FileUtil.usableSpace(serverRoot);
        log.info("[WorldBackUp] 복원에 쓰이는 공간 " + FileUtil.humanBytes(needed)
                + " · 남은 공간 " + FileUtil.humanBytes(free));

        if (hasRoomToRestore(needed, free)) return;

        // 자리가 모자라면 <b>플러그인 자기 잔재부터</b> 치운다. replaced/ 에는 이전 복원이
        // 밀어낸 옛 월드가 몇 벌씩 들어 있어서, 정작 되돌려야 하는 순간에 그것이 길을 막는
        // 일이 생긴다. 지우는 기준은 평소와 똑같은 keep-replaced-max 이므로 보관 정책이
        // 달라지지는 않는다 - 정리 시점만 복원 뒤에서 복원 앞으로 옮기는 것이다.
        log.warning("[WorldBackUp] 공간이 모자랍니다. 오래된 replaced/ 스냅샷부터 정리합니다.");
        cleanupReplaced(dataFolder, pending.keepReplacedMax(), log);
        free = FileUtil.usableSpace(serverRoot);
        if (hasRoomToRestore(needed, free)) {
            log.info("[WorldBackUp] 정리 후 남은 공간 " + FileUtil.humanBytes(free) + " - 복원을 계속합니다.");
            return;
        }

        if (!pending.keepReplaced()) {
            // 지우면서 공간이 돌아오므로 성공할 수도 있다. 막지 않는다.
            log.warning("[WorldBackUp] 남은 공간이 복원할 양보다 적습니다. 기존 데이터를 지우며"
                    + " 확보되는 공간에 기대야 합니다. 도중에 끊기면 replaced/ 사본이 없다는 점을"
                    + " 유념하세요. (restore.keep-replaced-files 가 꺼져 있습니다)");
            return;
        }

        throw new IOException("디스크 여유 공간이 부족해 복원을 시작하지 않았습니다. 필요: "
                + FileUtil.humanBytes(needed + RESTORE_HEADROOM_BYTES)
                + ", 남음: " + FileUtil.humanBytes(free)
                + " - 월드는 건드리지 않았습니다. plugins/WorldBackUp/replaced/ 를 비우거나"
                + " restore.keep-replaced-files 를 끄면 필요한 양이 줄어듭니다.");
    }

    /**
     * 푸는 동안 공간이 떨어지지 않을 여유가 있는지. 디스크를 만지지 않으므로 경계를 그대로
     * 검증할 수 있다.
     *
     * <p>{@code public} 인 이유는 하나뿐이다 - 이 규칙이 <b>잘못 거짓을 내면</b> 되돌릴 수
     * 있었던 복원을 막아 버리므로, 경계를 테스트로 못 박아 두어야 한다. 되돌릴 방법을 없애는
     * 것은 반쯤 복원된 월드보다 나쁠 수 있다.</p>
     */
    public static boolean hasRoomToRestore(long neededBytes, long freeBytes) {
        if (neededBytes <= 0) return true;
        long required = neededBytes + RESTORE_HEADROOM_BYTES;
        if (required < 0) return true; // 넘쳤다 - 막을 근거가 없다
        return freeBytes >= required;
    }

    /**
     * 여유가 이만큼은 더 남아야 시작한다.
     *
     * <p>복원 보고서·로그·서버 자신이 쓰는 몫이다. 크게 잡으면 정작 되돌려야 할 때 막히므로
     * 작게 둔다 - 여기서 걸러 내려는 것은 "거의 확실히 도중에 끊길" 상황뿐이다.</p>
     */
    private static final long RESTORE_HEADROOM_BYTES = 64L * 1024 * 1024;

    /**
     * 이번 복원이 디스크에 쓰게 될 바이트.
     *
     * <p>매니페스트가 있으면 그것이 정답이다 - 담아낸 파일의 <b>압축 해제된</b> 크기가 적혀
     * 있어서 실제로 쓰이는 양과 같고, 기준 백업에서 꺼내 올 파일까지 함께 센다. 매니페스트가
     * 없는 옛 백업이면 zip 의 중앙 디렉터리에 적힌 크기를 쓴다(역시 읽는 비용이 거의 없다).</p>
     */
    private static long restoredBytes(Path archive,
                                      Manifest manifest,
                                      List<String> roots,
                                      GlobMatcher preserve) throws IOException {
        long total = 0L;
        if (manifest != null) {
            for (String name : manifest.storedPaths()) {
                if (!roots.isEmpty() && !underRoots(name, roots)) continue;
                if (preserve.matchesFile(name)) continue;
                total += manifest.storedSize(name);
            }
            return total;
        }
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || isMetadata(name)) continue;
                if (!roots.isEmpty() && !underRoots(name, roots)) continue;
                if (preserve.matchesFile(name)) continue;
                total += Math.max(0L, entry.getSize());
            }
        }
        return total;
    }

    /**
     * 기존 데이터를 지우기 전에 아카이브가 멀쩡한지 확인한다.
     *
     * <p>복원은 "기존 데이터 삭제 -> 압축 해제" 순서라, 깨진 zip 으로 시작하면 월드만 날아간다.
     * 아카이브 크기에 비례해 시간이 걸리지만 그만한 값어치가 있다.</p>
     *
     * <p>차등 백업이면 기준 백업까지 함께 읽고, 매니페스트에 적힌 파일이 둘 중 어딘가에는
     * 반드시 있는지 확인한다.</p>
     */
    private static void verify(Path archive, Path baseArchive, Manifest manifest, Logger log) throws IOException {
        Set<String> available = new HashSet<>(readAndCheck(archive, log));
        if (baseArchive != null) {
            available.addAll(readAndCheck(baseArchive, log));
        }

        // 복원될 최종 파일 목록. 매니페스트가 있으면 그것이 정답이고, 없으면(옛 백업)
        // 아카이브에 든 파일 그대로다.
        //
        // 백업 당시 담지 못한 파일은 <b>여기서 빼야 한다.</b> 그것까지 요구하면 읽지 못한
        // 파일 하나 때문에 복원 전체가 거부된다 - 그 파일을 잃는 것과 백업 전체를 못 쓰는
        // 것은 전혀 다른 크기의 손해다. 아래 교차 검사가 노리는 것은 "짝이 맞지 않는 기준
        // 백업" 인데, 그 판단은 담아낸 파일만으로도 그대로 성립한다.
        Set<String> finalFiles = manifest == null ? available : manifest.storedPaths();
        if (finalFiles.isEmpty()) {
            throw new IOException("백업 파일에 복원할 내용이 없습니다.");
        }

        if (manifest != null) {
            for (String path : finalFiles) {
                if (!available.contains(path)) {
                    throw new IOException("차등 백업과 기준 백업 어디에도 없는 파일이 있습니다: " + path
                            + " (기준 백업이 이 차등 백업과 짝이 맞지 않습니다)");
                }
            }
        }
        log.info("[WorldBackUp] 검사 통과 - 복원 대상 " + finalFiles.size() + "개 파일");
    }

    /** 아카이브를 끝까지 읽어 CRC 를 검사하고, 들어 있는 데이터 파일 이름을 돌려준다. */
    private static Set<String> readAndCheck(Path archive, Logger log) throws IOException {
        log.info("[WorldBackUp] 백업 파일을 검사합니다: " + archive.getFileName()
                + " (" + FileUtil.humanBytes(Files.size(archive)) + ")");

        byte[] buffer = new byte[128 * 1024];
        Set<String> names = new HashSet<>();
        long bytes = 0L;
        long lastLog = System.currentTimeMillis();

        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (entry.isDirectory()) continue;

                // 스트림을 끝까지 읽어야 CRC 불일치가 ZipException 으로 드러난다.
                try (InputStream in = zip.getInputStream(entry)) {
                    int read;
                    while ((read = in.read(buffer)) > 0) bytes += read;
                } catch (IOException e) {
                    throw new IOException("백업 파일이 손상되었습니다: " + archive.getFileName()
                            + " -> " + entry.getName() + " (" + e.getMessage() + ")", e);
                }

                if (isMetadata(entry.getName())) continue;
                names.add(entry.getName());

                long now = System.currentTimeMillis();
                if (now - lastLog > 5_000L) {
                    lastLog = now;
                    log.info("[WorldBackUp] 검사 중... " + names.size() + "개 파일 ("
                            + FileUtil.humanBytes(bytes) + ")");
                }
            }
        }
        return names;
    }

    private static boolean isMetadata(String entryName) {
        return BackupEntry.META_ENTRY.equals(entryName) || Manifest.ENTRY.equals(entryName);
    }

    /**
     * {@code replaced/} 폴더를 최근 keep 개만 남기고 정리한다.
     *
     * <p>복원할 때마다 옛 월드가 통째로 쌓이는 곳이라, 방치하면 디스크를 가장 빨리 채운다.</p>
     */
    public static void cleanupReplaced(Path dataFolder, int keep, Logger log) {
        if (keep <= 0) return;
        Path replaced = dataFolder.resolve(REPLACED_FOLDER);
        if (!Files.isDirectory(replaced)) return;

        List<Path> snapshots;
        try (Stream<Path> stream = Files.list(replaced)) {
            snapshots = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return;
        }
        if (snapshots.size() <= keep) return;

        for (Path old : snapshots.subList(keep, snapshots.size())) {
            List<Path> failed = FileUtil.deleteRecursively(old);
            if (failed.isEmpty()) {
                log.info("[WorldBackUp] 오래된 replaced 스냅샷을 정리했습니다: " + old.getFileName());
            } else {
                log.warning("[WorldBackUp] replaced 스냅샷 일부를 지우지 못했습니다: " + old.getFileName());
            }
        }
    }

    /** 폴더 안의 내용을 비운다. preserve 패턴과 삭제 실패 파일은 남는다. */
    private static void clearDirectory(Path directory,
                                       Path serverRoot,
                                       GlobMatcher preserve,
                                       Path replacedDir,
                                       Stats stats,
                                       Logger log) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {

            /**
             * 통째로 보존할 폴더는 들어가지 않는다. {@link io.github.yj.worldbackup.backup.Archiver}
             * 가 백업에서 제외 폴더를 건너뛰는 것과 같은 규칙이다.
             *
             * <p>두 가지가 달라진다.</p>
             * <ul>
             *   <li>보존 대상인 <b>빈 폴더가 살아남는다.</b> 아래 {@code postVisitDirectory} 는
             *       빈 폴더를 지우는데, 파일 단위로만 검사하면 "그대로 둘 것" 으로 지정한 폴더가
             *       비어 있다는 이유로 사라졌다.</li>
             *   <li>{@code preserve} 에는 백업에서 제외했던 패턴이 함께 들어오고, 거기에는
             *       플러그인 자기 폴더가 있다. 그 안에는 옛 월드를 통째로 담아 둔
             *       {@code replaced/} 가 최대 몇 벌씩 들어 있다. 복원 대상 경로가 그 폴더를
             *       품고 있으면({@code extra-paths: ["plugins"]} 같은 설정) 지금까지는 그
             *       수십만 개를 한 파일씩 훑어 보고 넘겼다. 여기서 끊으면 그 비용이 사라지고
             *       {@code preserved} 집계도 뜻을 되찾는다.</li>
             * </ul>
             */
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String relative = FileUtil.relativize(serverRoot, dir);
                if (relative != null && preserve.matchesDirectory(relative)) {
                    stats.preserved++;
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                removeFile(file, serverRoot, preserve, replacedDir, stats, log);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                stats.failed++;
                log.warning("[WorldBackUp] 접근 실패: " + file + " (" + exc.getMessage() + ")");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (dir.equals(directory)) return FileVisitResult.CONTINUE;
                try {
                    Files.deleteIfExists(dir); // 비어 있을 때만 지워진다.
                } catch (IOException ignored) {
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void removeFile(Path file,
                                   Path serverRoot,
                                   GlobMatcher preserve,
                                   Path replacedDir,
                                   Stats stats,
                                   Logger log) {
        String relative = FileUtil.relativize(serverRoot, file);
        if (relative != null && preserve.matchesFile(relative)) {
            stats.preserved++;
            return;
        }
        try {
            if (replacedDir != null && relative != null) {
                Path destination = replacedDir.resolve(relative);
                Files.createDirectories(destination.getParent());
                Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.delete(file);
            }
            stats.removed++;
        } catch (IOException e) {
            stats.failed++;
            log.warning("[WorldBackUp] 파일을 정리하지 못했습니다: " + relative + " (" + e.getMessage() + ")");
        }
    }

    /** 아카이브에 실제로 들어 있는 데이터 파일 이름들(메타데이터 제외). */
    private static Set<String> dataEntryNames(Path archive) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (entry.isDirectory() || isMetadata(entry.getName())) continue;
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static void extract(Path archive,
                                Path serverRoot,
                                List<String> roots,
                                GlobMatcher preserve,
                                Stats stats,
                                Logger log,
                                Predicate<String> accept) throws IOException {
        long lastLog = System.currentTimeMillis();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (isMetadata(name)) continue;
                if (!roots.isEmpty() && !underRoots(name, roots)) continue;
                // 폴더 엔트리도 accept 를 거친다. 예전에는 무조건 통과시켜서, 기준 백업 이후
                // 삭제된 빈 폴더가 차등 복원 때 되살아났다. 차등 시점에 존재하던 빈 폴더는
                // 차등 zip 에도 그대로 들어 있으므로 기준에서 꺼낼 필요가 없다.
                if (!accept.test(name)) continue;
                if (preserve.matchesFile(name)) {
                    stats.preserved++;
                    continue;
                }

                Path out = serverRoot.resolve(name).normalize();
                if (!out.startsWith(serverRoot)) { // zip slip 방지
                    log.warning("[WorldBackUp] 비정상 경로를 건너뜁니다: " + name);
                    continue;
                }

                try {
                    if (entry.isDirectory()) {
                        Files.createDirectories(out);
                        continue;
                    }
                    Path parent = out.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (entry.getTime() > 0) {
                        Files.setLastModifiedTime(out, FileTime.fromMillis(entry.getTime()));
                    }
                    stats.restored++;
                    stats.restoredBytes += Math.max(0L, entry.getSize());
                } catch (IOException e) {
                    stats.failed++;
                    log.warning("[WorldBackUp] 복원 실패: " + name + " (" + e.getMessage() + ")");
                }

                long now = System.currentTimeMillis();
                if (now - lastLog > 5_000L) {
                    lastLog = now;
                    log.info("[WorldBackUp] 복원 중... " + stats.restored + "개 파일 ("
                            + FileUtil.humanBytes(stats.restoredBytes) + ")");
                }
            }
        }
    }

    private static boolean underRoots(String entryName, List<String> roots) {
        for (String root : roots) {
            if (entryName.equals(root) || entryName.startsWith(root + "/")) return true;
        }
        return false;
    }

    private static void writeReport(Path dataFolder,
                                    PendingRestore pending,
                                    Stats stats,
                                    long elapsedMillis,
                                    String error,
                                    Logger log) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("backup-id", pending.id());
            yaml.set("archive", pending.archive().toString());
            yaml.set("requested-by", pending.requestedBy());
            yaml.set("finished-at", BackupEntry.DISPLAY_FORMAT.format(Instant.now()));
            yaml.set("elapsed-ms", elapsedMillis);
            yaml.set("restored-files", stats.restored);
            yaml.set("restored-bytes", stats.restoredBytes);
            yaml.set("removed-files", stats.removed);
            yaml.set("preserved-files", stats.preserved);
            yaml.set("failed-files", stats.failed);
            yaml.set("success", error == null);
            if (error != null) yaml.set("error", error);
            Files.writeString(dataFolder.resolve(PendingRestore.REPORT_NAME),
                    yaml.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.log(Level.WARNING, "[WorldBackUp] 복원 보고서를 저장하지 못했습니다.", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
