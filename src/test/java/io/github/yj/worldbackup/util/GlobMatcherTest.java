package io.github.yj.worldbackup.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobMatcherTest {

    @Test
    void doubleStarMatchesAnyDepth() {
        GlobMatcher matcher = new GlobMatcher(List.of("**/session.lock"));
        assertTrue(matcher.matchesFile("session.lock"));
        assertTrue(matcher.matchesFile("world/session.lock"));
        assertTrue(matcher.matchesFile("world/nether/session.lock"));
        assertFalse(matcher.matchesFile("world/session.lock.bak"));
        assertFalse(matcher.matchesFile("world/region/r.0.0.mca"));
    }

    @Test
    void windowsSeparatorsAreNormalized() {
        GlobMatcher matcher = new GlobMatcher(List.of("**/logs/**"));
        assertTrue(matcher.matchesFile("world\\logs\\latest.log"));
        assertTrue(matcher.matchesDirectory("world/logs"));
        assertFalse(matcher.matchesDirectory("world/region"));
    }

    /**
     * <b>패턴</b>에 적힌 윈도우 구분자도 정규화된다.
     *
     * <p>검사할 경로만 정규화하고 패턴은 그대로 두면, 윈도우 관리자가 자연스럽게 적은
     * {@code world\region\**} 이 아무것도 걸러 내지 못한다. 그런데 조용히 통과한다 -
     * 50GB 폴더를 백업에서 뺐다고 믿는 쪽이 실제로는 그대로 담고 있는 것이다.</p>
     */
    @Test
    void patternsWrittenWithWindowsSeparatorsAlsoWork() {
        GlobMatcher backslash = new GlobMatcher(List.of("world\\region\\**"));
        assertTrue(backslash.matchesFile("world/region/r.0.0.mca"));
        assertTrue(backslash.matchesFile("world\\region\\r.0.0.mca"));
        assertTrue(backslash.matchesDirectory("world/region"));
        assertFalse(backslash.matchesFile("world/entities/r.0.0.mca"));

        // 지름길(**/D/**)을 타는 꼴도 같아야 한다
        GlobMatcher enclosing = new GlobMatcher(List.of("**\\logs\\**"));
        assertTrue(enclosing.matchesFile("world/logs/latest.log"));
        assertTrue(enclosing.matchesDirectory("world/logs"));

        // 마지막 조각만 보는 지름길도
        GlobMatcher tail = new GlobMatcher(List.of("**\\session.lock"));
        assertTrue(tail.matchesFile("world/session.lock"));
        assertFalse(tail.matchesFile("world/session.lock.bak"));
    }

    @Test
    void singleStarDoesNotCrossDirectories() {
        GlobMatcher matcher = new GlobMatcher(List.of("plugins/*.jar"));
        assertTrue(matcher.matchesFile("plugins/WorldBackUp.jar"));
        assertFalse(matcher.matchesFile("plugins/nested/WorldBackUp.jar"));
    }

    @Test
    void bracesActAsAlternation() {
        GlobMatcher matcher = new GlobMatcher(List.of("**/*.{log,tmp}"));
        assertTrue(matcher.matchesFile("world/a.log"));
        assertTrue(matcher.matchesFile("b.tmp"));
        assertFalse(matcher.matchesFile("b.dat"));
    }

    @Test
    void literalCommaOutsideBracesIsNotAlternation() {
        GlobMatcher matcher = new GlobMatcher(List.of("data/a,b.txt"));
        assertTrue(matcher.matchesFile("data/a,b.txt"));
        assertFalse(matcher.matchesFile("data/a.txt"));
    }

    @Test
    void backupDirectoryIsExcludedFromItsOwnBackup() {
        GlobMatcher matcher = new GlobMatcher(List.of("plugins/WorldBackUp/backups", "plugins/WorldBackUp/backups/**"));
        assertTrue(matcher.matchesDirectory("plugins/WorldBackUp/backups"));
        assertTrue(matcher.matchesFile("plugins/WorldBackUp/backups/wb-20260816-120000.zip"));
        assertFalse(matcher.matchesFile("plugins/WorldBackUp/config.yml"));
    }

    @Test
    void emptyMatcherMatchesNothing() {
        GlobMatcher matcher = GlobMatcher.empty();
        assertTrue(matcher.isEmpty());
        assertFalse(matcher.matchesFile("world/level.dat"));
    }

    /** 여러 패턴을 정규식 하나로 합치므로, 각 패턴이 서로를 침범하지 않는지 확인한다. */
    @Test
    void everyPatternStaysIndependentWhenCombined() {
        GlobMatcher matcher = new GlobMatcher(List.of(
                "**/session.lock",
                "plugins/*.jar",
                "**/*.{log,tmp}",
                "data/a,b.txt"));

        assertTrue(matcher.matchesFile("world/nether/session.lock"));
        assertTrue(matcher.matchesFile("plugins/Essentials.jar"));
        assertTrue(matcher.matchesFile("world/logs/latest.log"));
        assertTrue(matcher.matchesFile("data/a,b.txt"));

        // 한 패턴의 조각이 다른 패턴과 뒤섞여 넓어지면 안 된다.
        assertFalse(matcher.matchesFile("plugins/nested/Essentials.jar"));
        assertFalse(matcher.matchesFile("world/level.dat"));
        assertFalse(matcher.matchesFile("data/a.txt"));
        assertFalse(matcher.matchesFile("session.lock.bak"));
    }

    /**
     * {@code **}{@code /X} 는 마지막 조각만 검사하도록 최적화된다. 그 지름길이 답을 바꾸면 안 된다.
     */
    @Test
    void lastSegmentShortcutKeepsTheSameAnswers() {
        GlobMatcher matcher = new GlobMatcher(List.of("**/*.log"));

        assertTrue(matcher.matchesFile("latest.log"), "최상위");
        assertTrue(matcher.matchesFile("logs/latest.log"));
        assertTrue(matcher.matchesFile("world/a/b/c/latest.log"), "아무 깊이");
        assertFalse(matcher.matchesFile("latest.log/inner.dat"), "폴더 이름이 맞은 것은 파일이 아니다");
        assertFalse(matcher.matchesFile("world/latest.logx"));
        assertFalse(matcher.matchesFile("world/log"));
    }

    /**
     * {@code **} 가 꼬리에 들어 있으면 마지막 조각 지름길을 쓸 수 없다.
     *
     * <p>{@code **} 는 {@code /} 를 넘어가므로 {@code a**b} 는 {@code adir/subb} 에도 맞는다.
     * 지름길로 처리하면 그 경로를 놓친다.</p>
     */
    @Test
    void doubleStarInsideTheTailStillCrossesDirectories() {
        GlobMatcher matcher = new GlobMatcher(List.of("**/a**b"));

        assertTrue(matcher.matchesFile("ab"));
        assertTrue(matcher.matchesFile("world/axxb"));
        assertTrue(matcher.matchesFile("world/adir/subb"), "** 는 경로 구분자를 넘는다");
        assertFalse(matcher.matchesFile("world/xb"));
    }

    /** 마지막 조각 패턴과 경로 전체 패턴을 섞어도 서로 침범하지 않아야 한다. */
    @Test
    void lastSegmentAndFullPathPatternsCoexist() {
        GlobMatcher matcher = new GlobMatcher(List.of(
                "**/*.lock",              // 마지막 조각만
                "**/logs/**",             // 경로 전체
                "plugins/WorldBackUp",    // 경로 전체 (위치 고정)
                "plugins/WorldBackUp/**"));

        assertTrue(matcher.matchesFile("world/session.lock"));
        assertTrue(matcher.matchesFile("world/logs/latest.txt"));
        assertTrue(matcher.matchesDirectory("world/logs"));
        assertTrue(matcher.matchesDirectory("plugins/WorldBackUp"));
        assertTrue(matcher.matchesFile("plugins/WorldBackUp/backups/wb-1.zip"));

        assertFalse(matcher.matchesFile("world/region/r.0.0.mca"));
        assertFalse(matcher.matchesFile("plugins/OtherPlugin/config.yml"));
        assertFalse(matcher.matchesDirectory("world/region"));
        assertFalse(matcher.matchesFile("logsfile.txt"), "폴더 이름 일부가 맞은 것은 아니다");
    }

    /** 폴더 자체를 가리키는 {@code **}{@code /이름} 패턴도 지름길에서 살아야 한다. */
    @Test
    void lastSegmentPatternAlsoMatchesDirectories() {
        GlobMatcher matcher = new GlobMatcher(List.of("**/cache"));

        assertTrue(matcher.matchesDirectory("world/cache"));
        assertTrue(matcher.matchesDirectory("cache"));
        assertFalse(matcher.matchesDirectory("world/cached"));
    }

    /**
     * {@code **}{@code /D/**} 는 조각 훑기로 처리된다. 정규식과 같은 답을 내야 한다.
     */
    @Test
    void enclosingDirectoryShortcutKeepsTheSameAnswers() {
        GlobMatcher matcher = new GlobMatcher(List.of("**/logs/**"));

        assertTrue(matcher.matchesFile("logs/latest.log"), "최상위");
        assertTrue(matcher.matchesFile("world/logs/latest.log"));
        assertTrue(matcher.matchesFile("a/b/logs/c/d/e.txt"), "아무 깊이, 아래로도 아무 깊이");
        assertTrue(matcher.matchesDirectory("world/logs"), "폴더 자체");
        assertTrue(matcher.matchesDirectory("logs"));

        assertFalse(matcher.matchesFile("logs"), "폴더 이름만으로는 파일이 아니다");
        assertFalse(matcher.matchesFile("world/logs.txt"));
        assertFalse(matcher.matchesFile("world/mylogs/a.txt"), "조각이 정확히 같아야 한다");
        assertFalse(matcher.matchesFile("world/logsx/a.txt"));
        assertFalse(matcher.matchesDirectory("world/region"));
    }

    /** 대소문자를 가리지 않는 규칙이 조각 훑기에서도 같아야 한다. */
    @Test
    void enclosingDirectoryShortcutIsCaseInsensitive() {
        GlobMatcher matcher = new GlobMatcher(List.of("**/Cache/**"));

        assertTrue(matcher.matchesFile("world/cache/a.bin"));
        assertTrue(matcher.matchesFile("world/CACHE/a.bin"));
        assertFalse(matcher.matchesFile("world/cachex/a.bin"));
    }

    /** 폴더 이름에 와일드카드가 있으면 지름길을 쓸 수 없다. 그래도 답은 같아야 한다. */
    @Test
    void wildcardInTheDirectoryNameStillWorks() {
        GlobMatcher matcher = new GlobMatcher(List.of("**/log*/**"));

        assertTrue(matcher.matchesFile("world/logs/a.txt"));
        assertTrue(matcher.matchesFile("world/logging/a.txt"));
        assertFalse(matcher.matchesFile("world/blog/a.txt"));
    }

    /** config.yml 오타 하나로 설정 로딩 전체가 예외로 죽으면 안 된다. */
    @Test
    void unclosedBraceDoesNotBreakTheWholeMatcher() {
        GlobMatcher matcher = new GlobMatcher(List.of("**/*.{log,tmp", "**/session.lock"));

        assertTrue(matcher.matchesFile("world/a.log"));
        assertTrue(matcher.matchesFile("world/session.lock"), "뒤따르는 정상 패턴도 살아 있어야 한다");
        assertFalse(matcher.matchesFile("world/a.dat"));
    }
}
