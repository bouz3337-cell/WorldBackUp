package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.restore.PendingRestore;
import io.github.yj.worldbackup.util.FileUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>서버가 통째로 사라진 다음, zip 하나로 다시 서는가.</b>
 *
 * <p>{@link OneBackTest} 은 아카이브 <b>안에 무엇이 들었는지</b>를 본다. 여기서 묻는 것은
 * 그다음 질문이다 - 그 목록이 맞다고 해서 되살아난다는 뜻은 아니다. 실제로 풀어 봐야 내용이
 * 살아 있는지, 경로가 그대로인지, 되살아나면 안 되는 것이 딸려 오지 않는지를 알 수 있다.</p>
 *
 * <p>그래서 이 시험은 <b>원본 서버 폴더를 지운다.</b> 지우고 나면 비교할 대상도 없으므로
 * 지우기 전에 통째로 떠 두고, 되살린 것과 그 사본을 맞춘다. 원본이 남아 있으면 어딘가에서
 * 원본을 참조하고도 통과하는 시험이 되기 쉬운데, 그건 정작 재난이 났을 때 아무것도 보증하지
 * 못한다.</p>
 *
 * <p>푸는 쪽은 <b>이 플러그인의 코드가 아니다.</b> 재난 상황에서 관리자가 쓰는 것은 알집이나
 * {@code unzip} 이지 이 플러그인이 아니므로, 여기서도 평범한 zip 읽기로만 푼다.</p>
 */
class OneBackDisasterTest {

    private static final Logger LOG = Logger.getLogger("OneBackDisasterTest");

    /** 아카이브가 만드는 자기 설명 파일들. 원본에는 없지만 들어 있는 것이 맞다. */
    private static final List<String> ARCHIVE_OWN = List.of(
            BackupEntry.META_ENTRY, Manifest.ENTRY);

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------

    /**
     * 서버 폴더가 사라진 뒤, 내려받아 둔 zip 하나로 <b>전부</b> 돌아온다.
     *
     * <p>파일 이름을 하나하나 적어 두고 확인하지 않는다. 그런 시험은 나중에 생긴 파일을
     * 놓친다 - 적어 두지 않은 것은 빠져도 통과한다. 대신 지우기 전의 서버를 통째로 떠 두고
     * <b>차집합이 비어 있는지</b>를 본다. 빠진 것도, 군더더기도 그렇게 걸린다.</p>
     */
    @Test
    void everyFileComesBackByteForByteAfterTheServerFolderIsGone() throws IOException {
        Path server = buildServer();
        Map<String, byte[]> before = snapshot(server);

        Path archive = createOneBack(server);

        // 관리자가 zip 하나를 자기 PC 로 내려받는다. 서버 폴더 밖이어야 한다.
        Path offsite = takeOffsite(archive);

        // 재난. 컨테이너가 통째로 날아간다.
        FileUtil.deleteRecursively(server);
        assertFalse(Files.exists(server), "재난을 흉내 내지 못했다면 이 시험은 아무것도 보증하지 않는다");

        // 빈 자리에 zip 만으로 다시 세운다.
        Path revived = tmp.resolve("새-서버");
        unzip(offsite, revived);
        Map<String, byte[]> after = snapshot(revived);

        TreeSet<String> missing = new TreeSet<>(before.keySet());
        missing.removeIf(OneBackDisasterTest::meantToBeLeftOut);
        missing.removeAll(after.keySet());
        assertTrue(missing.isEmpty(), "이 파일들이 돌아오지 않았다: " + missing);

        TreeSet<String> extra = new TreeSet<>(after.keySet());
        extra.removeAll(before.keySet());
        ARCHIVE_OWN.forEach(extra::remove);
        assertTrue(extra.isEmpty(), "원본에 없던 것이 딸려 왔다: " + extra);

        for (Map.Entry<String, byte[]> file : before.entrySet()) {
            if (meantToBeLeftOut(file.getKey())) {
                continue;
            }
            assertArrayEquals(file.getValue(), after.get(file.getKey()),
                    file.getKey() + " 의 내용이 달라졌다");
        }
    }

