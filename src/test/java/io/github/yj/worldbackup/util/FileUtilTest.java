package io.github.yj.worldbackup.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileUtilTest {

    @TempDir
    Path tmp;

    /**
     * 겹치는 대상을 그대로 두면 zip 에 같은 엔트리가 두 번 들어가 백업 전체가
     * {@code ZipException} 으로 실패한다.
     */
    @Test
    void nestedTargetIsAbsorbedByItsParent() {
        Path root = tmp.resolve("server");
        List<Path> merged = FileUtil.dedupeTargets(List.of(
                root.resolve("world"),
                root.resolve("world/playerdata"),   // extra-paths 로 흔히 들어오는 형태
                root.resolve("server.properties")));

        assertEquals(List.of(root.resolve("world"), root.resolve("server.properties")), merged);
    }

    @Test
    void duplicateTargetAppearsOnlyOnce() {
        Path root = tmp.resolve("server");
        List<Path> merged = FileUtil.dedupeTargets(List.of(
                root.resolve("world"),
                root.resolve("world"),
                root.resolve("world/./")));

        assertEquals(List.of(root.resolve("world")), merged);
    }

    @Test
    void unrelatedTargetsAreAllKeptInOrder() {
        Path root = tmp.resolve("server");
        List<Path> targets = List.of(
                root.resolve("world"),
                root.resolve("world_nether"),
                root.resolve("plugins/LuckPerms"));

        assertEquals(targets, FileUtil.dedupeTargets(targets));
    }

    /** 이름이 비슷할 뿐인 형제 폴더를 하위 경로로 오해하면 월드가 통째로 백업에서 빠진다. */
    @Test
    void siblingWithSharedNamePrefixIsNotTreatedAsNested() {
        Path root = tmp.resolve("server");
        List<Path> merged = FileUtil.dedupeTargets(List.of(
                root.resolve("world"),
                root.resolve("world_nether"),
                root.resolve("world_the_end")));

        assertEquals(3, merged.size());
    }

    @Test
    void relativizeReturnsNullOutsideTheRoot() {
        Path root = tmp.resolve("server");
        assertEquals("world/level.dat", FileUtil.relativize(root, root.resolve("world/level.dat")));
        assertEquals(null, FileUtil.relativize(root, tmp.resolve("elsewhere/level.dat")));
    }
}
