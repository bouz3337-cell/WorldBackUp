package io.github.yj.worldbackup;

import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.Manifest;
import io.github.yj.worldbackup.restore.PendingRestore;
import io.github.yj.worldbackup.restore.RestoreApplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>1.0.0 이 만든 백업을 지금 버전이 되돌릴 수 있는가.</b>
 *
 * <p>플러그인을 올리는 서버에는 옛 백업이 그대로 쌓여 있다. 그 백업들이 읽히지 않으면
 * 업그레이드한 순간 <b>되돌릴 수 있는 시점이 하나도 없는 서버</b>가 된다 - 그런데 그 사실은
 * 정작 되돌려야 하는 날에야 드러난다. 백업 플러그인에서 가장 나쁜 실패다.</p>
 *
 * <p>그래서 1.0.0 형식을 <b>손으로 만들어</b> 지금 코드로 복원해 본다. 옛 버전을 컴파일해
 * 돌릴 수는 없으므로, 1.0.0 이 실제로 쓰던 것과 같은 모양을 직접 적는다.</p>
 *
 * <ul>
 *   <li>메타데이터에 {@code player-data} 가 <b>없다</b> (1.1.0 에서 생겼다)</li>
 *   <li>파일 목록에 "담지 못함"({@code -1}) 표시가 <b>없다</b> (1.1.1 에서 생겼다)</li>
 *   <li>복원 예약 파일에 {@code keep-replaced-max} 가 <b>없다</b></li>
 * </ul>
 */
class LegacyBackupCompatTest {

    private static final Logger LOG = Logger.getLogger("LegacyBackupCompatTest");

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------

    /** 1.0.0 이 만든 <b>전체 백업</b>을 그대로 되돌릴 수 있어야 한다. */
    @Test
    void aFullBackupMadeByVersionOneCanStillBeRestored() throws IOException {
        Path serverRoot = tmp.resolve("server");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Files.createDirectories(dataFolder);

        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("world/level.dat", "LEVEL");
        contents.put("world/region/r.0.0.mca", "REGION");
        contents.put("world/playerdata/uuid.dat", "INVENTORY");
        contents.put("server.properties", "motd=old");

        Path archive = legacyArchive(tmp.resolve("wb-20260101-000000.zip"),
                "20260101-000000", null, contents);

        // 테러로 월드가 날아간 상황
        write(serverRoot.resolve("world/region/r.0.0.mca"), "GRIEFED");

        legacyPending(dataFolder, "20260101-000000", archive, null,
                List.of("world", "server.properties"));
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("REGION", read(serverRoot.resolve("world/region/r.0.0.mca")));
        assertEquals("LEVEL", read(serverRoot.resolve("world/level.dat")));
        assertEquals("INVENTORY", read(serverRoot.resolve("world/playerdata/uuid.dat")),
                "인벤토리까지 돌아와야 한다");
        assertEquals("motd=old", read(serverRoot.resolve("server.properties")));
        assertTrue(RestoreApplier.failureMarkers(dataFolder).isEmpty(), "실패 표식이 남으면 안 된다");
    }

    /**
     * 1.0.0 이 만든 <b>차등 백업</b>도 되돌릴 수 있어야 한다.
     *
     * <p>차등은 기준 백업과 짝을 이뤄야 풀리고, 지금 코드는 그 판단에 파일 목록을 쓴다.
     * 옛 목록에는 "담지 못함" 표시가 없으므로 <b>전부 담긴 것</b>으로 읽혀야 한다 - 여기가
     * 어긋나면 복원이 파일을 하나도 꺼내지 않고 "완료" 로 끝난다.</p>
     */
    @Test
    void aDifferentialBackupMadeByVersionOneCanStillBeRestored() throws IOException {
        Path serverRoot = tmp.resolve("server2");
        Path dataFolder = serverRoot.resolve("plugins/WorldBackUp");
        Files.createDirectories(dataFolder);

        // 기준 백업 - 이 시점의 전체
        Map<String, String> base = new LinkedHashMap<>();
        base.put("world/level.dat", "LEVEL");
        base.put("world/region/r.0.0.mca", "REGION-OLD");
        Path baseArchive = legacyArchive(tmp.resolve("wb-base.zip"), "base", null, base);

        // 차등 - region 만 바뀌었다. 목록에는 그 시점의 전체가 적힌다.
        Map<String, String> diff = new LinkedHashMap<>();
        diff.put("world/region/r.0.0.mca", "REGION-NEW");
        Path diffArchive = legacyArchive(tmp.resolve("wb-diff.zip"), "diff", "base", diff,
                List.of("world/level.dat", "world/region/r.0.0.mca"));

        write(serverRoot.resolve("world/region/r.0.0.mca"), "GRIEFED");

        legacyPending(dataFolder, "diff", diffArchive, baseArchive, List.of("world"));
        RestoreApplier.applyIfPending(dataFolder, serverRoot, LOG);

        assertEquals("REGION-NEW", read(serverRoot.resolve("world/region/r.0.0.mca")),
                "차등 쪽 판이 이겨야 한다");
        assertEquals("LEVEL", read(serverRoot.resolve("world/level.dat")),
                "차등에 없는 파일은 기준 백업에서 꺼내 온다");
        assertTrue(RestoreApplier.failureMarkers(dataFolder).isEmpty());
    }

