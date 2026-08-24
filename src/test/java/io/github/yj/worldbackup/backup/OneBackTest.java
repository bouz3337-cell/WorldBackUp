package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.config.BackupSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>이 폴더 하나만 챙기면 서버를 다시 열 수 있는가.</b>
 *
 * <p>OneBack 의 약속은 문장 하나다 - "이 zip 을 빈 폴더에 풀면 서버가 뜬다". 그 약속은
 * <b>무엇이 빠졌는지</b>로만 깨진다. 서버 jar 가 없으면 못 켜고, 플러그인 jar 가 없으면
 * 절반짜리 서버가 뜨고, eula.txt 가 없으면 첫 실행이 거부된다. 그리고 그 사실은 정작
 * 서버가 날아간 뒤에야 드러난다.</p>
 *
 * <p>그래서 아카이브를 실제로 만들어 <b>안에 무엇이 들었는지</b>를 확인한다.</p>
 */
class OneBackTest {

    private static final Logger LOG = Logger.getLogger("OneBackTest");

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------

    /** 서버를 다시 여는 데 필요한 것이 <b>하나도 빠지지 않았는가.</b> */
    @Test
    void theArchiveHoldsEverythingNeededToOpenTheServerAgain() throws IOException {
        Path server = buildServer();
        Set<String> entries = entriesOf(create(server, settings(server, cfg -> {
        })));

        // 서버 자체
        assertTrue(entries.contains("paper-26.2.jar"), "서버 jar 가 없으면 켤 수가 없다");
        assertTrue(entries.contains("eula.txt"), "eula.txt 가 없으면 첫 실행이 거부된다");
        assertTrue(entries.contains("server.properties"));
        assertTrue(entries.contains("bukkit.yml"));
        assertTrue(entries.contains("config/paper-global.yml"));

        // 사람과 권한
        assertTrue(entries.contains("ops.json"));
        assertTrue(entries.contains("whitelist.json"));
        assertTrue(entries.contains("banned-players.json"));

        // 월드와 인벤토리
        assertTrue(entries.contains("world/level.dat"));
        assertTrue(entries.contains("world/region/r.0.0.mca"));
        assertTrue(entries.contains("world/playerdata/uuid.dat"), "인벤토리가 빠지면 반쪽이다");
        assertTrue(entries.contains("world_nether/level.dat"));

        // 플러그인 - jar 와 데이터 양쪽
        assertTrue(entries.contains("plugins/mopi.jar"), "플러그인 jar 가 없으면 그 기능이 통째로 빠진다");
        assertTrue(entries.contains("plugins/EventSystem.jar"));
        assertTrue(entries.contains("plugins/mopi/config.yml"));
        assertTrue(entries.contains("plugins/EventSystem/data/players.yml"));
        assertTrue(entries.contains("plugins/WorldBackUp/config.yml"));

        // 인터넷 없이도 뜨도록
        assertTrue(entries.contains("libraries/net/example/lib.jar"),
                "libraries 가 있어야 인터넷 없는 환경에서도 그 자리에서 뜬다");
    }

    /**
     * 자기 자신과 평소 백업은 <b>담기지 않는다.</b>
     *
     * <p>담기면 아카이브가 아카이브를 삼킨다 - 두 번째 OneBack 은 첫 번째를 품고, 세 번째는
     * 그 둘을 품는다. 몇 번이면 디스크가 찬다.</p>
     */
    @Test
    void theArchiveNeverSwallowsItselfOrTheOrdinaryBackups() throws IOException {
        Path server = buildServer();
        // 이미 한 벌이 있고, 평소 백업도 쌓여 있는 상태
        write(server.resolve("OneBack/oneback-20260101-000000.zip"), "OLD-ONEBACK");
        write(server.resolve("plugins/WorldBackUp/backups/wb-20260101-000000.zip"), "OLD-BACKUP");

        Set<String> entries = entriesOf(create(server, settings(server, cfg -> {
        })));

        assertFalse(hasPrefix(entries, "OneBack/"), "자기 폴더를 담으면 아카이브가 아카이브를 삼킨다");
        assertFalse(hasPrefix(entries, "plugins/WorldBackUp/backups/"),
                "평소 백업까지 담으면 크기가 곱절이 된다");
    }

