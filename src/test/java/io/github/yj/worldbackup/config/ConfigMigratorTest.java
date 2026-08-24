package io.github.yj.worldbackup.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 설정 이행은 <b>사용자가 쓴 줄을 한 줄도 잃지 않아야</b> 한다.
 *
 * <p>이 코드는 관리자의 파일을 다시 쓴다. 값 하나를 잃으면 보관 정책이나 백업 위치가 조용히
 * 기본값으로 돌아가고, 그 사실은 정작 되돌려야 하는 날에야 드러난다. 그래서 "새 키가 들어왔나"
 * 보다 "옛 줄이 그대로인가" 를 먼저 못 박는다.</p>
 */
class ConfigMigratorTest {

    private static final Path SHIPPED = Path.of("src/main/resources/config.yml");

    /** 없는 설정이 <b>주석까지</b> 따라 들어온다. 이 플러그인에서는 주석이 곧 문서다. */
    @Test
    void aMissingKeyArrivesWithItsComments() {
        String shipped = lines(
                "backup:",
                "  # 첫 설정",
                "  enabled: true",
                "",
                "  # 새로 생긴 설정.",
                "  # 두 줄짜리 설명이다.",
                "  fresh: 3");
        String user = lines(
                "backup:",
                "  # 첫 설정",
                "  enabled: false");

        ConfigMigrator.Result result = ConfigMigrator.merge(shipped, user);

        assertEquals(List.of("backup.fresh"), result.added());
        assertTrue(result.text().contains("# 두 줄짜리 설명이다."), "설명이 함께 와야 한다");
        assertTrue(result.text().contains("  fresh: 3"));
        assertTrue(result.text().contains("  enabled: false"), "관리자가 바꾼 값은 그대로다");
    }

    /** 관리자가 쓴 줄은 <b>하나도 빠짐없이</b> 순서 그대로 남는다. */
    @Test
    void everyLineTheAdminWroteSurvivesInOrder() {
        String shipped = lines(
                "backup:",
                "  enabled: true",
                "  fresh: 3",
                "targets:",
                "  worlds: [all]",
                "  plugins: all");
        String user = lines(
                "backup:",
                "  # 내가 쓴 메모",
                "  enabled: false",
                "targets:",
                "  # 이 서버는 메인 월드만",
                "  worlds: [world]");

        String merged = ConfigMigrator.merge(shipped, user).text();

        int at = -1;
        for (String line : user.split("\n")) {
            int found = merged.indexOf(line, at + 1);
            assertTrue(found > at, "사라졌거나 순서가 바뀐 줄: " + line);
            at = found;
        }
    }

    /** 빠진 것이 없으면 파일을 다시 쓰지 않는다. */
    @Test
    void nothingIsRewrittenWhenNothingIsMissing() {
        String same = lines("backup:", "  enabled: true");

        ConfigMigrator.Result result = ConfigMigrator.merge(same, same);

        assertFalse(result.changed());
        assertEquals(same, result.text());
    }

    /** 묶음 자체가 없으면 묶음째 붙인다. */
    @Test
    void aWholeMissingSectionIsAppended() {
        String shipped = lines(
                "backup:",
                "  enabled: true",
                "",
                "# 되돌리기 설정",
                "restore:",
                "  # 카운트다운",
                "  countdown-seconds: 15");
        String user = lines("backup:", "  enabled: true");

        ConfigMigrator.Result result = ConfigMigrator.merge(shipped, user);

        assertEquals(List.of("restore.countdown-seconds"), result.added());
        assertTrue(result.text().contains("# 되돌리기 설정"));
        assertTrue(result.text().contains("  countdown-seconds: 15"), "묶음은 속까지 통째로 온다");
    }

