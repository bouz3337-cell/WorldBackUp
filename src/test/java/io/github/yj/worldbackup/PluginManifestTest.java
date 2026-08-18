package io.github.yj.worldbackup;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 배포되는 {@code plugin.yml} 을 검증한다.
 *
 * <p>이 파일이 어긋나면 플러그인은 <b>아예 로드되지 않거나</b>, 로드되고도 권한이 조용히
 * 어긋난다. 서버를 켜 보기 전에는 알 수 없는 오류인데, 켜 보는 것은 자동 검증이 아니다.</p>
 *
 * <p>권한이 특히 조용하다. 코드가 선언되지 않은 권한을 묻으면 일반 사용자에게는 언제나 거짓이
 * 되므로, 오타 하나가 "OP 말고는 아무도 못 쓰는 명령" 을 만들어 낸다. 그런데 개발·시험은 대개
 * OP 로 하기 때문에 그 상태가 정상으로 보인다.</p>
 */
class PluginManifestTest {

    private static final String Q = Character.toString(34);
    private static final Path MANIFEST = Path.of("src/main/resources/plugin.yml");
    private static final Path SOURCES = Path.of("src/main/java");

    @Test
    void theManifestParsesAndNamesTheRealMainClass() throws IOException {
        YamlConfiguration yaml = load();

        assertEquals("WorldBackUp", yaml.getString("name"),
                "데이터 폴더 이름(plugins/WorldBackUp)이 문서 곳곳에 적혀 있다");
        assertEquals("26.2", yaml.getString("api-version"));

        String main = yaml.getString("main");
        assertEquals("io.github.yj.worldbackup.WorldBackUpPlugin", main);
        assertTrue(Files.isRegularFile(SOURCES.resolve(main.replace('.', '/') + ".java")),
                "main 이 가리키는 클래스가 실제로 있어야 한다: " + main);
    }

    /** 버전은 빌드가 채워 넣는다. 자리표가 사라지면 배포본에 문자열 그대로 나간다. */
    @Test
    void theVersionPlaceholderSurvives() throws IOException {
        assertTrue(Files.readString(MANIFEST, StandardCharsets.UTF_8).contains("${version}"),
                "processResources 가 이 자리표를 채운다");
    }

    /**
     * 코드가 묻는 권한과 선언된 권한이 <b>정확히</b> 같아야 한다.
     *
     * <p>선언 없이 묻으면 일반 사용자에게 언제나 거짓이고(오타 하나로 명령이 잠긴다),
     * 묻지 않는데 선언되어 있으면 관리자가 권한을 준 뒤 아무 일도 일어나지 않는다.</p>
     *
     * <p>선언은 <b>원문에서</b> 읽는다. {@code YamlConfiguration} 은 키 안의 점을 경로
     * 구분자로 보아 {@code worldbackup.use} 를 {@code worldbackup} → {@code use} 로 쪼개므로,
     * 그것으로 세면 권한 이름이 사라진다.</p>
     */
    @Test
    void everyPermissionTheCodeAsksAboutIsDeclared() throws IOException {
        Set<String> declared = matches(Files.readString(MANIFEST, StandardCharsets.UTF_8),
                "(?m)^[ ]{2}(worldbackup[.][a-z]+):");

        Set<String> asked = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                asked.addAll(matches(Files.readString(file, StandardCharsets.UTF_8),
                        Q + "(worldbackup[.][a-z]+)" + Q));
            }
        }

        assertFalse(asked.isEmpty(), "소스에서 권한을 하나도 못 찾았다면 이 검사가 무력하다");
        assertFalse(declared.isEmpty(), "plugin.yml 에서 선언을 하나도 못 찾았다면 이 검사가 무력하다");
        assertEquals(asked, declared, "왼쪽이 코드가 묻는 권한, 오른쪽이 plugin.yml 선언");
    }

    /** {@code worldbackup.admin} 은 나머지 전부를 포함한다고 문서가 약속한다. */
    @Test
    void theAdminPermissionCoversEveryOtherPermission() throws IOException {
        String text = Files.readString(MANIFEST, StandardCharsets.UTF_8);

        Set<String> declared = matches(text, "(?m)^[ ]{2}(worldbackup[.][a-z]+):");
        Set<String> children = matches(text, "(?m)^[ ]{6}(worldbackup[.][a-z]+): true");

        Set<String> others = new TreeSet<>(declared);
        assertTrue(others.remove("worldbackup.admin"), "worldbackup.admin 이 선언되어 있어야 한다");

        assertEquals(others, children, "admin 이 빠뜨린 권한이 있으면 문서가 거짓말이 된다");
    }

    private static Set<String> matches(String text, String regex) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }


    /** 명령어는 Brigadier 로 등록한다. commands 블록이 있으면 이름이 겹쳐 등록이 어긋난다. */
    @Test
    void theManifestDeclaresNoCommands() throws IOException {
        assertFalse(load().contains("commands"),
                "plugin.yml 의 commands 블록과 Brigadier 등록이 겹치면 안 된다");
    }

    private static YamlConfiguration load() throws IOException {
        assertTrue(Files.isRegularFile(MANIFEST), "배포본을 찾지 못했습니다: " + MANIFEST.toAbsolutePath());
        return YamlConfiguration.loadConfiguration(MANIFEST.toFile());
    }
}