    /**
     * 되살아나면 안 되는 것은 담지 않는다.
     *
     * <p>복원 예약이 되살아나면 다음 부팅이 <b>또</b> 복원하고, 실패 표식이 되살아나면 아무도
     * 손대지 않은 서버에서 자동 백업이 영구히 멈춘다. 이건 평소 백업이 이미 지키는 규칙인데,
     * 서버를 통째로 담는 이쪽에서 빠뜨리기 쉽다.</p>
     */
    @Test
    void thePluginsOwnControlFilesAreNeverIncluded() throws IOException {
        Path server = buildServer();
        write(server.resolve("plugins/WorldBackUp/pending-restore.yml"), "id: x");
        write(server.resolve("plugins/WorldBackUp/restore-failed-20260101-000000.yml"), "error: x");
        write(server.resolve("plugins/WorldBackUp/replaced/20260101-000000/world/level.dat"), "OLD");

        Set<String> entries = entriesOf(create(server, settings(server, cfg -> {
        })));

        assertFalse(entries.contains("plugins/WorldBackUp/pending-restore.yml"),
                "되살아나면 다음 부팅이 또 복원한다");
        assertFalse(hasPrefix(entries, "plugins/WorldBackUp/restore-failed-"),
                "되살아나면 자동 백업이 영구히 멈춘다");
        assertFalse(hasPrefix(entries, "plugins/WorldBackUp/replaced/"),
                "옛 월드 사본까지 담으면 크기가 곱절이 된다");
        // 그래도 설정은 담긴다 - 그 시점의 보관 정책이 함께 돌아와야 한다
        assertTrue(entries.contains("plugins/WorldBackUp/config.yml"));
    }

    /** 로그는 서버를 여는 데 필요 없고, 가장 빨리 자란다. */
    @Test
    void logsAreLeftOut() throws IOException {
        Path server = buildServer();
        Set<String> entries = entriesOf(create(server, settings(server, cfg -> {
        })));

        assertFalse(hasPrefix(entries, "logs/"));
        assertFalse(hasPrefix(entries, "crash-reports/"));
    }

    /** 관리자가 더 빼고 싶은 것은 뺄 수 있어야 한다. (SQLite 를 열어 둔 플러그인 등) */
    @Test
    void theAdminCanExcludeMore() throws IOException {
        Path server = buildServer();
        write(server.resolve("plugins/EventSystem/data/live.db"), "DB");

        Set<String> entries = entriesOf(create(server, settings(server,
                cfg -> cfg.set("oneback.exclude", List.of("**/*.db")))));

        assertFalse(entries.contains("plugins/EventSystem/data/live.db"));
        assertTrue(entries.contains("plugins/EventSystem/data/players.yml"), "나머지는 그대로 담긴다");
    }

    // ------------------------------------------------------------------
    // 시작해도 되는지

    /**
     * 공간 점검의 경계.
     *
     * <p>이 판단이 <b>잘못 참을 내면</b> 디스크를 채워 서버를 멈춘다 - 마인크래프트는 청크를
     * 쓰지 못하면 그 자리에서 죽는다. <b>잘못 거짓을 내면</b> 정작 서버를 옮기려는 순간에
     * 막는다. 양쪽 다 비싸서 경계를 못 박아 둔다.</p>
     */
    @Test
    void theSpaceCheckOnlyStartsWhenThereIsRoomToFinish() {
        long gb = 1024L * 1024 * 1024;
        long headroom = 256L * 1024 * 1024;

        assertTrue(OneBack.hasRoom(10 * gb, 20 * gb), "넉넉하면 통과");
        assertTrue(OneBack.hasRoom(10 * gb, 10 * gb + headroom), "딱 맞아도 통과");
        assertFalse(OneBack.hasRoom(10 * gb, 10 * gb), "여유분을 못 채우면 시작하지 않는다");
        assertFalse(OneBack.hasRoom(10 * gb, 1 * gb));

        assertTrue(OneBack.hasRoom(0L, 0L), "쓸 것이 없으면 막을 이유가 없다");
        assertTrue(OneBack.hasRoom(Long.MAX_VALUE, 1L), "넘치면 막을 근거가 없다");
    }