    /** 새 설정은 그 묶음 안에 들어간다. 엉뚱한 묶음 밑에 붙으면 읽히지 않는다. */
    @Test
    void aNewKeyLandsInsideItsOwnSection() {
        String shipped = lines(
                "backup:",
                "  enabled: true",
                "  fresh: 3",
                "targets:",
                "  worlds: [all]");
        String user = lines(
                "backup:",
                "  enabled: true",
                "targets:",
                "  worlds: [all]");

        String merged = ConfigMigrator.merge(shipped, user).text();

        assertTrue(merged.indexOf("  fresh: 3") < merged.indexOf("targets:"),
                "targets 밑으로 들어가면 backup.fresh 로 읽히지 않는다");
    }

    /** 목록과 그 항목들이 통째로 따라온다. */
    @Test
    void aMissingListKeepsItsItems() {
        String shipped = lines(
                "retention:",
                "  # 계단식 보관",
                "  tiers:",
                "    - { every: 0,  keep: 8 }",
                "    - { every: 1h, keep: 10 }",
                "  max-backups: 48");
        String user = lines("retention:", "  max-backups: 48");

        String merged = ConfigMigrator.merge(shipped, user).text();

        assertTrue(merged.contains("- { every: 0,  keep: 8 }"));
        assertTrue(merged.contains("- { every: 1h, keep: 10 }"));
    }

    /**
     * <b>배포본 자체</b>로 왕복시킨다.
     *
     * <p>설정을 하나 지운 파일에 이행을 걸면 배포본과 <b>글자 하나까지</b> 같아져야 한다.
     * 이 검사가 통과하면 실제 업그레이드가 하는 일이 그대로 검증된 것이다.</p>
     */
    @Test
    void theShippedConfigSurvivesARoundTrip() throws IOException {
        String shipped = shippedText();

        for (String key : List.of("  flush-settle-seconds: 3", "  max-skipped-cycles: 48")) {
            String stripped = strip(shipped, key);
            assertFalse(stripped.equals(shipped), "지울 설정을 찾지 못했다: " + key);

            ConfigMigrator.Result result = ConfigMigrator.merge(shipped, stripped);

            assertTrue(result.changed(), key + " 를 다시 넣지 못했다");
            assertTrue(result.guarded().isEmpty(), key + " 는 동작을 바꾸지 않으므로 그대로 들어간다");
            assertSameLines(shipped, result.text(), key + " 왕복이 배포본과 달라졌다");
        }
    }

    /** 옛 서버에 새 설정 둘이 한꺼번에 들어오는, 실제 업그레이드 모양. */
    @Test
    void anOlderConfigGetsEveryNewKey() throws IOException {
        String shipped = shippedText();
        String older = strip(strip(shipped, "  max-skipped-cycles: 48"), "  flush-settle-seconds: 3");

        ConfigMigrator.Result result = ConfigMigrator.merge(shipped, older);

        assertEquals(List.of("backup.max-skipped-cycles", "backup.flush-settle-seconds"), result.added());
        assertSameLines(shipped, result.text(), "옛 설정 이행 결과가 배포본과 달라졌다");
    }

    /**
     * <b>막는 것은 "지우는" 변화뿐이다.</b>
     *
     * <p>{@code targets.plugins} 의 기본값 {@code all} 은 백업을 <b>키운다.</b> 그것까지
     * 막으면 업그레이드한 서버는 영영 플러그인 데이터 없이 백업하게 되는데, 두 실패의 대가가
     * 비대칭이다 - 백업이 커진 것은 나중에 줄일 수 있지만 <b>백업에 없는 경제 잔고는 되돌릴
     * 수 없다.</b> 그래서 이쪽은 배포 기본값 그대로 들어간다.</p>
     */
    @Test
    void aNewDefaultThatOnlyAddsDataArrivesTurnedOn() throws IOException {
        String shipped = shippedText();
        String older = strip(shipped, "  plugins: all");

        ConfigMigrator.Result result = ConfigMigrator.merge(shipped, older);

        assertEquals(List.of("targets.plugins"), result.added());
        assertTrue(result.guarded().isEmpty(), "지우는 변화가 아니므로 막지 않는다");
        assertSameLines(shipped, result.text(), "배포본 그대로 들어가야 한다");
    }

