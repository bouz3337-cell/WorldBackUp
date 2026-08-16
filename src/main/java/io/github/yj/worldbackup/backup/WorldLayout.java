package io.github.yj.worldbackup.backup;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 서버 버전마다 다른 월드 폴더 구조를 다룬다.
 *
 * <p>{@code World#getWorldFolder()} 가 무엇을 돌려주는지는 버전에 따라 다르다.</p>
 * <ul>
 *   <li>예전 구조 - {@code world/} 를 돌려주고, 그 안에 region 과 playerdata 가 함께 있다.</li>
 *   <li>26.2 - {@code world/dimensions/minecraft/overworld/} 처럼 <b>차원 폴더</b>를 돌려준다.
 *       region 은 거기 있지만 {@code playerdata}, {@code level.dat}, {@code data} 는
 *       그 위의 {@code world/} 에 있다.</li>
 * </ul>
 *
 * <p>차원 폴더만 백업하면 지형은 멀쩡히 되돌아오는데 인벤토리·경험치·스코어보드·지도·
 * 월드 스폰이 통째로 빠진다. 게다가 백업은 성공한 것처럼 보인다. 그래서 폴더 구조를
 * 전제하지 않고, {@code level.dat} 을 표지 삼아 진짜 월드 폴더를 찾아 올라간다.</p>
 */
public final class WorldLayout {

    /** 이 파일이 있는 폴더가 월드 하나의 뿌리다. */
    public static final String LEVEL_MARKER = "level.dat";

    private WorldLayout() {
    }

    /**
     * 차원 폴더에서 시작해 {@code level.dat} 이 있는 조상 폴더를 찾는다.
     *
     * <p>서버 폴더 밖으로는 나가지 않고, 서버 폴더 자체를 월드로 삼지도 않는다.
     * 그랬다가는 서버 전체가 하나의 백업 대상이 되어 버린다.</p>
     *
     * @return 찾은 월드 폴더. 못 찾으면 입력을 그대로 돌려준다.
     */
    public static Path levelRoot(Path worldFolder, Path serverRoot) {
        if (worldFolder == null) return null;
        Path root = serverRoot == null ? null : serverRoot.toAbsolutePath().normalize();
        Path current = worldFolder.toAbsolutePath().normalize();

        while (current != null) {
            if (root != null && (current.equals(root) || !current.startsWith(root))) break;
            if (Files.isRegularFile(current.resolve(LEVEL_MARKER))) return current;
            current = current.getParent();
        }
        return worldFolder.toAbsolutePath().normalize();
    }
}
