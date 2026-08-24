package io.github.yj.worldbackup.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>백업에서 조용히 빠지는 월드를 찾아낸다.</b>
 *
 * <p>백업 대상은 {@code Bukkit.getWorlds()} 에서 온다. 그런데 그것은 <b>로드된 월드만</b>
 * 돌려준다. Multiverse 같은 플러그인이 만들어 두고 언로드해 둔 월드, 플러그인이 로드에
 * 실패해 올라오지 못한 월드는 디스크에 멀쩡히 있으면서도 백업에서 빠진다.</p>
 *
 * <p>그리고 <b>아무 경고도 없다.</b> 백업은 성공으로 끝나고, 그 월드가 빠졌다는 사실은
 * 정작 되돌려야 하는 날에 드러난다. 이 검사가 그 자리를 메운다.</p>
 */
class WorldScanTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------
    // 찾기

    /** {@code level.dat} 이 있는 폴더가 월드다. 이름이나 구조를 전제하지 않는다. */
    @Test
    void aFolderWithALevelMarkerIsAWorld() throws IOException {
        Path server = tmp.resolve("server");
        world(server.resolve("world"));
        world(server.resolve("이벤트월드"));          // 플러그인이 만든 한글 이름
        world(server.resolve("mv_skyblock"));       // Multiverse 가 만든 것

        List<WorldScan.World> found = WorldScan.findOnDisk(server);

        assertEquals(List.of("mv_skyblock", "world", "이벤트월드"),
                found.stream().map(WorldScan.World::name).toList());
    }

    /** {@code world-container} 나 {@code --universe} 로 한 단 들어간 서버도 찾아야 한다. */
    @Test
    void worldsUnderAContainerFolderAreFoundToo() throws IOException {
        Path server = tmp.resolve("server");
        world(server.resolve("universe/world"));
        world(server.resolve("universe/world_nether"));

        List<WorldScan.World> found = WorldScan.findOnDisk(server);

        assertEquals(2, found.size());
    }

    /**
     * 월드 안으로는 더 들어가지 않는다.
     *
     * <p>26.2 는 {@code world/dimensions/minecraft/overworld/} 구조를 쓰는데, 그 아래에도
     * {@code level.dat} 이 있는 배치가 있다. 들어가면 <b>한 월드가 여럿으로 세어져</b>
     * "백업에서 빠졌다" 는 거짓 경고가 뜬다.</p>
     */
    @Test
    void aWorldIsCountedOnceEvenWhenItsDimensionsLookLikeWorlds() throws IOException {
        Path server = tmp.resolve("server");
        Path world = server.resolve("world");
        world(world);
        world(world.resolve("dimensions/minecraft/the_nether"));

        List<WorldScan.World> found = WorldScan.findOnDisk(server);

        assertEquals(1, found.size(), "월드 하나는 하나로 센다");
        assertEquals("world", found.get(0).name());
    }

    /** 파일이 아주 많고 월드가 있을 리 없는 폴더는 훑지 않는다. 검사가 비싸지면 아무도 안 쓴다. */
    @Test
    void theHeavyFoldersAreNeverWalked() throws IOException {
        Path server = tmp.resolve("server");
        world(server.resolve("world"));
        // 여기 level.dat 을 심어도 월드로 보면 안 된다
        world(server.resolve("plugins/SomePlugin/world"));
        world(server.resolve("libraries/x/world"));
        world(server.resolve("OneBack/server-복구/world"));

        List<WorldScan.World> found = WorldScan.findOnDisk(server);

        assertEquals(List.of("world"), found.stream().map(WorldScan.World::name).toList());
    }

    /** 서버 폴더 자체를 월드로 삼으면 서버 전체가 월드 하나가 된다. */
    @Test
    void theServerFolderItselfIsNeverAWorld() throws IOException {
        Path server = tmp.resolve("server");
        Files.createDirectories(server);
        Files.writeString(server.resolve(WorldLayout.LEVEL_MARKER), "x", StandardCharsets.UTF_8);

        assertTrue(WorldScan.findOnDisk(server).isEmpty());
    }

    // ------------------------------------------------------------------
    // 백업에서 빠진 것

    /**
     * <b>이 클래스가 존재하는 이유.</b>
     *
     * <p>로드된 월드는 백업 대상이 되고, 언로드된 월드는 되지 않는다. 그 차이를 찾아낸다.</p>
     */
    @Test
    void anUnloadedWorldIsReportedAsMissingFromTheBackup() throws IOException {
        Path server = tmp.resolve("server");
        world(server.resolve("world"));            // 로드됨
        world(server.resolve("mv_skyblock"));      // 플러그인이 만들고 언로드해 둠

        List<WorldScan.World> onDisk = WorldScan.findOnDisk(server);
        Set<Path> backedUp = Set.of(server.resolve("world"));   // 백업은 이것만 안다

        List<WorldScan.World> missing = WorldScan.missingFromBackup(onDisk, backedUp);

        assertEquals(1, missing.size());
        assertEquals("mv_skyblock", missing.get(0).name(),
                "언로드된 월드는 아무 경고 없이 백업에서 빠진다. 그것을 여기서 잡는다");
    }

    /** 이미 담기고 있으면 경고하지 않는다. 거짓 경고가 쌓이면 진짜 경고도 무시된다. */
    @Test
    void nothingIsReportedWhenEveryWorldIsCovered() throws IOException {
        Path server = tmp.resolve("server");
        world(server.resolve("world"));
        world(server.resolve("world_nether"));

        List<WorldScan.World> onDisk = WorldScan.findOnDisk(server);
        Set<Path> backedUp = Set.of(server.resolve("world"), server.resolve("world_nether"));

        assertTrue(WorldScan.missingFromBackup(onDisk, backedUp).isEmpty());
    }

    /**
     * {@code extra-paths} 로 <b>상위 폴더</b>를 담아 두었으면 그 안의 월드도 담긴 것이다.
     *
     * <p>이걸 못 보면 "빠졌다" 고 잘못 알리고, 관리자는 이미 담고 있는 것을 또 넣게 된다.</p>
     */
    @Test
    void aWorldInsideAlreadyBackedUpFolderIsNotMissing() throws IOException {
        Path server = tmp.resolve("server");
        world(server.resolve("universe/world"));
        world(server.resolve("universe/skyblock"));

        List<WorldScan.World> onDisk = WorldScan.findOnDisk(server);
        Set<Path> backedUp = Set.of(server.resolve("universe"));   // 통째로 담아 둠

        assertTrue(WorldScan.missingFromBackup(onDisk, backedUp).isEmpty());
    }

    // ------------------------------------------------------------------
    // 무엇이 성한가

    @Test
    void aHealthyWorldReportsNoProblems() throws IOException {
        Path server = tmp.resolve("server");
        Path world = world(server.resolve("world"));
        write(world.resolve("region/r.0.0.mca"), "지형");
        write(world.resolve("playerdata/uuid.dat"), "인벤토리");

        WorldScan.World scanned = WorldScan.findOnDisk(server).get(0);

        assertTrue(scanned.terrain());
        assertTrue(scanned.playerData());
        assertTrue(WorldScan.problems(scanned).isEmpty());
    }

    /** 26.2 의 새 배치({@code world/players/}) 도 플레이어 데이터로 알아봐야 한다. */
    @Test
    void theNewPlayerDataLayoutIsRecognised() throws IOException {
        Path server = tmp.resolve("server");
        Path world = world(server.resolve("world"));
        write(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"), "지형");
        write(world.resolve("players/data/uuid.dat"), "인벤토리");

        WorldScan.World scanned = WorldScan.findOnDisk(server).get(0);

        assertTrue(scanned.terrain(), "차원 폴더 아래의 지형도 지형이다");
        assertTrue(scanned.playerData());
    }

    /** 빠진 것이 있으면 사람에게 알린다. 다만 <b>만들어 주지는 않는다.</b> */
    @Test
    void anEmptyWorldSaysWhatIsMissing() throws IOException {
        Path server = tmp.resolve("server");
        world(server.resolve("world"));

        WorldScan.World scanned = WorldScan.findOnDisk(server).get(0);
        List<String> problems = WorldScan.problems(scanned);

        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("지형"));
        assertTrue(problems.get(1).contains("플레이어"));
    }

    /**
     * {@code level.dat} 은 <b>절대 만들어 주지 않는다.</b>
     *
     * <p>그 파일에는 시드가 들어 있다. 새로 만들면 서버가 <b>다른 지형</b>을 생성하기 시작하고,
     * 이미 있는 청크는 그대로 남아 땅이 어긋난 월드가 된다. 그것은 되돌릴 수 없다.
     * 없으면 백업에서 되돌리는 것이 유일하게 맞는 길이다.</p>
     */
    @Test
    void aFolderWithoutALevelMarkerIsNeverRepairedIntoAWorld() throws IOException {
        Path server = tmp.resolve("server");
        // level.dat 만 없는 폴더 - 지형은 남아 있다
        write(server.resolve("world/region/r.0.0.mca"), "지형");

        assertTrue(WorldScan.findOnDisk(server).isEmpty(),
                "월드로 보지 않는다. 시드를 지어내 되살리는 것보다 없다고 말하는 편이 낫다");
        assertFalse(Files.exists(server.resolve("world/" + WorldLayout.LEVEL_MARKER)),
                "검사가 파일을 만들어 내면 안 된다");
    }

    // ------------------------------------------------------------------

    private static Path world(Path folder) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(WorldLayout.LEVEL_MARKER), "LEVEL", StandardCharsets.UTF_8);
        return folder;
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }
}
