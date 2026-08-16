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

    /** config.yml 오타 하나로 설정 로딩 전체가 예외로 죽으면 안 된다. */
    @Test
    void unclosedBraceDoesNotBreakTheWholeMatcher() {
        GlobMatcher matcher = new GlobMatcher(List.of("**/*.{log,tmp", "**/session.lock"));

        assertTrue(matcher.matchesFile("world/a.log"));
        assertTrue(matcher.matchesFile("world/session.lock"), "뒤따르는 정상 패턴도 살아 있어야 한다");
        assertFalse(matcher.matchesFile("world/a.dat"));
    }
}
