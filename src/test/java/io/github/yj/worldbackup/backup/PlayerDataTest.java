package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.util.FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 플레이어 데이터 위치 탐색.
 *
 * <p>이걸 "메인 월드 폴더 안" 이라고 전제했다가, 서버 루트에 있는 서버에서 인벤토리가
 * 통째로 백업에서 빠졌다. 백업은 성공했다고 뜨고 목록에도 정상으로 보여서, 롤백할 때가
 * 되어서야 드러났다. 그래서 두 배치 모두와 "아예 없는" 경우를 검증한다.</p>
 */
class PlayerDataTest {

    @TempDir
    Path tmp;

    @Test
    void findsPlayerDataInsideTheWorldFolder() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        Files.createDirectories(world.resolve("playerdata"));
        Files.createDirectories(world.resolve("stats"));
        Files.createDirectories(world.resolve("advancements"));
        Files.createDirectories(serverRoot.resolve("plugins"));

        PlayerData.Located located = PlayerData.locate(List.of(world, serverRoot));

        assertTrue(located.inventory());
        assertEquals(List.of("world/playerdata", "world/stats", "world/advancements"),
                relativize(serverRoot, located.paths()));
    }

    /** 월드 폴더 밖으로 나가 있어도 찾아야 한다. 이 경우가 인벤토리 유실의 원인이었다. */
    @Test
    void findsPlayerDataAtTheServerRoot() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(serverRoot.resolve("playerdata"));
        Files.createDirectories(serverRoot.resolve("stats"));

        PlayerData.Located located = PlayerData.locate(List.of(world, serverRoot));

        assertTrue(located.inventory());
        assertEquals(List.of("playerdata", "stats"), relativize(serverRoot, located.paths()));
    }

    /** 양쪽에 흩어져 있어도 둘 다 담아야 한다. */
    @Test
    void findsPlayerDataInBothPlacesWithoutDuplicates() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        Files.createDirectories(world.resolve("playerdata"));
        Files.createDirectories(serverRoot.resolve("advancements"));

        PlayerData.Located located = PlayerData.locate(List.of(world, serverRoot, world));

        assertTrue(located.inventory());
        assertEquals(List.of("world/playerdata", "advancements"),
                relativize(serverRoot, located.paths()));
    }

    /** 못 찾으면 호출자가 경고할 수 있도록 분명히 알려야 한다. 조용히 넘어가면 안 된다. */
    @Test
    void reportsWhenInventoryFolderIsMissing() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("stats")); // 통계는 있지만 인벤토리는 없다

        PlayerData.Located located = PlayerData.locate(List.of(world, serverRoot));

        assertFalse(located.inventory(), "playerdata 가 없으면 인벤토리를 되돌릴 수 없다");
        assertEquals(List.of("world/stats"), relativize(serverRoot, located.paths()));
    }

    /** 월드 폴더 안에 있으면 월드 대상과 겹치는데, 그건 dedupeTargets 가 흡수한다. */
    @Test
    void nestedPlayerDataIsAbsorbedByTheWorldTarget() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        Files.createDirectories(world.resolve("playerdata"));

        PlayerData.Located located = PlayerData.locate(List.of(world, serverRoot));

        List<Path> targets = new java.util.ArrayList<>();
        targets.add(world);
        targets.addAll(located.paths());

        assertEquals(List.of(world), FileUtil.dedupeTargets(targets),
                "zip 에 같은 엔트리가 두 번 들어가면 백업 전체가 실패한다");
    }

    // ------------------------------------------------------------------
    // 정해진 후보에 없을 때의 탐색

    /** 후보 목록이 아무리 늘어도 결국 새로운 배치를 만난다. 마지막에는 실제로 찾아본다. */
    @Test
    void searchFindsPlayerDataInAnUnexpectedPlace() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        Files.createDirectories(world.resolve("region"));
        // 월드 폴더 안도, 서버 루트도 아닌 곳
        Files.createDirectories(serverRoot.resolve("data/players/playerdata"));

        assertFalse(PlayerData.locate(List.of(world, serverRoot)).inventory(), "정해진 후보로는 못 찾는다");

        PlayerData.Located located = PlayerData.search(serverRoot, List.of(world, serverRoot));

        assertTrue(located.inventory(), "훑어서 찾아내야 한다");
        assertEquals(List.of("data/players/playerdata"), relativize(serverRoot, located.paths()));
    }

    /** 플러그인 데이터 폴더 안의 동명 폴더를 서버의 플레이어 데이터로 착각하면 안 된다. */
    @Test
    void searchSkipsPluginDataFolders() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Files.createDirectories(serverRoot.resolve("world/region"));
        Files.createDirectories(serverRoot.resolve("plugins/SomePlugin/playerdata"));

        PlayerData.Located located = PlayerData.search(serverRoot, List.of(serverRoot.resolve("world"), serverRoot));

        assertFalse(located.inventory(), "plugins/ 아래는 서버의 플레이어 데이터가 아니다");
        assertTrue(located.paths().isEmpty());
    }

    /** 흔한 배치에서는 훑는 비용이 아예 들지 않아야 한다. */
    @Test
    void searchDoesNotWalkWhenTheDirectCandidateHits() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        Files.createDirectories(world.resolve("playerdata"));
        Files.createDirectories(serverRoot.resolve("data/players/playerdata")); // 훑었다면 이것도 잡혔을 것

        PlayerData.Located located = PlayerData.search(serverRoot, List.of(world, serverRoot));

        assertEquals(List.of("world/playerdata"), relativize(serverRoot, located.paths()),
                "바로 맞히면 더 훑지 않는다");
    }

    private static List<String> relativize(Path serverRoot, List<Path> paths) {
        return paths.stream().map(path -> FileUtil.relativize(serverRoot, path)).toList();
    }
}
