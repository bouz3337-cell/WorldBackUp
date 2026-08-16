package io.github.yj.worldbackup.backup;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 플레이어별 데이터(인벤토리·좌표·경험치·통계·발전 과제) 폴더를 <b>찾아낸다.</b>
 *
 * <p>예전에는 이것들이 메인 월드 폴더 안({@code world/playerdata})에 있다고 전제했다.
 * 서버 구현이나 버전에 따라 서버 루트 쪽에 있을 수 있는데, 그러면 백업 대상 어디에도 걸리지
 * 않는다. 문제는 그때 백업이 <b>실패하지 않는다</b>는 것이다. 월드는 정상적으로 담기니
 * {@code /wb backup} 은 성공이라 뜨고 목록에도 멀쩡하게 보인다. 정작 롤백할 때가 되어서야
 * 인벤토리가 그대로인 것을 알게 된다.</p>
 *
 * <p>그래서 전제하지 않고 실제로 찾는다. 그리고 못 찾으면 조용히 넘어가지 않는다.</p>
 */
public final class PlayerData {

    /** 서버가 플레이어별로 파일을 쌓는 폴더 이름들. */
    private static final List<String> FOLDER_NAMES = List.of("playerdata", "stats", "advancements");

    /** 인벤토리·좌표·경험치가 들어 있는 폴더. 백업에서 이것만은 빠지면 안 된다. */
    private static final String INVENTORY_FOLDER = "playerdata";

    /** 정해진 후보에서 못 찾았을 때 서버 폴더를 훑어 볼 깊이. */
    private static final int SEARCH_DEPTH = 3;

    /**
     * 탐색에서 건너뛸 폴더들.
     *
     * <p>{@code plugins/} 아래에도 {@code playerdata} 라는 이름의 폴더가 있을 수 있는데,
     * 그건 그 플러그인의 데이터지 서버의 플레이어 데이터가 아니다. 나머지는 뒤져 봐야
     * 나올 게 없는데 항목만 수천 개인 곳들이다.</p>
     *
     * <p>작은 폴더는 굳이 빼지 않는다. 한 번이라도 잘못 건너뛰면 못 찾는 쪽이 훨씬 비싸다.</p>
     */
    private static final Set<String> SKIP = Set.of(
            "plugins", "libraries", "versions", "logs", "cache", "crash-reports",
            "backups", "replaced", "region", "entities", "poi", ".git");

    /**
     * @param paths     실제로 존재하는 플레이어 데이터 폴더들 (절대 경로, 중복 없음)
     * @param inventory {@code playerdata} 폴더를 하나라도 찾았는지.
     *                  false 면 이 백업으로는 인벤토리를 되돌릴 수 없다.
     */
    public record Located(List<Path> paths, boolean inventory) {
    }

    private PlayerData() {
    }

    /**
     * 후보 위치들을 훑어 실제로 있는 플레이어 데이터 폴더를 모은다.
     *
     * <p>월드 폴더 안에 있으면 그 월드가 이미 백업 대상이라 중복이 되는데,
     * 그건 {@link io.github.yj.worldbackup.util.FileUtil#dedupeTargets(List)} 가 걸러 낸다.
     * 그러니 여기서는 찾은 것을 그냥 다 돌려주면 된다.</p>
     *
     * @param searchBases 월드 폴더들과 서버 루트 등, 하위를 뒤져 볼 기준 경로
     */
    public static Located locate(List<Path> searchBases) {
        List<Path> found = new ArrayList<>();
        for (Path base : searchBases) {
            if (base == null) continue;
            for (String name : FOLDER_NAMES) {
                Path candidate = base.resolve(name).toAbsolutePath().normalize();
                if (!Files.isDirectory(candidate)) continue;
                if (!found.contains(candidate)) found.add(candidate);
            }
        }
        return of(found);
    }

