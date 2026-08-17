package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.restore.PendingRestore;
import io.github.yj.worldbackup.restore.RestoreApplier;
import io.github.yj.worldbackup.util.GlobMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 심볼릭 링크로 옮겨 둔 월드도 백업되는지.
 *
 * <p>월드를 다른 디스크에 두고 서버 폴더에서 링크로 걸어 두는 것은 리눅스 서버에서 흔한
 * 구성이다(월드는 SSD, 서버는 HDD). {@code Files.walkFileTree} 는 기본적으로 링크를 따르지
 * 않으므로, 시작 경로가 링크면 <b>디렉터리로 취급되지 않고</b> 파일 하나로 방문된다.
 * 그러면 그 아래가 통째로 백업에서 빠지는데 백업은 성공한 것처럼 끝난다.</p>
 */
class SymlinkTargetTest {

    private static final Logger LOG = Logger.getLogger("WorldBackUpSymlinkTest");

    @TempDir
    Path tmp;

    @Test
    void aSymlinkedWorldFolderIsStillBackedUp() throws Exception {
        Path serverRoot = tmp.resolve("server");
        Path elsewhere = tmp.resolve("ssd/world");
        Files.createDirectories(elsewhere.resolve("region"));
        Files.writeString(elsewhere.resolve("level.dat"), "LEVEL", StandardCharsets.UTF_8);
        Files.writeString(elsewhere.resolve("region/r.0.0.mca"), "지형", StandardCharsets.UTF_8);
        Files.createDirectories(serverRoot);

        Path link = serverRoot.resolve("world");
        try {
            Files.createSymbolicLink(link, elsewhere);
        } catch (UnsupportedOperationException | FileSystemException e) {
            // 윈도우는 권한이 없으면 링크를 만들 수 없다. 그때는 이 검증을 건너뛴다.
            assumeTrue(false, "이 환경에서는 심볼릭 링크를 만들 수 없습니다: " + e.getMessage());
            return;
        }

        Path archive = tmp.resolve("out/wb-20260818-120000.zip");
        Archiver.Result result = Archiver.create(archive, serverRoot, List.of(link), 1,
                new GlobMatcher(List.of()), null, 0L,
                (fileCount, originalBytes) -> "id: test\n", null, LOG);

        List<String> names = entryNames(archive);
        assertTrue(names.contains("world/level.dat"),
                "링크 아래의 월드가 백업에서 빠지면 되돌릴 것이 없다. 담긴 것: " + names);
        assertTrue(names.contains("world/region/r.0.0.mca"), "담긴 것: " + names);
        assertTrue(result.fileCount() >= 2, "파일 수: " + result.fileCount());
    }

    /**
     * 복원이 링크 <b>자체</b>를 치우고 그 자리에 월드를 새로 만들지 않는지.
     *
     * <p>링크를 따라 들어가지 않으면 링크가 디렉터리로 취급되지 않아 {@code replaced/} 로
     * 옮겨지고, 압축이 그 자리에 풀리면서 월드가 시스템 디스크에 새로 생긴다. 다른 디스크에
     * 두려고 걸어 둔 링크가 복원 한 번에 사라지는 것이다.</p>
     */
    @Test
    void restoringThroughASymlinkKeepsTheLinkAndWritesToTheRealDisk() throws Exception {
        Path serverRoot = tmp.resolve("server2");
        Path elsewhere = tmp.resolve("ssd2/world");
        Files.createDirectories(elsewhere.resolve("region"));
        Files.writeString(elsewhere.resolve("level.dat"), "LEVEL", StandardCharsets.UTF_8);
        Files.writeString(elsewhere.resolve("region/r.0.0.mca"), "지형", StandardCharsets.UTF_8);
        Files.createDirectories(serverRoot);

        Path link = serverRoot.resolve("world");
        try {
            Files.createSymbolicLink(link, elsewhere);
        } catch (UnsupportedOperationException | FileSystemException e) {
            assumeTrue(false, "이 환경에서는 심볼릭 링크를 만들 수 없습니다: " + e.getMessage());
            return;
        }

        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Path archive = dataFolder.resolve("backups/wb-20260818-120000.zip");
        Archiver.create(archive, serverRoot, List.of(link), 1,
                new GlobMatcher(List.of()), null, 0L,
                (fileCount, originalBytes) -> "id: test\n", null, LOG);

        // 테러: 링크 아래의 실제 월드를 망가뜨린다.
        Files.writeString(elsewhere.resolve("level.dat"), "망가짐", StandardCharsets.UTF_8);
        Files.delete(elsewhere.resolve("region/r.0.0.mca"));

        new PendingRestore("20260818-120000", archive, null, "tester", System.currentTimeMillis(),
                false, 3, true, List.of(), List.of("world")).write(dataFolder);
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertTrue(Files.isSymbolicLink(link), "링크가 그대로 남아야 한다 - 없으면 월드가 다른 디스크로 옮겨진 것이다");
        assertEquals("LEVEL", Files.readString(elsewhere.resolve("level.dat"), StandardCharsets.UTF_8),
                "복원은 링크를 지나 실제 디스크에 써야 한다");
        assertEquals("지형", Files.readString(elsewhere.resolve("region/r.0.0.mca"), StandardCharsets.UTF_8));
    }

    private static List<String> entryNames(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) names.add(it.nextElement().getName());
        }
        return names;
    }
}
