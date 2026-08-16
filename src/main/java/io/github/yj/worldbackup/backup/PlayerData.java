package io.github.yj.worldbackup.backup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        boolean inventory = false;

        for (Path base : searchBases) {
            if (base == null) continue;
            for (String name : FOLDER_NAMES) {
                Path candidate = base.resolve(name).toAbsolutePath().normalize();
                if (!Files.isDirectory(candidate)) continue;
                if (found.contains(candidate)) continue;
                found.add(candidate);
                if (name.equals(INVENTORY_FOLDER)) inventory = true;
            }
        }
        return new Located(List.copyOf(found), inventory);
    }

    /**
     * 인벤토리가 백업에서 빠졌음을 알린다.
     *
     * <p>조용히 넘어가면 안 되는 상황이다. 백업은 성공했다고 뜨고 목록에도 정상으로 보이는데,
     * 정작 롤백할 때 인벤토리만 그대로 남는다. 사고가 난 뒤에 알게 되는 것이 최악이라
     * 시작할 때와 백업할 때 모두 크게 알린다.</p>
     */
    public static void warnMissing(Logger log) {
        log.severe("==================================================================");
        log.severe("[백업] 플레이어 데이터(playerdata) 폴더를 찾지 못했습니다.");
        log.severe("[백업] 이 백업으로는 인벤토리·좌표·경험치를 되돌릴 수 없습니다.");
        log.severe("[백업] config.yml 의 targets.extra-paths 에 실제 경로를 넣어 주세요.");
        log.severe("==================================================================");
    }
}