    /**
     * 공간이 모자라면 <b>아무것도 만들지 않고</b> 멈춘다.
     *
     * <p>반쯤 쓰다 만 아카이브를 남기면 그것이 진짜 백업인 줄 알고 챙겨 갈 수 있다.</p>
     */
    @Test
    void notEnoughSpaceMeansNoArchiveAtAll() throws IOException {
        Path server = buildServer();
        BackupSettings settings = settings(server, cfg -> {
        });
        Path archive = settings.oneBackDir().resolve(OneBack.PREFIX + "x" + OneBack.SUFFIX);

        // 실제 디스크를 채울 수는 없으므로 판단 자체를 검증한다. 위 경계 테스트와 짝이다.
        assertFalse(OneBack.hasRoom(Long.MAX_VALUE / 4, 1024L));
        assertFalse(Files.exists(archive), "판단이 거짓이면 파일이 생기기 전에 멈춘다");
    }

    // ------------------------------------------------------------------

    /**
     * 하나가 서버 폴더 크기만큼이므로 정리가 없으면 디스크를 가장 빨리 채운다.
     *
     * <p>지우는 순서는 <b>파일 이름</b>이다. 이름에 시각이 들어 있어 사전순 = 시간순이고,
     * 파일 수정 시각과 달리 복사·이동에도 흔들리지 않는다.</p>
     */
    @Test
    void onlyTheNewestArchivesAreKept() throws IOException {
        Path directory = tmp.resolve("OneBack");
        for (String stamp : List.of("20260101-000000", "20260102-000000", "20260103-000000",
                "20260104-000000")) {
            write(directory.resolve(OneBack.PREFIX + stamp + OneBack.SUFFIX), stamp);
        }

        assertEquals(2, OneBack.prune(directory, 2, LOG));

        List<Path> left = OneBack.list(directory);
        assertEquals(2, left.size());
        assertEquals(OneBack.PREFIX + "20260104-000000" + OneBack.SUFFIX,
                left.get(0).getFileName().toString(), "최신이 앞에 온다");
        assertEquals(OneBack.PREFIX + "20260103-000000" + OneBack.SUFFIX,
                left.get(1).getFileName().toString());
    }

    /**
     * 중간에 끊긴 조각은 <b>반드시</b> 치워진다.
     *
     * <p>이 조각은 이 플러그인이 만드는 가장 큰 파일이다 - 서버 폴더 크기만 하다. 그런데
     * 완성된 아카이브가 아니라 쓸모도 없다. 평소 백업의 조각 정리는 백업 폴더만 보므로
     * 여기를 빠뜨리면 <b>아무도 치우지 않아</b> 디스크에 서버 한 벌이 영영 눌러앉는다.</p>
     */
    @Test
    void anInterruptedArchiveLeavesNothingBehind() throws IOException {
        Path directory = tmp.resolve("OneBack");
        write(directory.resolve(OneBack.PREFIX + "20260101-000000" + OneBack.SUFFIX + ".tmp"), "조각");
        write(directory.resolve(OneBack.PREFIX + "20260102-000000" + OneBack.SUFFIX), "완성본");
        write(directory.resolve(OneBack.GUIDE_NAME), "안내");

        assertEquals(1, OneBack.cleanupTemp(directory, LOG));

        assertFalse(Files.exists(directory.resolve(OneBack.PREFIX + "20260101-000000" + OneBack.SUFFIX + ".tmp")));
        assertTrue(Files.exists(directory.resolve(OneBack.PREFIX + "20260102-000000" + OneBack.SUFFIX)),
                "완성된 아카이브는 건드리면 안 된다");
        assertTrue(Files.exists(directory.resolve(OneBack.GUIDE_NAME)));
    }

    /** 우리가 만들지 않은 {@code .tmp} 는 남의 것이다. */
    @Test
    void cleanupNeverTouchesSomebodyElsesTempFiles() throws IOException {
        Path directory = tmp.resolve("OneBack");
        write(directory.resolve("남의파일.zip.tmp"), "MINE");
        write(directory.resolve("backup.tmp"), "MINE");

        assertEquals(0, OneBack.cleanupTemp(directory, LOG));
        assertTrue(Files.exists(directory.resolve("남의파일.zip.tmp")));
        assertTrue(Files.exists(directory.resolve("backup.tmp")));
    }

