package io.github.yj.worldbackup.backup;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 계단식 보관.
 *
 * <p>이 로직이 틀리면 조용히 백업이 사라지고, 정작 사고가 났을 때 되돌릴 시점이 없다는 걸
 * 알게 된다. 특히 "자는 동안 당하고 아침에 발견" 을 견디려면 <b>지난 하루의 해상도</b>가
 * 살아 있어야 하므로 그 구간을 집중적으로 본다.</p>
 */
class RetentionTiersTest {

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    /** 실제로 쓰라고 권한 배치. 최근 6시간은 전부, 지난 하루는 1시간 단위. */
    private static final List<RetentionTiers.Tier> TIERS = List.of(
            new RetentionTiers.Tier(Duration.ZERO, 24),
            new RetentionTiers.Tier(Duration.ofHours(1), 18),
            new RetentionTiers.Tier(Duration.ofHours(6), 12),
            new RetentionTiers.Tier(Duration.ofHours(24), 14));

    @Test
    void recentBackupsAreAllKept() {
        List<BackupEntry> all = every15Minutes(24 * 4); // 24시간치

        Set<String> keep = RetentionTiers.select(all, TIERS, NOW);

        // 첫 계단이 간격 0 / 24개이므로 가장 최근 24개(= 6시간)는 무조건 남는다.
        for (int i = 0; i < 24; i++) {
            assertTrue(keep.contains(all.get(i).id()), "최근 " + i + "번째는 남아야 한다");
        }
    }

    /**
     * 핵심 계약. 새벽에 사고가 나고 정오에 발견해도, 사고 직전으로 돌아갈 백업이 있어야 한다.
     */
    @Test
    void lastDayKeepsHourlyResolution() {
        List<BackupEntry> all = every15Minutes(24 * 4);

        Set<String> keep = RetentionTiers.select(all, TIERS, NOW);

        // 9시간 전(=새벽 3시) 앞뒤 한 시간 안에 살아남은 백업이 있어야 한다.
        Instant incident = NOW.minus(Duration.ofHours(9));
        boolean covered = all.stream()
                .filter(entry -> keep.contains(entry.id()))
                .anyMatch(entry -> {
                    Duration gap = Duration.between(entry.createdAt(), incident);
                    return !gap.isNegative() && gap.compareTo(Duration.ofHours(1)) <= 0;
                });

        assertTrue(covered, "사고 직전 1시간 이내로 되돌아갈 수 있어야 한다");
    }

    @Test
    void oldBackupsAreThinnedOut() {
        List<BackupEntry> all = every15Minutes(24 * 4);

        Set<String> keep = RetentionTiers.select(all, TIERS, NOW);

        assertTrue(keep.size() < all.size(), "오래된 것은 솎아내야 한다");
        // 24시간치 96개가 24(6시간) + 18(1시간 단위) 언저리로 줄어든다.
        assertTrue(keep.size() <= 45, "예상보다 너무 많이 남았다: " + keep.size());
    }

    /** 계단 밖으로 밀려난 백업은 남기지 않는다. 그게 계단식의 목적이다. */
    @Test
    void backupsBeyondTheLastTierAreDropped() {
        List<BackupEntry> all = new ArrayList<>(every15Minutes(24 * 4)); // 첫 계단을 채울 만큼
        all.add(entry("ancient", NOW.minus(Duration.ofDays(400)), false, true));

        Set<String> keep = RetentionTiers.select(all, TIERS, NOW);

        assertFalse(keep.contains("ancient"), "마지막 계단(14일)보다 오래된 것은 대상이 아니다");
    }

