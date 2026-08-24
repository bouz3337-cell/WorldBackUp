package io.github.yj.worldbackup.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    // ------------------------------------------------------------------
    // 용량 측정

    @Test
    void measureCountsEverythingExceptExcludedFiles() throws IOException {
        Path root = tmp.resolve("server");
        write(root.resolve("world/level.dat"), "1234567890");        // 10 B
        write(root.resolve("world/region/r.0.0.mca"), "12345");      //  5 B
        write(root.resolve("world/logs/latest.log"), "noise-noise"); // 제외 대상

        FileUtil.Sizes sizes = FileUtil.measure(root.resolve("world"), root,
                new GlobMatcher(List.of("**/logs/**")), null);

        assertEquals(15L, sizes.totalBytes(), "제외된 파일은 세지 않는다");
        assertEquals(15L, sizes.changedBytes(), "기준이 없으면 전부 저장 대상이다");
    }

    /**
     * 차등 백업의 디스크 여유 판단이 여기에 달려 있다. 전체 크기로 판단하면 200MB 를 쓸 백업이
     * 10GB 를 요구하며, 그 공간을 만들겠다고 멀쩡한 백업을 지운 뒤 결국 실패한다.
     */
    @Test
    void measureSeparatesChangedBytesFromTheWholeSnapshot() throws IOException {
        Path root = tmp.resolve("server");
        Path unchanged = write(root.resolve("world/region/r.0.0.mca"), "AAAAAAAAAA"); // 10 B, 안 바뀜
        write(root.resolve("world/region/r.0.1.mca"), "BBBBB");                       //  5 B, 바뀜

        long unchangedSize = Files.size(unchanged);
        long unchangedTime = Files.getLastModifiedTime(unchanged).toMillis();

        // 기준 백업에 r.0.0.mca 만 같은 크기·시각으로 들어 있는 상황
        FileUtil.ChangeFilter filter = (relative, size, modified) ->
                !(relative.equals("world/region/r.0.0.mca") && size == unchangedSize && modified == unchangedTime);

        FileUtil.Sizes sizes = FileUtil.measure(root.resolve("world"), root, GlobMatcher.empty(), filter);

        assertEquals(15L, sizes.totalBytes(), "진행률은 스냅샷 전체 기준을 유지한다");
        assertEquals(5L, sizes.changedBytes(), "디스크 여유는 실제로 저장할 양으로 판단해야 한다");
    }

    @Test
    void measureHandlesASingleFileTarget() throws IOException {
        Path root = tmp.resolve("server");
        write(root.resolve("server.properties"), "motd=hello");

        FileUtil.Sizes sizes = FileUtil.measure(root.resolve("server.properties"), root,
                GlobMatcher.empty(), null);

        assertEquals(10L, sizes.totalBytes());
        assertEquals(10L, sizes.changedBytes());
    }

    /**
     * 접두사가 같다고 <b>그 폴더 안</b>인 것은 아니다.
     *
     * <p>뿌리가 {@code server/plugins} 일 때 {@code server/plugins-old/x} 도 문자열로는
     * 접두사가 맞는다. 이어 붙이기로 처리하면 {@code plugins/old/x} 라는 있지도 않은 경로가
     * 나오고, 그 이름으로 zip 에 담기면 복원이 엉뚱한 자리에 쓴다. 예외도 경고도 없이
     * 백업은 성공한 것처럼 끝난다.</p>
     */
    @Test
    void aSiblingWithTheSamePrefixIsNotReadAsAChild() {
        Path root = tmp.resolve("server");
        FileUtil.RelativePaths relatives =
                FileUtil.RelativePaths.under(root, root.resolve("plugins"));

        assertEquals("plugins", relatives.of(root.resolve("plugins")));
        assertEquals("plugins/Economy/balances.yml",
                relatives.of(root.resolve("plugins/Economy/balances.yml")));
        assertEquals("plugins-old/x", relatives.of(root.resolve("plugins-old/x")),
                "이름이 겹치는 형제 폴더를 자식으로 읽으면 엉뚱한 경로가 만들어진다");
    }

    private static Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