    /**
     * 이진 파일이 <b>한 바이트도</b> 달라지지 않는다.
     *
     * <p>월드는 전부 이진이다({@code .mca}, {@code level.dat}). 어딘가에서 텍스트로 한 번
     * 읽고 쓰면 인코딩을 타고 조용히 망가지는데, 그 사실은 되돌린 월드를 열어 보고서야
     * 드러난다. 그때는 이미 원본이 없다.</p>
     */
    @Test
    void aBinaryWorldFileSurvivesIntact() throws IOException {
        Path server = buildServer();
        byte[] original = Files.readAllBytes(server.resolve("world/region/r.0.0.mca"));
        assertEquals(256, original.length, "0부터 255까지 전부 들어 있어야 의미가 있는 시험이다");

        Path offsite = takeOffsite(createOneBack(server));
        FileUtil.deleteRecursively(server);

        Path revived = tmp.resolve("새-서버");
        unzip(offsite, revived);

        assertArrayEquals(original, Files.readAllBytes(revived.resolve("world/region/r.0.0.mca")));
    }

    /** 되살린 폴더에 서버를 <b>켤 수 있는</b> 것이 다 있는가. zip 목록이 아니라 실제 파일로 본다. */
    @Test
    void therevivedFolderHasWhatItTakesToStart() throws IOException {
        Path server = buildServer();
        Path offsite = takeOffsite(createOneBack(server));
        FileUtil.deleteRecursively(server);

        Path revived = tmp.resolve("새-서버");
        unzip(offsite, revived);

        assertTrue(Files.isRegularFile(revived.resolve("paper-26.2.jar")), "서버 jar 가 없으면 못 켠다");
        assertTrue(Files.isRegularFile(revived.resolve("eula.txt")), "eula 가 없으면 첫 실행이 거부된다");
        assertTrue(Files.isRegularFile(revived.resolve("server.properties")));
        assertTrue(Files.isRegularFile(revived.resolve("world/level.dat")), "level.dat 에 시드가 들어 있다");
        assertTrue(Files.isRegularFile(revived.resolve("world/playerdata/uuid.dat")), "인벤토리가 빠지면 반쪽이다");
        assertTrue(Files.isRegularFile(revived.resolve("plugins/mopi.jar")));
        assertTrue(Files.isRegularFile(revived.resolve("plugins/mopi/mineprotect.db")), "잠금과 기록이 여기 있다");
        assertTrue(Files.isRegularFile(revived.resolve("libraries/net/example/lib.jar")),
                "libraries 가 있어야 인터넷 없이도 그 자리에서 뜬다");
        assertTrue(Files.isRegularFile(revived.resolve("plugins/WorldBackUp/config.yml")),
                "보관 정책이 없으면 되살린 서버가 예전과 다르게 백업한다");
    }

    /**
     * 되살아나면 <b>안 되는</b> 것은 딸려 오지 않는다.
     *
     * <p>제일 위험한 것이 복원 예약이다. 그것이 되살아난 서버에 들어 있으면 <b>다음 부팅이
     * 또 복원한다</b> - 방금 되살린 것을 그 위에 덮어쓰면서. 백업 폴더가 딸려 오면 zip 이
     * 곱절이 되고, {@code session.lock} 이 딸려 오면 월드가 이미 열려 있다고 나온다.</p>
     */
    @Test
    void nothingThatMustNotComeBackComesBack() throws IOException {
        Path server = buildServer();
        Path offsite = takeOffsite(createOneBack(server));
        FileUtil.deleteRecursively(server);

        Path revived = tmp.resolve("새-서버");
        unzip(offsite, revived);

        assertFalse(Files.exists(revived.resolve("plugins/WorldBackUp/" + PendingRestore.FILE_NAME)),
                "복원 예약이 살아 오면 되살린 서버가 다음 부팅에 스스로를 덮어쓴다");
        assertFalse(Files.exists(revived.resolve("plugins/WorldBackUp/backups")),
                "평소 백업까지 담으면 zip 이 곱절이 된다");
        assertFalse(Files.exists(revived.resolve("OneBack")), "아카이브가 아카이브를 삼킨다");
        assertFalse(Files.exists(revived.resolve("world/session.lock")),
                "월드가 이미 열려 있다고 나온다");
        assertFalse(Files.exists(revived.resolve("logs")));
        assertFalse(Files.exists(revived.resolve("crash-reports")));
        assertFalse(Files.exists(revived.resolve("debug.log")));
    }