    /**
     * 보관 정책은 <b>백업을 지우는</b> 설정이다. 여기가 새면 업그레이드가 백업을 지운다.
     *
     * <p>{@code retention.tiers} 는 값이 차 있으면 예전 정책(max-backups/max-age-days/
     * keep-daily)을 <b>대신한다.</b> 배포 기본값 그대로 끼워 넣으면, 서버를 켜고 몇 초 뒤
     * 도는 첫 정리에서 계단에 들지 못한 백업이 사라진다.</p>
     */
    @Test
    void theRetentionPolicyIsNeverChangedByAnUpgrade() throws IOException {
        String shipped = shippedText();
        String older = strip(shipped, "  tiers:");

        ConfigMigrator.Result result = ConfigMigrator.merge(shipped, older);

        assertEquals(List.of("retention.tiers"), result.guarded());
        assertTrue(result.text().contains("  tiers: []"),
                "비어 있어야 예전 정책이 그대로 적용된다");
        assertTrue(result.text().contains("#        - { every: 0,"),
                "계단 예시는 주석으로 남아 있어야 한다");
    }

    /** 이미 값을 쓰고 있는 서버의 설정은 건드리지 않는다. 업그레이드가 관리자 의사를 덮으면 안 된다. */
    @Test
    void aSettingTheAdminAlreadyChoseIsNeverTouched() throws IOException {
        String shipped = shippedText();
        String mine = shipped.replace("  plugins: all", "  plugins: data");

        ConfigMigrator.Result result = ConfigMigrator.merge(shipped, mine);

        assertFalse(result.changed(), "이미 있는 설정은 손대지 않는다");
    }

    /** 배포본을 그대로 쓰는 서버는 업그레이드해도 파일이 그대로다. */
    @Test
    void anUntouchedShippedConfigIsLeftAlone() throws IOException {
        String shipped = shippedText();

        assertFalse(ConfigMigrator.merge(shipped, shipped).changed());
    }

    /**
     * 줄 단위로 견준다. 650줄짜리 파일을 통째로 견주면 실패 메시지가 파일 두 벌이라
     * 정작 <b>어디가</b> 어긋났는지 읽을 수 없다. 처음 어긋난 자리만 짚어 준다.
     */
    private static void assertSameLines(String expected, String actual, String message) {
        List<String> want = List.of(expected.split("\\n", -1));
        List<String> got = List.of(actual.split("\\n", -1));
        for (int i = 0; i < Math.max(want.size(), got.size()); i++) {
            String a = i < want.size() ? want.get(i) : "<파일 끝>";
            String b = i < got.size() ? got.get(i) : "<파일 끝>";
            if (a.equals(b)) continue;
            StringBuilder around = new StringBuilder();
            for (int j = Math.max(0, i - 3); j < Math.min(got.size(), i + 4); j++) {
                around.append(j == i ? "  >> " : "     ").append(got.get(j)).append('\n');
            }
            throw new AssertionError(message + System.lineSeparator()
                    + (i + 1) + "행이 어긋났다." + System.lineSeparator()
                    + "  기대: " + a + System.lineSeparator()
                    + "  실제: " + b + System.lineSeparator()
                    + "실제 파일 주변:" + System.lineSeparator() + around);
        }
    }

    private static String shippedText() throws IOException {
        return Files.readString(SHIPPED, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String lines(String... text) {
        return String.join("\n", text);
    }

    /** 설정 하나를 <b>앞의 주석까지</b> 지운다. 옛 버전의 파일을 흉내 내는 것이다. */
    private static String strip(String text, String keyLine) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        int at = lines.indexOf(keyLine);
        if (at < 0) return text;
        int start = at;
        while (start > 0) {
            String previous = lines.get(start - 1).trim();
            if (previous.isEmpty() || previous.startsWith("#")) start--;
            else break;
        }
        int end = at + 1;
        while (end < lines.size() && lines.get(end).startsWith("    ")) end++;
        lines.subList(start, end).clear();
        return String.join("\n", lines);
    }
}
