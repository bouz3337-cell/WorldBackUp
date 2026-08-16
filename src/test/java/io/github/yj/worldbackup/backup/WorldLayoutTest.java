package io.github.yj.worldbackup.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 월드 폴더 구조 해석.
 *
 * <p>26.2 의 {@code getWorldFolder()} 는 {@code world/dimensions/minecraft/overworld} 같은
 * <b>차원 폴더</b>를 돌려준다. 그것만 백업하면 지형은 멀쩡히 되돌아오는데 인벤토리·경험치·
 * 스코어보드·지도·월드 스폰이 통째로 빠지고, 그런데도 백업은 성공한 것처럼 보인다.
 * 실제 서버에서 이걸로 인벤토리가 복구되지 않았다.</p>
 */
class WorldLayoutTest {

    @TempDir
    Path tmp;

    /** 26.2 구조. 차원 폴더에서 level.dat 이 있는 월드 폴더까지 올라가야 한다. */
    @Test
    void climbsFromDimensionFolderToTheRealWorldFolder() throws IOException {
        Path serverRoot = tmp.resolve("container");
        Path world = serverRoot.resolve("world");
        Path overworld = world.resolve("dimensions/minecraft/overworld");

        Files.createDirectories(overworld.resolve("region"));
        Files.createDirectories(world.resolve("playerdata"));
        write(world.resolve(WorldLayout.LEVEL_MARKER), "LEVEL");

        assertEquals(world, WorldLayout.levelRoot(overworld, serverRoot));
    }

    /** 예전 구조는 그대로여야 한다. 이미 월드 폴더면 올라가지 않는다. */
    @Test
    void keepsTheFolderWhenItIsAlreadyTheWorldRoot() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        Files.createDirectories(world.resolve("region"));
        write(world.resolve(WorldLayout.LEVEL_MARKER), "LEVEL");

        assertEquals(world, WorldLayout.levelRoot(world, serverRoot));
    }

    /** 네더·엔드가 각자 level.dat 을 갖는 구조에서는 각자가 뿌리다. */
    @Test
    void separateWorldFoldersStayIndependent() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Path nether = serverRoot.resolve("world_nether");
        Files.createDirectories(nether.resolve("DIM-1/region"));
        write(nether.resolve(WorldLayout.LEVEL_MARKER), "LEVEL");
        write(serverRoot.resolve("world/" + WorldLayout.LEVEL_MARKER), "LEVEL");

        assertEquals(nether, WorldLayout.levelRoot(nether, serverRoot));
    }

    /**
     * 서버 폴더 자체를 월드로 삼으면 안 된다.
     * 그랬다가는 서버 전체가 통째로 하나의 백업 대상이 된다.
     */
    @Test
    void neverClimbsUpToTheServerRoot() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Path orphan = serverRoot.resolve("world/dimensions/minecraft/overworld");
        Files.createDirectories(orphan);
        write(serverRoot.resolve(WorldLayout.LEVEL_MARKER), "여기까지 올라오면 안 된다");

        assertEquals(orphan, WorldLayout.levelRoot(orphan, serverRoot),
                "level.dat 을 못 찾으면 원래 폴더를 그대로 쓴다");
    }

    /** 월드가 서버 폴더 밖에 있으면 올라가지 않는다. */
    @Test
    void doesNotClimbOutsideTheServerRoot() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Path outside = tmp.resolve("elsewhere/world/dimensions/minecraft/overworld");
        Files.createDirectories(serverRoot);
        Files.createDirectories(outside);
        write(tmp.resolve("elsewhere/world/" + WorldLayout.LEVEL_MARKER), "LEVEL");

        assertEquals(outside, WorldLayout.levelRoot(outside, serverRoot));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
