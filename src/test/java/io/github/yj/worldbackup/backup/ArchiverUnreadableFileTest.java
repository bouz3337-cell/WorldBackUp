package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.util.GlobMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 읽지 못한 파일이 백업에 "들어간 것처럼" 남지 않는지 검증한다.
 *
 * <p>이것이 실서버에서 가장 자주 일어나는 실패다. 서버가 region 파일을 쓰고 있는 동안에는
 * 파일을 <b>열 수는 있어도 읽을 수 없다.</b> 그때 zip 엔트리를 먼저 만들어 두면 0바이트
 * 엔트리가 남고, 매니페스트에는 그 파일이 정상적으로 담긴 것으로 기록된다. 그러면</p>
 * <ul>
 *   <li>복원할 때 멀쩡한 파일이 <b>빈 파일로 덮어써지고</b>,</li>
 *   <li>다음 차등 백업은 "기준에 이미 있다"고 믿어 그 파일을 아예 저장하지 않으며,</li>
 *   <li>그 차등본을 복원하려 하면 검사가 "어디에도 없는 파일" 이라며 복원 자체를 막는다.</li>
 * </ul>
 */
class ArchiverUnreadableFileTest {

    private static final Logger LOG = Logger.getLogger("WorldBackUpArchiverTest");

    @TempDir
    Path tmp;

    /** 다른 프로세스가 잡고 있는 파일은 매니페스트에도, zip 에도 남지 않아야 한다. */
    @Test
    void lockedFileIsNotRecordedAsBackedUp() throws Exception {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        write(world.resolve("region/r.0.0.mca"), "REGION-GOOD");
        Path busy = world.resolve("region/r.0.1.mca");
        write(busy, "REGION-BUSY-AND-IMPORTANT");

        Path archive = serverRoot.resolve("backups/wb-20260817-120000.zip");
        Archiver.Result result;

        // 서버가 이 region 파일에 쓰고 있는 상황. 열기는 되지만 읽기가 실패한다.
        try (FileChannel channel = FileChannel.open(busy, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            assertTrue(lock.isValid());
            result = archive(archive, serverRoot, world, null);
        }

        assertEquals(1, result.skippedCount(), "읽지 못한 파일은 건너뛴 것으로 세야 한다");

        Manifest manifest = Manifest.readFrom(archive).orElseThrow();
        assertTrue(manifest.stored("world/region/r.0.0.mca"), "읽은 파일은 꺼낼 수 있어야 한다");
        assertFalse(manifest.stored("world/region/r.0.1.mca"),
                "읽지 못한 파일을 담은 것으로 적으면 다음 차등 백업이 이 파일을 통째로 잃는다");
        assertTrue(manifest.contains("world/region/r.0.1.mca"),
                "그래도 '그 시점에 있던 파일' 로는 남아야 한다 - 차등 복원이 기준의 예전 판을 찾는 단서다");

        assertNull(entryOf(archive, "world/region/r.0.1.mca"),
                "빈 엔트리를 남기면 복원 때 멀쩡한 파일을 빈 파일로 덮어쓴다");
        assertEquals(1, result.fileCount(), "담지 못한 파일까지 세면 안 된다");
    }

    /**
     * 위 백업을 기준으로 차등 백업을 만들면, 읽지 못했던 파일은 이번에 <b>새로 담겨야</b> 한다.
     *
     * <p>기준 매니페스트가 거짓이면 차등본은 그 파일을 건너뛰고, 두 아카이브 어디에도 없는
     * 파일이 생겨 복원이 통째로 막힌다.</p>
     */
    @Test
    void nextDifferentialStoresTheFileThatCouldNotBeReadBefore() throws Exception {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        write(world.resolve("region/r.0.0.mca"), "REGION-GOOD");
        Path busy = world.resolve("region/r.0.1.mca");
        write(busy, "REGION-BUSY-AND-IMPORTANT");

        Path full = serverRoot.resolve("backups/wb-20260817-120000.zip");
        try (FileChannel channel = FileChannel.open(busy, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            assertTrue(lock.isValid());
            archive(full, serverRoot, world, null);
        }

        // 이제 잠금이 풀렸다. 파일 내용은 그대로지만 기준에 없으므로 담겨야 한다.
        Manifest base = Manifest.readFrom(full).orElseThrow();
        Path diff = serverRoot.resolve("backups/wb-20260817-123000.zip");
        Archiver.Result result = archive(diff, serverRoot, world, base);

        assertEquals(0, result.skippedCount());
        assertEquals("REGION-BUSY-AND-IMPORTANT",
                textOf(diff, "world/region/r.0.1.mca"),
                "기준에 없는 파일은 차등본이 담아야 한다");
    }

    /**
     * 읽다가 <b>중간에</b> 끊긴 파일은 zip 엔트리를 되돌릴 수 없다. 그렇다면 최소한
     * "담아냈다" 고 적히지는 않아야 한다.
     *
     * <p>첫 조각을 읽은 뒤에 끊기는 경우다(실제 I/O 오류). 이때 zip 에는 잘린 엔트리가
     * 남는데, 담은 것으로 적으면 다음 차등 백업이 "기준에 있다" 고 믿어 그 파일을 통째로
     * 잃는다. 잘린 엔트리를 복원에서 걸러 내는 것은 {@code RestoreApplier} 의 몫이다.</p>
     */
    @Test
    void fileTruncatedMidReadIsNotCountedAsStored() throws Exception {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        write(world.resolve("level.dat"), "LEVEL");
        Path region = world.resolve("region/r.0.0.mca");
        Files.createDirectories(region.getParent());
        Files.write(region, new byte[300_000]);

        Path archive = serverRoot.resolve("backups/wb-20260817-120000.zip");
        Archiver.Result result;

        // 첫 조각(128KB)은 읽히고 그 뒤부터 끊긴다.
        try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock lock = channel.lock(BUFFER_BYTES, Long.MAX_VALUE - BUFFER_BYTES, false)) {
            assertTrue(lock.isValid());
            result = archive(archive, serverRoot, world, null);
        }

        assertEquals(1, result.skippedCount());
        assertFalse(Manifest.readFrom(archive).orElseThrow().stored("world/region/r.0.0.mca"),
                "잘린 파일을 담은 것으로 적으면 다음 차등 백업이 이 파일을 잃는다");

        ZipEntry truncated = entryOf(archive, "world/region/r.0.0.mca");
        assertEquals(BUFFER_BYTES, truncated.getSize(),
                "zip 엔트리 자체는 잘린 채로 남는다 - 되돌릴 방법이 없다");
    }

    // ------------------------------------------------------------------

    /** {@code Archiver} 의 읽기 버퍼 크기. 여기까지는 읽히고 그 뒤가 끊기도록 잠근다. */
    private static final int BUFFER_BYTES = 128 * 1024;

    private Archiver.Result archive(Path archive, Path serverRoot, Path target, Manifest base) throws IOException {
        return Archiver.create(archive, serverRoot, List.of(target), 1,
                new GlobMatcher(List.of()), base, 0L,
                (fileCount, originalBytes) -> "id: test\n", null, LOG);
    }

    private static ZipEntry entryOf(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            return zip.getEntry(name);
        }
    }

    private static String textOf(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = Optional.ofNullable(zip.getEntry(name)).orElseThrow();
            try (var in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
