package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.util.GlobMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
 *
 * <p>읽기 실패는 {@link UnreadableFile} 로 <b>주입한다.</b> 예전에는 {@link FileLock} 으로
 * 흉내 냈는데 그 잠금은 윈도우에서만 강제라, 정작 이 플러그인이 대부분 돌아가는 리눅스에서는
 * 위 세 가지가 하나도 검증되지 않고 조용히 통과했다. 실제 OS 잠금이 정말 이 모양인지는
 * 맨 아래 테스트가 그것이 가능한 플랫폼에서 확인한다.</p>
 */
class ArchiverUnreadableFileTest {

    private static final Logger LOG = Logger.getLogger("WorldBackUpArchiverTest");

    @TempDir
    Path tmp;

    /** 열리지 않는 파일은 매니페스트에도, zip 에도 남지 않아야 한다. */
    @Test
    void unreadableFileIsNotRecordedAsBackedUp() throws Exception {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        write(world.resolve("region/r.0.0.mca"), "REGION-GOOD");
        Path busy = world.resolve("region/r.0.1.mca");
        write(busy, "REGION-BUSY-AND-IMPORTANT");

        Path archive = serverRoot.resolve("backups/wb-20260817-120000.zip");
        Archiver.Result result = archive(archive, serverRoot, world, null,
                UnreadableFile.refusingToOpen(busy));

        assertUnreadableFileWasHandled(archive, result);
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
        archive(full, serverRoot, world, null, UnreadableFile.refusingToOpen(busy));

        // 이제 읽을 수 있다. 파일 내용은 그대로지만 기준에 없으므로 담겨야 한다.
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
        Archiver.Result result = archive(archive, serverRoot, world, null,
                UnreadableFile.failingAfterFirstChunk(region));

        assertEquals(1, result.skippedCount());
        assertFalse(Manifest.readFrom(archive).orElseThrow().stored("world/region/r.0.0.mca"),
                "잘린 파일을 담은 것으로 적으면 다음 차등 백업이 이 파일을 잃는다");

        ZipEntry truncated = entryOf(archive, "world/region/r.0.0.mca");
        assertEquals(UnreadableFile.BUFFER_BYTES, truncated.getSize(),
                "zip 엔트리 자체는 잘린 채로 남는다 - 되돌릴 방법이 없다");
    }

    /**
     * 실제 OS 파일 잠금도 위와 같은 결과를 내는지.
     *
     * <p>위 테스트들은 읽기 실패를 <b>주입</b>한다. 그 주입이 실제 서버에서 벌어지는 일과 같은
     * 모양인지는 별개의 질문이고, 그것을 확인할 수 있는 곳은 잠금이 <b>강제</b>인 플랫폼뿐이다.
     * 리눅스의 {@link FileLock} 은 권고적이라 읽기를 막지 못하므로 여기서 재현할 수 없다 -
     * 그래서 이 한 테스트만 플랫폼을 가린다. 주입한 상황이 현실과 맞는지를 붙잡아 두는 것이
     * 이 테스트의 유일한 일이다.</p>
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRealOsFileLockLooksTheSameToTheArchiver() throws Exception {
        Path serverRoot = tmp.resolve("server");
        Path world = serverRoot.resolve("world");
        write(world.resolve("region/r.0.0.mca"), "REGION-GOOD");
        Path busy = world.resolve("region/r.0.1.mca");
        write(busy, "REGION-BUSY-AND-IMPORTANT");

        Path archive = serverRoot.resolve("backups/wb-20260817-120000.zip");
        Archiver.Result result;

        try (FileChannel channel = FileChannel.open(busy, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            assertTrue(lock.isValid());
            result = archive(archive, serverRoot, world, null);
        }

        assertUnreadableFileWasHandled(archive, result);
    }

    // ------------------------------------------------------------------

    /** 주입한 실패와 실제 잠금이 <b>같은 결과</b>여야 하므로 검증을 한 곳에 둔다. */
    private static void assertUnreadableFileWasHandled(Path archive, Archiver.Result result) throws IOException {
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

    private Archiver.Result archive(Path archive, Path serverRoot, Path target, Manifest base) throws IOException {
        return Archiver.create(archive, serverRoot, List.of(target), 1,
                new GlobMatcher(List.of()), base, 0L,
                (fileCount, originalBytes) -> "id: test\n", null, LOG);
    }

    private Archiver.Result archive(Path archive, Path serverRoot, Path target, Manifest base,
                                    Archiver.FileOpener opener) throws IOException {
        return Archiver.create(archive, serverRoot, List.of(target), 1,
                new GlobMatcher(List.of()), base, 0L,
                (fileCount, originalBytes) -> "id: test\n", null, LOG, opener);
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
