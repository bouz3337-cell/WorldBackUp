package io.github.yj.worldbackup.config;

import io.github.yj.worldbackup.backup.RetentionTiers;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>배포되는 {@code config.yml} 자체</b>를 검증한다.
 *
 * <p>이 파일은 새로 설치하는 서버마다 그대로 복사되어 나간다. 그런데 지금까지 아무 테스트도
 * 이 파일을 열어 보지 않았다 - 주석 한 줄을 잘못 고쳐 YAML 이 깨지거나, 코드가 읽는 키를
 * 오타 내거나, 문서에 적은 기본값과 다른 값이 들어가도 빌드는 초록으로 지나간다. 그리고 그
 * 결과는 <b>모든 새 서버</b>가 받는다.</p>
 *
     *
     * <p>실서버에서 이 파일이 깨지면 {@code loadConfiguration} 은 <b>던지지 않고</b> 경고를
     * 기록한 뒤 빈 설정을 돌려준다(재 보고 확인했다 - 테스트에서는 기록할 서버가 없어 그 자리에서
     * NPE 가 난다). 즉 새 서버는 "설정을 읽었다" 고 믿으며 전부 기본값으로 돌고, 관리자는 콘솔
     * 경고 한 줄을 놓치면 알 방법이 없다. 그래서 배포 전에 여기서 직접 열어 본다.</p>
 * <p>실제로 이 파일은 도구로 여러 번 편집됐다. 사람이 눈으로 확인하는 것에 기대지 않는다.</p>
 */
class ShippedConfigTest {

    private static final Path CONFIG = Path.of("src/main/resources/config.yml");
    /** 큰따옴표. 정규식 안에 이스케이프로 넣으면 이 줄 자체가 읽기 어려워진다. */
    private static final String Q = Character.toString(34);

    private static final Path SETTINGS_SOURCE =
            Path.of("src/main/java/io/github/yj/worldbackup/config/BackupSettings.java");

    /** 깨진 YAML 은 로딩 단계에서 조용히 빈 설정이 된다. 그러면 전부 기본값으로 도는데 아무도 모른다. */
    @Test
    void theShippedConfigParses() throws IOException {
        YamlConfiguration cfg = load();
        assertFalse(cfg.getKeys(false).isEmpty(),
                "YAML 이 깨지면 빈 설정이 된다. 그러면 새 서버가 전부 기본값으로 돌면서도 정상으로 보인다");
        assertEquals(Set.of("backup", "targets", "retention", "restore"), cfg.getKeys(false));
    }

    /** 문서(README 설정 요약)가 약속하는 기본값과 배포본이 같아야 한다. */
    @Test
    void theShippedDefaultsMatchWhatIsDocumented() throws IOException {
        BackupSettings settings = settings();

        assertTrue(settings.enabled());
        assertTrue(settings.differential(), "기본값은 differential 이다");
        assertEquals(24, settings.fullEvery());
        assertEquals(30, settings.intervalMinutes());
        assertEquals(4, settings.compressionLevel());
        assertTrue(settings.skipIfNoPlayers());
        assertEquals(48, settings.maxSkippedCycles());
        assertFalse(settings.onShutdown());

        assertEquals(48, settings.maxBackups());
        assertEquals(5, settings.minBackups(), "0 이면 백업이 전멸할 수 있다");
        assertEquals(14, settings.maxAgeDays());
        assertEquals(7, settings.keepDaily());
        assertTrue(settings.protectManual());
        assertEquals(10, settings.maxProtected());
        assertEquals(5L * 1024 * 1024 * 1024, settings.minFreeDiskBytes());
        assertEquals(0L, settings.maxTotalBytes(), "0 = 무제한");

        assertTrue(settings.safetyBackup());
        assertTrue(settings.verifyArchive());
        assertTrue(settings.keepReplacedFiles());
        assertEquals(3, settings.keepReplacedMax());
    }

    /**
     * 배포본의 계단은 문서가 말하는 모양(5단계 · 총 27개)이어야 한다.
     *
     * <p>README 와 config.yml 주석이 "총 27개로 약 16일" 이라고 적어 두고 실제 값이 다르면,
     * 관리자는 없는 시간대를 있다고 믿는다. 그 사실은 되돌려야 하는 날에야 드러난다.</p>
     */
    @Test
    void theShippedTiersAreTheOnesDocumented() throws IOException {
        List<RetentionTiers.Tier> tiers = settings().tiers();

        assertEquals(5, tiers.size(), "5단계");
        assertEquals(27, tiers.stream().mapToInt(RetentionTiers.Tier::keep).sum(), "총 27개");
    }

    /** 배포본은 스스로 경고를 내지 않아야 한다. 첫 부팅부터 콘솔에 경고가 찍히면 안 된다. */
    @Test
    void theShippedConfigProducesNoWarnings() throws IOException {
        assertEquals(List.of(), settings().tierWarnings());
    }

    /**
     * 코드가 읽는 키와 배포본에 적힌 키가 <b>정확히</b> 같아야 한다.
     *
     * <p>한쪽에만 있으면 둘 중 하나다 - 관리자가 적어도 아무 일도 일어나지 않는 <b>죽은 설정</b>
     * 이거나, 문서에 없는 채로 동작을 바꾸는 <b>숨은 설정</b>이다. 둘 다 조용해서 눈에 띄지 않는다.</p>
     */
    @Test
    void everyKeyTheCodeReadsIsWrittenInTheShippedConfig() throws IOException {
        Set<String> declared = new TreeSet<>();
        YamlConfiguration cfg = load();
        for (String key : cfg.getKeys(true)) {
            if (cfg.isConfigurationSection(key)) continue; // backup, targets 같은 묶음
            declared.add(key);
        }

        Set<String> read = new TreeSet<>();
        Matcher matcher = Pattern.compile("cfg[.]get[A-Za-z]+[(]" + Q + "([^" + Q + "]+)" + Q)
                .matcher(Files.readString(SETTINGS_SOURCE, StandardCharsets.UTF_8));
        while (matcher.find()) {
            read.add(matcher.group(1));
        }

        assertFalse(read.isEmpty(), "소스에서 키를 하나도 못 찾았다면 이 검사가 무력하다");
        assertEquals(read, declared,
                "코드가 읽는 키와 배포본의 키가 어긋난다. 왼쪽이 코드, 오른쪽이 config.yml");
    }

    // ------------------------------------------------------------------

    private static YamlConfiguration load() throws IOException {
        assertTrue(Files.isRegularFile(CONFIG), "배포본을 찾지 못했습니다: " + CONFIG.toAbsolutePath());
        return YamlConfiguration.loadConfiguration(CONFIG.toFile());
    }

    private static BackupSettings settings() throws IOException {
        return BackupSettings.load(load(), Path.of("server/plugins/WorldBackUp"), Path.of("server"));
    }
}