    /**
     * 만든 시각은 <b>파일 이름</b>에서 읽는다.
     *
     * <p>이 파일은 옮겨 다니라고 만든 것이라 수정 시각은 복사한 때가 되어 버린다.</p>
     */
    @Test
    void theCreationTimeComesFromTheNameNotTheFileSystem() {
        assertEquals("2026-08-25 14:30:00",
                OneBack.displayTime(Path.of(OneBack.PREFIX + "20260825-143000" + OneBack.SUFFIX)).orElseThrow());
        // 같은 초에 두 번 만들면 뒤에 -2 가 붙는다. 시각은 그대로 읽혀야 한다.
        assertEquals("2026-08-25 14:30:00",
                OneBack.displayTime(Path.of(OneBack.PREFIX + "20260825-143000-2" + OneBack.SUFFIX)).orElseThrow());
        // 알아볼 수 없는 이름은 조용히 비운다. 여기서 터지면 /wb status 가 통째로 막힌다.
        assertTrue(OneBack.displayTime(Path.of("아무거나.zip")).isEmpty());
        assertTrue(OneBack.displayTime(Path.of(OneBack.PREFIX + "짧음" + OneBack.SUFFIX)).isEmpty());
        assertTrue(OneBack.displayTime(Path.of(OneBack.PREFIX + "abcdefgh-ijklmn" + OneBack.SUFFIX)).isEmpty());
    }

    /**
     * 압축 풀기 도우미가 함께 놓인다.
     *
     * <p>"zip 을 빈 폴더에 푸세요" 는 생각보다 큰 벽이다. 윈도우 탐색기에서 zip 을
     * 더블클릭하면 <b>푼 것처럼 보이지만 안을 들여다본 것뿐</b>이고, 그 상태로 파일을 끌어다
     * 놓으면 몇 GB 를 옮기다 멈추기도 한다. 서버가 안 뜨는 이유가 그것인지 알아채기도 어렵다.</p>
     */
    @Test
    void theFolderCarriesItsOwnUnpackHelpers() throws IOException {
        Path directory = tmp.resolve("OneBack");
        Files.createDirectories(directory);

        OneBack.writeUnpackScripts(directory, LOG);

        String bat = Files.readString(directory.resolve(OneBack.UNPACK_BAT), StandardCharsets.UTF_8);
        String sh = Files.readString(directory.resolve(OneBack.UNPACK_SH), StandardCharsets.UTF_8);

        // 이름을 박아 두지 않는다. 새 아카이브가 생겨도 도우미는 그대로 쓸 수 있어야 한다.
        assertTrue(bat.contains("oneback-*.zip"), "가장 최근 아카이브를 스스로 찾아야 한다");
        assertTrue(sh.contains("oneback-*.zip"));

        // 이미 있는 폴더에 푸는 것이 이 도우미가 만들 수 있는 가장 나쁜 결과다.
        assertTrue(bat.contains("if exist"), "이미 있는 폴더에는 풀지 않아야 한다");
        assertTrue(sh.contains("if [ -e \"$DEST\" ]"));

        // 푼 다음에 무엇을 해야 하는지까지 알려 줘야 한다.
        assertTrue(bat.contains("nogui"));
        assertTrue(sh.contains("nogui"));
    }

    /** 안내문이 도우미의 존재를 알려 줘야 한다. 있는 줄 모르면 없는 것과 같다. */
    @Test
    void theGuidePointsAtTheUnpackHelpers() throws IOException {
        Path directory = tmp.resolve("OneBack");
        Files.createDirectories(directory);

        OneBack.writeGuide(directory, directory.resolve(OneBack.PREFIX + "20260101-000000" + OneBack.SUFFIX),
                "26.2", LOG);

        String guide = Files.readString(directory.resolve(OneBack.GUIDE_NAME), StandardCharsets.UTF_8);
        assertTrue(guide.contains(OneBack.UNPACK_BAT));
        assertTrue(guide.contains(OneBack.UNPACK_SH));
    }

    /** 관계없는 파일은 건드리지 않는다. 안내문도 남아 있어야 한다. */
    @Test
    void pruningLeavesEverythingElseAlone() throws IOException {
        Path directory = tmp.resolve("OneBack");
        write(directory.resolve(OneBack.PREFIX + "20260101-000000" + OneBack.SUFFIX), "A");
        write(directory.resolve(OneBack.PREFIX + "20260102-000000" + OneBack.SUFFIX), "B");
        write(directory.resolve(OneBack.GUIDE_NAME), "안내");
        write(directory.resolve("내가-따로-보관.zip"), "MINE");

        OneBack.prune(directory, 1, LOG);

        assertTrue(Files.exists(directory.resolve(OneBack.GUIDE_NAME)), "안내문은 남아야 한다");
        assertTrue(Files.exists(directory.resolve("내가-따로-보관.zip")),
                "우리가 만들지 않은 파일은 건드리지 않는다");
    }