    /**
     * 정해진 후보에서 못 찾으면 서버 폴더 아래를 얕게 훑는다.
     *
     * <p>플레이어 데이터가 어디 있는지는 서버 구현·버전·호스팅 환경에 따라 다르고, 후보 목록을
     * 아무리 늘려도 결국 새로운 배치를 만난다. 그래서 마지막에는 실제로 찾아본다. 흔한 배치에서는
     * 위 {@link #locate} 가 바로 맞히므로 이 탐색은 <b>돌지 않는다.</b></p>
     *
     * <p>깊이를 {@value #SEARCH_DEPTH} 로 묶고 {@link #SKIP} 을 건너뛴다. 플러그인 데이터
     * 폴더에도 {@code playerdata} 라는 이름이 있을 수 있어서, 그런 것을 서버의 플레이어
     * 데이터로 착각하면 엉뚱한 폴더를 백업하게 된다.</p>
     */
    public static Located search(Path serverRoot, List<Path> preferredBases) {
        Located direct = locate(preferredBases);
        if (direct.inventory()) return direct;
        if (serverRoot == null || !Files.isDirectory(serverRoot)) return direct;

        List<Path> found = new ArrayList<>(direct.paths());
        try {
            Files.walkFileTree(serverRoot, Set.of(), SEARCH_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(serverRoot)) return FileVisitResult.CONTINUE;
                    String name = name(dir);
                    if (SKIP.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                    if (FOLDER_NAMES.contains(name)) {
                        record(dir);
                        return FileVisitResult.SKIP_SUBTREE; // 안까지 들어갈 이유가 없다
                    }
                    return FileVisitResult.CONTINUE;
                }

                /**
                 * 깊이 제한에 <b>정확히</b> 걸린 디렉터리는 {@code preVisitDirectory} 가 아니라
                 * 여기로 전달된다. 이걸 놓치면 탐색이 마지막 한 층을 통째로 못 본다.
                 */
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isDirectory() && FOLDER_NAMES.contains(name(file))) {
                        record(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                private String name(Path path) {
                    return path.getFileName().toString().toLowerCase(Locale.ROOT);
                }

                private void record(Path dir) {
                    Path normalized = dir.toAbsolutePath().normalize();
                    if (!found.contains(normalized)) found.add(normalized);
                }
            });
        } catch (IOException ignored) {
            // 부분 결과라도 쓴다
        }
        return of(found);
    }

    private static Located of(List<Path> found) {
        boolean inventory = found.stream()
                .anyMatch(path -> path.getFileName().toString().equalsIgnoreCase(INVENTORY_FOLDER));
        return new Located(List.copyOf(found), inventory);
    }

    /**
     * 인벤토리가 백업에서 빠졌음을 알린다.
     *
     * <p>조용히 넘어가면 안 되는 상황이다. 백업은 성공했다고 뜨고 목록에도 정상으로 보이는데,
     * 정작 롤백할 때 인벤토리만 그대로 남는다. 사고가 난 뒤에 알게 되는 것이 최악이라
     * 시작할 때와 백업할 때 모두 크게 알린다.</p>
     */
    public static void warnMissing(Logger log, Path serverRoot, List<Path> searched) {
        log.severe("==================================================================");
        log.severe("[백업] 플레이어 데이터(playerdata) 폴더를 찾지 못했습니다.");
        log.severe("[백업] 이 백업으로는 인벤토리·좌표·경험치를 되돌릴 수 없습니다.");
        log.severe("[백업] 아직 아무도 접속하지 않은 서버라면 정상입니다. 한 번 접속한 뒤");
        log.severe("[백업] /wb status 로 다시 확인해 주세요.");
        log.severe("[백업]");
        // 원격 서버에서 이 로그만 보고 원인을 짚을 수 있어야 한다. 어디를 봤는지 남긴다.
        log.severe("[백업] 서버 폴더: " + serverRoot);
        log.severe("[백업] 확인한 위치 (하위 " + SEARCH_DEPTH + "단계까지 훑었음):");
        for (Path base : searched) {
            log.severe("[백업]   - " + base);
        }
        log.severe("[백업] 그래도 안 되면 config.yml 의 targets.extra-paths 에 실제 경로를 넣으세요.");
        log.severe("==================================================================");
    }
}
