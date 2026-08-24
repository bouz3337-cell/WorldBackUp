package io.github.yj.worldbackup.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * <b>디스크에 실제로 있는 월드</b>를 찾아 무엇이 성한지 본다.
 *
 * <p>백업은 {@code Bukkit.getWorlds()} 로 대상을 정한다. 그런데 그것은 <b>로드된 월드만</b>
 * 돌려준다. Multiverse 같은 플러그인이 만들어 두고 언로드해 둔 월드, 플러그인이 로드에
 * 실패해 올라오지 못한 월드는 디스크에 멀쩡히 있으면서도 <b>백업에서 조용히 빠진다.</b>
 * 그런 월드가 있다는 사실은 정작 되돌려야 하는 날에 드러난다.</p>
 *
 * <p>그래서 서버 폴더를 직접 훑는다. 판단 기준은 {@link WorldLayout#LEVEL_MARKER} 하나다 -
 * 그 파일이 있는 폴더가 월드 하나의 뿌리다. 폴더 이름이나 구조를 전제하지 않으므로 버전이
 * 바뀌어도, 플러그인이 이상한 이름을 붙여도 걸린다.</p>
 *
 * <p>디스크만 읽으므로 서버 없이 그대로 검증할 수 있다.</p>
 */
public final class WorldScan {

    /**
     * 훑어 내려갈 깊이.
     *
     * <p>월드는 서버 폴더 바로 아래({@code world/}) 있거나, {@code world-container} ·
     * {@code --universe} 로 한 단 들어가 있다({@code universe/world/}). 3 이면 그 둘을
     * 모두 덮으면서 {@code libraries/} 같은 깊은 나무를 파고들지 않는다.</p>
     */
    private static final int MAX_DEPTH = 3;

    /**
     * 들어가 보지 않는 폴더.
     *
     * <p>월드가 있을 리 없는데 파일이 아주 많은 곳들이다. 여기를 훑으면 검사 한 번이
     * 수십만 파일을 건드린다.</p>
     */
    private static final Set<String> SKIP = Set.of(
            "plugins", "libraries", "versions", "cache", "logs", "crash-reports",
            "oneback", "backups", ".paper", "bundler");

    private WorldScan() {
    }

    /**
     * 월드 하나의 상태.
     *
     * @param folder   월드 뿌리 폴더
     * @param name     폴더 이름 (서버가 부르는 이름과 대개 같다)
     * @param terrain  지형 파일({@code .mca})이 하나라도 있는지
     * @param playerData 플레이어 데이터 폴더가 있는지
     * @param bytes    이 월드가 차지하는 크기
     */
    public record World(Path folder, String name, boolean terrain, boolean playerData, long bytes) {
    }

    /**
     * 서버 폴더에서 월드를 찾는다.
     *
     * <p>서버 폴더 자체는 월드로 보지 않는다. 그랬다가는 서버 전체가 월드 하나가 된다.</p>
     */
    public static List<World> findOnDisk(Path serverRoot) {
        List<World> found = new ArrayList<>();
        if (serverRoot == null || !Files.isDirectory(serverRoot)) return found;

        Path root = serverRoot.toAbsolutePath().normalize();
        collect(root, root, 0, found);
        found.sort(Comparator.comparing(World::name));
        return found;
    }

    private static void collect(Path directory, Path serverRoot, int depth, List<World> found) {
        if (depth > MAX_DEPTH) return;
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.toList()) {
                if (!Files.isDirectory(child)) continue;
                String name = child.getFileName().toString();
                if (SKIP.contains(name.toLowerCase(Locale.ROOT))) continue;

                if (Files.isRegularFile(child.resolve(WorldLayout.LEVEL_MARKER))) {
                    found.add(describe(child));
                    // 월드 안으로는 더 들어가지 않는다. 차원 폴더에도 level.dat 이 있는
                    // 버전이 있어서, 들어가면 한 월드가 여럿으로 세어진다.
                    continue;
                }
                collect(child, serverRoot, depth + 1, found);
            }
        } catch (IOException ignored) {
            // 읽을 수 없는 폴더는 없는 것으로 본다. 검사 때문에 시작이 막히면 안 된다.
        }
    }

    /** 이 월드에 무엇이 있는지 살펴본다. */
    private static World describe(Path folder) {
        boolean terrain = false;
        boolean playerData = false;
        long bytes = 0L;

        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.toList()) {
                if (Files.isDirectory(path)) {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.equals("playerdata") || name.equals("players")) playerData = true;
                    continue;
                }
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".mca")) terrain = true;
                try {
                    bytes += Files.size(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return new World(folder, folder.getFileName().toString(), terrain, playerData, bytes);
    }

    /**
     * 디스크에는 있는데 <b>백업 대상에 없는</b> 월드.
     *
     * <p>이것이 이 클래스가 존재하는 이유다. 백업은 로드된 월드만 보므로, 언로드된 월드는
     * 아무 경고 없이 빠진다. 그 사실을 아는 유일한 방법이 디스크를 직접 보는 것이다.</p>
     *
     * @param onDisk     {@link #findOnDisk} 결과
     * @param backedUp   지금 백업이 담고 있는 월드 폴더들
     */
    public static List<World> missingFromBackup(List<World> onDisk, Set<Path> backedUp) {
        Set<Path> covered = new LinkedHashSet<>();
        for (Path path : backedUp) {
            if (path != null) covered.add(path.toAbsolutePath().normalize());
        }

        List<World> missing = new ArrayList<>();
        for (World world : onDisk) {
            Path folder = world.folder().toAbsolutePath().normalize();
            if (covered.contains(folder)) continue;
            // 백업 대상이 이 월드를 품고 있으면(예: extra-paths 에 상위 폴더) 담긴 것이다.
            boolean inside = covered.stream().anyMatch(folder::startsWith);
            if (!inside) missing.add(world);
        }
        return missing;
    }

    /**
     * 이 월드에서 <b>사람이 알아야 하는</b> 문제들.
     *
     * <p>없는 것을 만들어 주지는 않는다. 특히 {@link WorldLayout#LEVEL_MARKER} 은 절대
     * 만들지 않는다 - 그 파일에는 <b>시드</b>가 들어 있어서, 새로 만들면 서버가 다른 지형을
     * 생성하기 시작한다. 이미 있는 청크는 그대로 남으므로 <b>땅이 어긋난 월드</b>가 되고,
     * 그것은 되돌릴 수 없다. 없으면 백업에서 되돌리는 것이 유일하게 맞는 길이다.</p>
     */
    public static List<String> problems(World world) {
        List<String> problems = new ArrayList<>();
        if (!world.terrain()) {
            problems.add("지형 파일(.mca)이 하나도 없습니다. 아직 생성되지 않았거나 사라진 월드입니다.");
        }
        if (!world.playerData()) {
            problems.add("플레이어 데이터 폴더가 없습니다. 이 월드로는 인벤토리를 되돌릴 수 없습니다.");
        }
        return problems;
    }
}
