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
}