    /**
     * zip 만 챙겨도 그것이 무엇인지 알 수 있다.
     *
     * <p>안내문({@code 읽어주세요.txt})은 아카이브 <b>밖</b>에 있다 - 풀기 전에 읽어야 하므로.
     * 그 말은 zip 하나만 내려받은 사람에게는 안내문이 없다는 뜻이다. 몇 달 뒤 파일 하나만
     * 발견한 사람이 최소한 <b>무엇이고 언제 것인지</b>는 알 수 있어야 한다.</p>
     */
    @Test
    void theZipAloneStillSaysWhatItIs() throws IOException {
        Path server = buildServer();
        Path offsite = takeOffsite(createOneBack(server));
        FileUtil.deleteRecursively(server);

        Path revived = tmp.resolve("새-서버");
        unzip(offsite, revived);

        String meta = Files.readString(revived.resolve(BackupEntry.META_ENTRY), StandardCharsets.UTF_8);
        assertTrue(meta.contains("kind: oneback"), "무엇인지 적혀 있어야 한다:\n" + meta);
        assertTrue(meta.contains("created-at-text:"), "언제 것인지 사람이 읽을 수 있어야 한다:\n" + meta);
        assertTrue(meta.contains("file-count:"), "몇 개가 들었는지 적혀 있으면 온전한지 가늠할 수 있다");
    }

    /**
     * 서버 폴더 안에만 둔 아카이브는 <b>서버와 함께 사라진다.</b>
     *
     * <p>OneBack 폴더는 서버 폴더 안에 산다. 그래서 이 기능은 "실수로 월드를 날렸다" 까지는
     * 지켜 주지만, 정작 만들어진 이유인 "폴더가 통째로 사라졌다" 는 <b>내려받아 두지 않으면
     * 지켜 주지 못한다.</b> 위의 시험들이 전부 zip 을 밖으로 내보내고 시작하는 이유이고,
     * 그 한 단계를 빼면 어떻게 되는지를 여기서 못 박아 둔다.</p>
     */
    @Test
    void anArchiveLeftInsideTheServerFolderDiesWithIt() throws IOException {
        Path server = buildServer();
        Path archive = createOneBack(server);
        assertTrue(archive.startsWith(server), "OneBack 폴더는 서버 폴더 안에 있다");

        FileUtil.deleteRecursively(server);

        assertFalse(Files.exists(archive),
                "서버 폴더 안에 둔 사본은 서버와 같이 사라진다 - 내려받아 두는 것이 이 기능의 마지막 한 단계다");
    }