    /**
     * 백업이 적으면 첫 계단이 다 채워지지 않아 전부 남는다.
     *
     * <p>"최근 N개는 무조건" 이라는 계단의 뜻이 그대로 적용된 결과다. 갓 설치한 서버나 오래
     * 놀던 서버에서 몇 개 없는 백업을 나이만 보고 지워 버리면 되돌릴 곳이 사라지므로,
     * 이쪽이 안전한 방향이다.</p>
     */
    @Test
    void sparseHistoryIsKeptWholeUntilTheFirstTierFillsUp() {
        List<BackupEntry> all = new ArrayList<>(every15Minutes(4));
        all.add(entry("ancient", NOW.minus(Duration.ofDays(400)), false, true));

        Set<String> keep = RetentionTiers.select(all, TIERS, NOW);

        assertEquals(5, keep.size(), "첫 계단(24개)이 안 찼으므로 전부 남는다");
        assertTrue(keep.contains("ancient"));
    }

    /**
     * 같은 구간에 전체 백업과 차등본이 함께 있으면 전체 백업을 남긴다.
     * 차등본을 대표로 남기면 그 기준 전체 백업까지 함께 붙잡아야 해서 디스크가 두 배로 든다.
     */
    @Test
    void fullBackupWinsOverDifferentialInTheSameBucket() {
        List<RetentionTiers.Tier> tiers = List.of(
                new RetentionTiers.Tier(Duration.ZERO, 0),
                new RetentionTiers.Tier(Duration.ofHours(6), 4));

        List<BackupEntry> all = List.of(
                entry("diff-newer", NOW.minus(Duration.ofHours(1)), true, true),
                entry("full-older", NOW.minus(Duration.ofHours(2)), false, true));

        Set<String> keep = RetentionTiers.select(all, tiers, NOW);

        assertEquals(Set.of("full-older"), keep, "같은 구간이면 전체 백업이 대표다");
    }

    /** 손상된 백업을 그 구간의 유일한 대표로 남기면 그 시간대가 통째로 비는 것과 같다. */
    @Test
    void brokenBackupsAreNeverChosenAsRepresentative() {
        List<RetentionTiers.Tier> tiers = List.of(
                new RetentionTiers.Tier(Duration.ZERO, 0),
                new RetentionTiers.Tier(Duration.ofHours(6), 4));

        List<BackupEntry> all = List.of(
                entry("broken", NOW.minus(Duration.ofHours(1)), false, false),
                entry("healthy", NOW.minus(Duration.ofHours(2)), false, true));

        Set<String> keep = RetentionTiers.select(all, tiers, NOW);

        assertEquals(Set.of("healthy"), keep);
    }

    @Test
    void emptyTiersKeepNothing() {
        assertTrue(RetentionTiers.select(every15Minutes(10), List.of(), NOW).isEmpty(),
                "계단이 없으면 이 로직은 아무 판단도 하지 않는다 (예전 정책이 담당)");
    }

    /** 서버가 꺼져 있어 구간이 비어도 넘어가야 한다. 빈 구간은 그냥 대표가 없는 것이다. */
    @Test
    void gapsInHistoryDoNotBreakTheWalk() {
        List<BackupEntry> all = List.of(
                entry("recent", NOW.minus(Duration.ofMinutes(10)), false, true),
                entry("after-long-gap", NOW.minus(Duration.ofDays(9)), false, true));

        Set<String> keep = RetentionTiers.select(all, TIERS, NOW);

        assertTrue(keep.contains("recent"));
        assertTrue(keep.contains("after-long-gap"), "공백 건너편의 백업도 계단 안이면 남아야 한다");
    }

    // ------------------------------------------------------------------

    private static List<BackupEntry> every15Minutes(int count) {
        List<BackupEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("b" + i, NOW.minus(Duration.ofMinutes(15L * i)), false, true));
        }
        return entries; // 최신순
    }

    private static BackupEntry entry(String id, Instant createdAt, boolean differential, boolean complete) {
        return new BackupEntry(id, Path.of("backups", id + ".zip"), createdAt, BackupType.SCHEDULED, null,
                1L, 1L, 1, List.of("world"), List.of("world"), List.of(), "test",
                false, complete, differential ? "some-base" : null, true);
    }
}