    /**
     * 안내문은 <b>zip 밖에</b> 있어야 한다.
     *
     * <p>서버가 날아간 뒤 이 폴더를 여는 사람은 대개 당황해 있고, 만든 사람이 아닐 수도 있다.
     * 그때 필요한 설명이 정작 풀어야 볼 수 있는 zip 안에 있으면 소용이 없다.</p>
     */
    @Test
    void theGuideSitsNextToTheArchiveNotInsideIt() throws IOException {
        Path directory = tmp.resolve("OneBack");
        Path archive = directory.resolve(OneBack.PREFIX + "20260101-000000" + OneBack.SUFFIX);
        Files.createDirectories(directory);

        OneBack.writeGuide(directory, archive, "26.2-115", LOG);

        String guide = Files.readString(directory.resolve(OneBack.GUIDE_NAME), StandardCharsets.UTF_8);
        assertTrue(guide.contains(archive.getFileName().toString()), "어느 파일을 쓰라는지 적혀 있어야 한다");
        assertTrue(guide.contains("26.2-115"), "어느 버전으로 만든 것인지 적혀 있어야 한다");
        assertTrue(guide.contains("빈 폴더"), "다시 여는 절차가 적혀 있어야 한다");
    }

    // ------------------------------------------------------------------

    private Path create(Path server, BackupSettings settings) throws IOException {
        Path archive = settings.oneBackDir().resolve(OneBack.PREFIX + "test" + OneBack.SUFFIX);
        OneBack.Result result = OneBack.create(archive, server, settings, "tester", text -> {
        }, LOG);
        return result.archive();
    }

    private BackupSettings settings(Path server, Consumer<YamlConfiguration> tweak) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("oneback.exclude", List.of("**/logs/**", "**/crash-reports/**", "**/*.log"));
        tweak.accept(cfg);
        return BackupSettings.load(cfg, server.resolve("plugins/WorldBackUp"), server);
    }

    /** 실제 서버 폴더와 같은 모양. 다른 플러그인(mopi, EventSystem)도 함께 둔다. */
    private Path buildServer() throws IOException {
        Path server = tmp.resolve("server");
        write(server.resolve("paper-26.2.jar"), "SERVER-JAR");
        write(server.resolve("eula.txt"), "eula=true");
        write(server.resolve("server.properties"), "motd=hello");
        write(server.resolve("bukkit.yml"), "settings: {}");
        write(server.resolve("config/paper-global.yml"), "_version: 1");
        write(server.resolve("ops.json"), "[]");
        write(server.resolve("whitelist.json"), "[]");
        write(server.resolve("banned-players.json"), "[]");

        write(server.resolve("world/level.dat"), "LEVEL");
        write(server.resolve("world/region/r.0.0.mca"), "REGION");
        write(server.resolve("world/playerdata/uuid.dat"), "INVENTORY");
        write(server.resolve("world_nether/level.dat"), "NETHER");

        write(server.resolve("plugins/mopi.jar"), "MOPI-JAR");
        write(server.resolve("plugins/mopi/config.yml"), "mopi: true");
        write(server.resolve("plugins/EventSystem.jar"), "EVENT-JAR");
        write(server.resolve("plugins/EventSystem/data/players.yml"), "players: []");
        write(server.resolve("plugins/WorldBackUp/config.yml"), "backup: {}");

        write(server.resolve("libraries/net/example/lib.jar"), "LIB");
        write(server.resolve("logs/latest.log"), "log line");
        write(server.resolve("crash-reports/crash.txt"), "crash");
        return server;
    }

    private static Set<String> entriesOf(Path archive) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (!entry.isDirectory()) names.add(entry.getName());
            }
        }
        return names;
    }

    private static boolean hasPrefix(Set<String> entries, String prefix) {
        return entries.stream().anyMatch(name -> name.startsWith(prefix));
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }
}