    /**
     * 아카이브 안의 경로가 <b>서버 폴더 밖을 가리키지 않는다.</b>
     *
     * <p>{@code ../} 나 절대 경로가 든 zip 은 푸는 쪽이 어디에 풀든 엉뚱한 자리에 파일을
     * 쓴다. 우리가 만든 zip 이니 그럴 리 없다고 두면, 그럴 리 없다는 사실이 어디에도 적혀
     * 있지 않게 된다. 관리자가 어떤 압축 도구로 풀어도 안전해야 한다.</p>
     */
    @Test
    void noEntryPointsOutsideTheFolderItIsUnpackedInto() throws IOException {
        Path server = buildServer();
        Path archive = createOneBack(server);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName();
                assertFalse(name.startsWith("/") || name.startsWith("\\"), "절대 경로: " + name);
                assertFalse(name.contains(".."), "상위 폴더를 가리킨다: " + name);
                assertFalse(name.length() > 1 && name.charAt(1) == ':', "드라이브 문자: " + name);
            }
        }
    }

    // ------------------------------------------------------------------
    // 거들기

    /** 서버 폴더 밖으로 내보낸다. 관리자가 패널에서 내려받는 그 한 단계다. */
    private Path takeOffsite(Path archive) throws IOException {
        Path offsite = tmp.resolve("내-PC").resolve(archive.getFileName().toString());
        Files.createDirectories(offsite.getParent());
        Files.copy(archive, offsite);
        return offsite;
    }

    private Path createOneBack(Path server) throws IOException {
        BackupSettings settings = settings(server);
        Path archive = settings.oneBackDir().resolve(OneBack.PREFIX + "20260826-050000" + OneBack.SUFFIX);
        return OneBack.create(archive, server, settings, "tester", text -> {
        }, LOG).archive();
    }

    private BackupSettings settings(Path server) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("oneback.exclude",
                List.of("**/logs/**", "**/crash-reports/**", "**/*.log", "**/*.log.gz"));
        return BackupSettings.load(cfg, server.resolve("plugins/WorldBackUp"), server);
    }

    /** 담기지 않는 것이 맞는 경로인가. 위 설정과 플러그인이 스스로 붙이는 규칙을 합친 것이다. */
    private static boolean meantToBeLeftOut(String path) {
        return path.startsWith("logs/")
                || path.startsWith("crash-reports/")
                || path.endsWith(".log")
                || path.endsWith(".log.gz")
                || path.startsWith("OneBack/")
                || path.startsWith("plugins/WorldBackUp/backups/")
                || path.endsWith("/session.lock")
                || path.equals("plugins/WorldBackUp/" + PendingRestore.FILE_NAME);
    }

    /**
     * 평범한 zip 읽기. 재난 상황에서 관리자가 쓰는 것은 이 플러그인이 아니라 압축 도구다.
     */
    private static void unzip(Path archive, Path into) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = into.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
        }
        return files;
    }

    /**
     * 실제 서버 폴더에 있는 것들. 되살릴 때 필요한 것과, 절대 딸려 오면 안 되는 것을 모두 둔다.
     */
    private Path buildServer() throws IOException {
        Path server = tmp.resolve("server");

        // 서버 자체
        write(server.resolve("paper-26.2.jar"), "SERVER-JAR");
        write(server.resolve("eula.txt"), "eula=true");
        write(server.resolve("server.properties"), "motd=hello\nlevel-seed=12345");
        write(server.resolve("bukkit.yml"), "settings: {}");
        write(server.resolve("spigot.yml"), "settings: {}");
        write(server.resolve("config/paper-global.yml"), "_version: 1");

        // 사람과 권한
        write(server.resolve("ops.json"), "[{\"name\":\"Admin\"}]");
        write(server.resolve("whitelist.json"), "[]");
        write(server.resolve("banned-players.json"), "[{\"name\":\"Griefer\"}]");
        write(server.resolve("usercache.json"), "[]");

        // 월드 - region 은 0..255 를 그대로 담아 인코딩 사고를 잡는다
        write(server.resolve("world/level.dat"), "LEVEL");
        writeBytes(server.resolve("world/region/r.0.0.mca"), allByteValues());
        write(server.resolve("world/playerdata/uuid.dat"), "INVENTORY");
        write(server.resolve("world/session.lock"), "LOCK");
        write(server.resolve("world_nether/level.dat"), "NETHER");
        write(server.resolve("world_the_end/level.dat"), "END");

        // 플러그인 - jar 와 데이터 양쪽
        write(server.resolve("plugins/mopi.jar"), "MOPI-JAR");
        write(server.resolve("plugins/mopi/config.yml"), "mopi: true");
        write(server.resolve("plugins/mopi/mineprotect.db"), "SQLITE");
        write(server.resolve("plugins/EventSystem.jar"), "EVENT-JAR");
        write(server.resolve("plugins/EventSystem/data/players.yml"), "players: []");
        write(server.resolve("plugins/WorldBackUp.jar"), "WB-JAR");
        write(server.resolve("plugins/WorldBackUp/config.yml"), "backup: {}");

        // 인터넷 없이도 뜨도록
        write(server.resolve("libraries/net/example/lib.jar"), "LIB");
        write(server.resolve("versions/26.2/paper-26.2.jar"), "VERSION-JAR");
        write(server.resolve("cache/mojang_1.21.jar"), "CACHE");

        // 여기서부터는 담기면 안 되는 것들
        write(server.resolve("plugins/WorldBackUp/" + PendingRestore.FILE_NAME), "id: wb-20260101");
        write(server.resolve("plugins/WorldBackUp/backups/wb-20260101-000000.zip"), "OLD-BACKUP");
        write(server.resolve("logs/latest.log"), "log line");
        write(server.resolve("logs/2026-08-01-1.log.gz"), "gzipped");
        write(server.resolve("crash-reports/crash.txt"), "crash");
        write(server.resolve("debug.log"), "debug");
        return server;
    }

    /** 0부터 255까지. 텍스트로 한 번이라도 읽고 쓰면 여기서 무너진다. */
    private static byte[] allByteValues() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    private static void write(Path file, String body) throws IOException {
        writeBytes(file, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(Path file, byte[] body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, body);
    }
}