    /**
     * 옛 백업의 "플레이어 데이터 포함 여부" 는 <b>거짓이 아니라 모름</b>이어야 한다.
     *
     * <p>{@code player-data} 는 1.1.0 에서 생긴 키다. 없는 것을 {@code false} 로 읽으면
     * {@code /wb list} 가 멀쩡한 옛 백업을 전부 <b>[플레이어없음]</b> 으로 표시한다.
     * 인벤토리를 되돌리려는 사람이 그 백업을 건너뛰게 된다.</p>
     */
    @Test
    void anOldBackupDoesNotClaimToHaveNoPlayerData() throws IOException {
        Path backupDir = tmp.resolve("backups");
        Files.createDirectories(backupDir);

        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("world/level.dat", "LEVEL");
        Path archive = legacyArchive(backupDir.resolve("wb-20260101-000000.zip"),
                "20260101-000000", null, contents);
        // 사이드카도 1.0.0 형식으로 (player-data 없음)
        Files.writeString(backupDir.resolve("wb-20260101-000000.yml"),
                legacyMeta("20260101-000000", null, List.of("world")), StandardCharsets.UTF_8);

        BackupRepository repository = new BackupRepository(backupDir, LOG);
        List<BackupEntry> entries = repository.list();

        assertEquals(1, entries.size(), "옛 백업이 목록에 보여야 한다");
        BackupEntry entry = entries.get(0);
        assertTrue(entry.complete(), "옛 백업이 [손상] 으로 보이면 복원에 쓸 수 없다");
        assertNull(entry.playerData(), "기록이 없는 것은 '없음' 이 아니라 '모름' 이다");
        assertEquals(Files.size(archive), entry.archiveBytes());
    }

    /** 옛 파일 목록에는 "담지 못함" 표시가 없으므로 전부 복원 대상이어야 한다. */
    @Test
    void everyFileInAnOldManifestCountsAsStored() throws IOException {
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("world/level.dat", "LEVEL");
        contents.put("world/region/r.0.0.mca", "REGION");
        Path archive = legacyArchive(tmp.resolve("wb-x.zip"), "x", null, contents);

        Manifest manifest = Manifest.readFrom(archive).orElseThrow();

        assertTrue(manifest.stored("world/level.dat"));
        assertTrue(manifest.stored("world/region/r.0.0.mca"));
        assertEquals(2, manifest.storedPaths().size());
        assertFalse(manifest.stored("world/없는파일.dat"));
    }

    // ------------------------------------------------------------------
    // 1.0.0 이 실제로 쓰던 모양

    private Path legacyArchive(Path archive, String id, String baseId,
                               Map<String, String> contents) throws IOException {
        return legacyArchive(archive, id, baseId, contents, List.copyOf(contents.keySet()));
    }

    /**
     * @param listed 파일 목록에 적을 경로들. 차등 백업이면 zip 에 든 것보다 많다
     *               (그 시점의 <b>전체</b> 파일이 적히기 때문).
     */
    private Path legacyArchive(Path archive, String id, String baseId,
                               Map<String, String> contents, List<String> listed) throws IOException {
        Files.createDirectories(archive.getParent());
        StringBuilder manifest = new StringBuilder();
        for (String path : listed) {
            String body = contents.get(path);
            long size = body == null ? 8L : body.getBytes(StandardCharsets.UTF_8).length;
            // 1.0.0 형식: "크기 수정시각 경로". 담지 못함(-1) 표시는 아직 없다.
            manifest.append(size).append(' ').append(1_700_000_000_000L).append(' ')
                    .append(path).append('\n');
        }

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(Manifest.ENTRY));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(BackupEntry.META_ENTRY));
            zip.write(legacyMeta(id, baseId, List.of("world")).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return archive;
    }

    /** 1.0.0 의 메타데이터. {@code player-data} 키가 없다. */
    private static String legacyMeta(String id, String baseId, List<String> worlds) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("id: ").append(id).append('\n');
        yaml.append("created-at: 1700000000000\n");
        yaml.append("created-at-text: 2023-11-15 00:00:00\n");
        yaml.append("type: ").append(baseId == null ? "SCHEDULED" : "DIFFERENTIAL").append('\n');
        yaml.append("label: null\n");
        yaml.append("original-bytes: 100\n");
        yaml.append("file-count: 2\n");
        yaml.append("roots:\n- world\n");
        yaml.append("worlds:\n");
        for (String world : worlds) yaml.append("- ").append(world).append('\n');
        yaml.append("excludes:\n- '**/session.lock'\n");
        yaml.append("server-version: 1.0.0-test\n");
        yaml.append("locked: false\n");
        yaml.append("base-id: ").append(baseId == null ? "null" : baseId).append('\n');
        return yaml.toString();
    }

    /** 1.0.0 의 복원 예약 파일. {@code keep-replaced-max} 키가 없다. */
    private static void legacyPending(Path dataFolder, String id, Path archive,
                                      Path baseArchive, List<String> roots) throws IOException {
        StringBuilder yaml = new StringBuilder();
        yaml.append("id: ").append(id).append('\n');
        yaml.append("archive: ").append(archive.toAbsolutePath()).append('\n');
        if (baseArchive != null) {
            yaml.append("base-archive: ").append(baseArchive.toAbsolutePath()).append('\n');
        }
        yaml.append("requested-by: admin\n");
        yaml.append("requested-at: 1700000000000\n");
        yaml.append("keep-replaced: false\n");
        yaml.append("verify-archive: true\n");
        yaml.append("preserve: []\n");
        yaml.append("roots:\n");
        for (String root : roots) yaml.append("- ").append(root).append('\n');
        Files.writeString(PendingRestore.file(dataFolder), yaml.toString(), StandardCharsets.UTF_8);
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
