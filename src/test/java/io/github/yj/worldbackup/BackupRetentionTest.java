package io.github.yj.worldbackup;

import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.BackupType;
import io.github.yj.worldbackup.config.BackupSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 보관 정책({@code retention.*}) 검증.
 *
 * <p>백업을 <b>지우는</b> 로직이라 잘못되면 조용히 데이터를 잃는다. 실제 압축은 필요 없고
 * "wb-&lt;id&gt;.zip + 사이드카 메타" 조합만 있으면 {@link BackupRepository} 가 읽어들이므로,
 * 시각을 원하는 대로 심어 둔 가짜 백업으로 정책만 떼어 내 검증한다.</p>
 */
class BackupRetentionTest {

    private static final Logger LOG = Logger.getLogger("WorldBackUpRetentionTest");

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------
    // 나이 / 일별 보존

    @Test
    void maxAgeDaysDeletesOnlyOldBackups() throws Exception {
        BackupRepository repo = repository();
        put(repo, "old", at(30, 12), BackupType.SCHEDULED, null);
        put(repo, "recent", at(1, 12), BackupType.SCHEDULED, null);

        BackupRepository.PruneResult result = repo.prune(settings(cfg -> cfg.set("retention.max-age-days", 14)));

        assertEquals(List.of("old"), result.ids(), "보관 기간이 지난 백업만 지워야 한다");
        assertEquals(List.of("recent"), ids(repo));
    }

    @Test
    void keepDailyPreservesTheNewestBackupOfEachDay() throws Exception {
        BackupRepository repo = repository();
        put(repo, "d3-morning", at(3, 6), BackupType.SCHEDULED, null);
        put(repo, "d3-noon", at(3, 12), BackupType.SCHEDULED, null);
        put(repo, "d3-evening", at(3, 18), BackupType.SCHEDULED, null);

        // 나이로만 보면 셋 다 삭제 대상이지만, keep-daily 가 그 날의 최신 하나를 붙잡는다.
        repo.prune(settings(cfg -> {
            cfg.set("retention.max-age-days", 1);
            cfg.set("retention.keep-daily", 7);
        }));

        assertEquals(List.of("d3-evening"), ids(repo), "그 날의 가장 최근 백업만 남아야 한다");
    }

    @Test
    void keepDailyDoesNotProtectDaysBeyondItsWindow() throws Exception {
        BackupRepository repo = repository();
        put(repo, "inside", at(2, 12), BackupType.SCHEDULED, null);
        put(repo, "outside", at(20, 12), BackupType.SCHEDULED, null);

        repo.prune(settings(cfg -> {
            cfg.set("retention.max-age-days", 1);
            cfg.set("retention.keep-daily", 3); // 최근 3일치만 보존
        }));

        assertEquals(List.of("inside"), ids(repo), "보존 창 밖의 날짜는 지켜 주지 않는다");
    }

    // ------------------------------------------------------------------
    // 보호 백업

    @Test
    void manualBackupsSurviveAgeButAreCappedByMaxProtected() throws Exception {
        BackupRepository repo = repository();
        put(repo, "m1-oldest", at(40, 9), BackupType.MANUAL, null);
        put(repo, "m2", at(30, 9), BackupType.PRE_RESTORE, null);
        put(repo, "m3", at(20, 9), BackupType.MANUAL, null);
        put(repo, "m4-newest", at(10, 9), BackupType.MANUAL, null);

        repo.prune(settings(cfg -> {
            cfg.set("retention.max-age-days", 1);   // 넷 다 나이로는 삭제 대상
            cfg.set("retention.protect-manual", true);
            cfg.set("retention.max-protected", 2);  // 하지만 2개까지만 보호한다
        }));

        assertEquals(List.of("m4-newest", "m3"), ids(repo), "보호 백업도 상한을 넘으면 오래된 것부터 지운다");
    }

    /**
     * 보호 백업 상한이 <b>계단이 남기기로 한 백업</b>까지 지우면 안 된다.
     *
     * <p>계단은 "이 시간대에는 되돌릴 지점이 하나 있다" 는 약속이다. 그 자리를 채우고 있는
     * 백업이 하필 수동 백업이면, {@code max-protected} 가 오래된 순으로 지우면서 그 시간대를
     * 통째로 비운다. 그런데 {@code /wb status} 는 계속 "계단식 N단계" 를 보여 주므로,
     * 비었다는 사실은 정작 되돌려야 하는 날에야 드러난다.</p>
     */
    @Test
    void maxProtectedDoesNotEvictABackupThatATierIsKeeping() throws Exception {
        BackupRepository repo = repository();
        // 계단 1(최신 1개)이 잡는 자리와, 계단 2(24시간 구간)가 잡는 자리.
        put(repo, "m-newest", Instant.now().minusSeconds(600), BackupType.MANUAL, null);
        put(repo, "m-yesterday", Instant.now().minusSeconds(30 * 3600L), BackupType.MANUAL, null);

        repo.prune(settings(cfg -> {
            cfg.set("retention.tiers", List.of(
                    Map.of("every", "0", "keep", 1),
                    Map.of("every", "24h", "keep", 3)));
            cfg.set("retention.protect-manual", true);
            cfg.set("retention.max-protected", 1); // 상한은 1이지만 계단이 둘 다 필요하다
        }));

        assertEquals(List.of("m-newest", "m-yesterday"), ids(repo),
                "계단이 붙잡은 자리는 보호 상한보다 우선한다");
    }

    /** 계단 밖의 보호 백업은 그대로 상한에 걸린다. 위 예외가 상한을 무력화하지는 않는다. */
    @Test
    void maxProtectedStillEvictsProtectedBackupsOutsideEveryTier() throws Exception {
        BackupRepository repo = repository();
        put(repo, "m-newest", Instant.now().minusSeconds(600), BackupType.MANUAL, null);
        put(repo, "m-ancient", at(300, 12), BackupType.MANUAL, null); // 어떤 계단도 닿지 않는다

        repo.prune(settings(cfg -> {
            cfg.set("retention.tiers", List.of(Map.of("every", "0", "keep", 1)));
            cfg.set("retention.protect-manual", true);
            cfg.set("retention.max-protected", 1);
        }));

        assertEquals(List.of("m-newest"), ids(repo), "계단 밖이면 상한이 그대로 동작한다");
    }

    @Test
    void explicitlyLockedBackupSurvivesEveryPolicy() throws Exception {
        BackupRepository repo = repository();
        BackupEntry locked = put(repo, "locked", at(90, 9), BackupType.SCHEDULED, null);
        assertTrue(repo.setLocked(locked, true));
        put(repo, "filler-1", at(5, 9), BackupType.SCHEDULED, null);
        put(repo, "filler-2", at(4, 9), BackupType.SCHEDULED, null);

        repo.prune(settings(cfg -> {
            cfg.set("retention.max-age-days", 1);
            cfg.set("retention.max-backups", 1);
            cfg.set("retention.max-protected", 1);
        }));

        assertTrue(ids(repo).contains("locked"), "/wb lock 으로 잠근 백업은 어떤 정책으로도 지워지지 않는다");
    }

    // ------------------------------------------------------------------
    // 차등 백업의 기준 보호

    @Test
    void baseBackupIsNeverDeletedWhileADependentSurvives() throws Exception {
        BackupRepository repo = repository();
        BackupEntry base = put(repo, "base", at(30, 9), BackupType.SCHEDULED, null);
        put(repo, "diff", at(2, 9), BackupType.SCHEDULED, base.id());

        // 나이로 보면 base 는 삭제 대상이지만, diff 가 살아 있으므로 남아야 한다.
        repo.prune(settings(cfg -> cfg.set("retention.max-age-days", 14)));

        assertEquals(List.of("diff", "base"), ids(repo), "기준을 잃으면 차등본이 통째로 복원 불가가 된다");
        assertTrue(repo.list().stream().allMatch(BackupEntry::complete));
    }

    /**
     * 예전에는 개수 상한을 먼저 계산하고 나중에 기준 백업을 목록에서 빼는 바람에,
     * 실제 삭제 수가 계산보다 적어 {@code max-backups} 를 넘긴 채로 끝났다.
     */
    @Test
    void maxBackupsIsReachedEvenWhenABaseMustBeKept() throws Exception {
        BackupRepository repo = repository();
        BackupEntry base = put(repo, "f-base", at(10, 9), BackupType.SCHEDULED, null);
        put(repo, "d-1", at(9, 9), BackupType.SCHEDULED, base.id());
        put(repo, "d-2", at(8, 9), BackupType.SCHEDULED, base.id());
        put(repo, "f-a", at(7, 9), BackupType.SCHEDULED, null);
        put(repo, "f-b", at(6, 9), BackupType.SCHEDULED, null);

        BackupRepository.PruneResult result = repo.prune(settings(cfg -> cfg.set("retention.max-backups", 3)));

        // 가장 오래된 f-base 는 기준이라 못 지운다 -> 대신 딸린 차등본을 지워 상한을 맞춘다.
        assertEquals(2, result.deleted());
        assertTrue(result.ids().containsAll(List.of("d-1", "d-2")));
        assertEquals(3, repo.list().size(), "max-backups 를 넘긴 채로 끝나면 안 된다");
        assertFalse(ids(repo).contains("d-1"));
        assertTrue(ids(repo).contains("f-base"), "차등본이 사라진 뒤에도 이번 회차에서는 기준을 남긴다");
    }

    /**
     * 진행 중인 차등 백업의 기준은 어떤 경로로도 사라지면 안 된다.
     *
     * <p>압축이 끝나기 전에는 딸린 차등본이 아직 디스크에 없어 {@code dependents} 가 비어 보인다.
     * 그 틈에 기준이 지워지면 방금 만든 차등본은 태어나자마자 복원 불가가 된다.</p>
     */
    @Test
    void pinnedBaseSurvivesEveryDeletionPath() throws Exception {
        BackupRepository repo = repository();
        BackupEntry base = put(repo, "base", at(30, 9), BackupType.SCHEDULED, null);
        put(repo, "filler", at(29, 9), BackupType.SCHEDULED, null);

        repo.pin(base.id()); // 지금 이 기준으로 차등 백업을 압축하는 중이라고 가정

        // 나이로도, 개수로도, 공간 확보로도 지워지면 안 된다.
        repo.prune(settings(cfg -> {
            cfg.set("retention.max-age-days", 1);
            cfg.set("retention.max-backups", 1);
        }));
        repo.freeUpSpace(settings(cfg -> {
        }), Long.MAX_VALUE);

        assertTrue(ids(repo).contains("base"), "사용 중인 기준 백업은 정책이 지우면 안 된다");
        assertFalse(repo.delete(base), "직접 삭제도 거부해야 한다");
        assertTrue(Files.exists(base.archive()));

        // 백업이 끝나면 평범한 백업으로 돌아간다.
        repo.unpin();
        assertTrue(repo.delete(base));
        assertFalse(ids(repo).contains("base"));
    }

    // ------------------------------------------------------------------
    // 최소 보관 개수 (무인 운영 안전망)

    /**
     * 무인 서버가 오래 놀면 나이 정책 하나로 백업이 <b>전멸</b>할 수 있다.
     * 접속자가 없으면 백업은 건너뛰지만 보관 정리는 계속 돌고, keep-daily 는 "최근 N일 안에
     * 만들어진 백업"만 지키므로 그 기간에 백업이 없으면 아무도 지키지 못한다.
     */
    @Test
    void ageAloneCanNeverWipeOutEveryBackup() throws Exception {
        BackupRepository repo = repository();
        put(repo, "d40", at(40, 9), BackupType.SCHEDULED, null);
        put(repo, "d39", at(39, 9), BackupType.SCHEDULED, null);
        put(repo, "d38", at(38, 9), BackupType.SCHEDULED, null);
        put(repo, "d37", at(37, 9), BackupType.SCHEDULED, null);

        // 넷 다 나이로는 삭제 대상이고 keep-daily 창(최근 7일) 밖이다.
        repo.prune(settings(cfg -> {
            cfg.set("retention.max-age-days", 14);
            cfg.set("retention.keep-daily", 7);
            cfg.set("retention.min-backups", 2);
        }));

        assertEquals(List.of("d37", "d38"), ids(repo), "최신 것부터 하한선만큼 남아야 한다");
    }

    @Test
    void minBackupsIsIgnoredWhenSetToZero() throws Exception {
        BackupRepository repo = repository();
        put(repo, "old", at(40, 9), BackupType.SCHEDULED, null);

        repo.prune(settings(cfg -> {
            cfg.set("retention.max-age-days", 14);
            cfg.set("retention.min-backups", 0);
        }));

        assertTrue(ids(repo).isEmpty(), "하한선을 끄면 예전처럼 전부 지운다");
    }

    /** 차등본만 되살리면 기준을 잃어 복원 불가가 된다. 기준도 함께 되살려야 한다. */
    @Test
    void rescuingADifferentialAlsoRescuesItsBase() throws Exception {
        BackupRepository repo = repository();
        BackupEntry base = put(repo, "base", at(40, 9), BackupType.SCHEDULED, null);
        put(repo, "diff", at(39, 9), BackupType.SCHEDULED, base.id());

        repo.prune(settings(cfg -> {
            cfg.set("retention.max-age-days", 14);
            cfg.set("retention.min-backups", 1);
        }));

        assertEquals(List.of("diff", "base"), ids(repo), "기준이 함께 남아야 한다");
        assertTrue(repo.list().stream().allMatch(BackupEntry::complete), "되살린 차등본은 복원 가능해야 한다");
    }

    /** 손상된 백업은 되살려도 복원에 못 쓰므로 하한선에 세지 않는다. */
    @Test
    void brokenBackupsDoNotCountTowardTheMinimum() throws Exception {
        BackupRepository repo = repository();
        put(repo, "healthy", at(40, 9), BackupType.SCHEDULED, null);
        put(repo, "orphan", at(39, 9), BackupType.SCHEDULED, "사라진-기준"); // 기준이 없는 차등본

        repo.prune(settings(cfg -> {
            cfg.set("retention.max-age-days", 14);
            cfg.set("retention.min-backups", 1);
        }));

        assertEquals(List.of("healthy"), ids(repo), "멀쩡한 백업이 하한선을 채워야 한다");
    }

    // ------------------------------------------------------------------
    // 계단식 보관

    /** 계단을 켜면 max-backups·max-age-days·keep-daily 대신 계단이 판단한다. */
    @Test
    void tiersReplaceTheOlderRetentionKnobs() throws Exception {
        BackupRepository repo = repository();
        for (int hour = 0; hour < 12; hour++) {
            put(repo, "h" + hour, Instant.now().minusSeconds(hour * 3600L), BackupType.SCHEDULED, null);
        }

        repo.prune(settings(cfg -> {
            // 최근 2개는 무조건, 그다음 6시간 단위로 2구간
            cfg.set("retention.tiers", List.of(
                    Map.of("every", "0", "keep", 2),
                    Map.of("every", "6h", "keep", 2)));
            // 계단을 켜면 아래 값들은 무시되어야 한다.
            cfg.set("retention.max-backups", 100);
            cfg.set("retention.max-age-days", 3650);
            cfg.set("retention.keep-daily", 30);
        }));

        List<String> left = ids(repo);
        assertTrue(left.contains("h0"), "최근 것은 남는다");
        assertTrue(left.contains("h1"));
        assertTrue(left.size() < 12, "계단이 오래된 것을 솎아내야 한다: " + left);
        assertFalse(left.contains("h11"), "마지막 계단 밖은 지워진다");
    }

    /** 형식이 깨진 계단은 무시하고, 하나도 못 읽으면 예전 정책으로 돌아간다. */
    @Test
    void brokenTierEntriesFallBackToTheOlderPolicy() throws Exception {
        BackupRepository repo = repository();
        put(repo, "old", at(30, 12), BackupType.SCHEDULED, null);
        put(repo, "recent", at(1, 12), BackupType.SCHEDULED, null);

        repo.prune(settings(cfg -> {
            cfg.set("retention.tiers", List.of(
                    Map.of("every", "이상한값", "keep", 5),
                    Map.of("every", "6h", "keep", 0)));
            cfg.set("retention.max-age-days", 14);
        }));

        assertEquals(List.of("recent"), ids(repo), "계단을 못 읽으면 max-age-days 가 그대로 동작한다");
    }

    /** 깨진 계단을 조용히 버리면 관리자가 기대한 시간대가 소리 없이 빈다. 경고가 남아야 한다. */
    @Test
    void malformedTiersProduceWarnings() {
        BackupSettings settings = settings(cfg -> cfg.set("retention.tiers", List.of(
                Map.of("every", "이상한값", "keep", 5),
                Map.of("every", "6h", "keep", 0),
                Map.of("every", "1h", "keep", 3))));

        assertEquals(1, settings.tiers().size(), "멀쩡한 계단 하나만 살아남는다");
        assertEquals(2, settings.tierWarnings().size(), "버린 계단마다 경고가 있어야 한다");
        assertTrue(settings.tierWarnings().get(0).contains("every"));
        assertTrue(settings.tierWarnings().get(1).contains("keep"));
    }

    /**
     * {@code mode: full} 과 깊은 계단이 만나면 알린다.
     *
     * <p>이 조합은 조용히 실패한다. 디스크가 먼저 차서 공간 확보 로직이 매 주기 오래된 백업을
     * 지우고 다시 채우는데, {@code /wb status} 는 그동안 계속 "계단식 N단계" 를 보여 준다.
     * 관리자는 자는 동안의 해상도가 지켜지고 있다고 믿지만 실제로는 최근 몇 개만 남아 있고,
     * 그 사실은 정작 되돌려야 하는 날에야 드러난다.</p>
     */
    @Test
    void fullModeWithDeepTiersIsCalledOut() {
        BackupSettings settings = settings(cfg -> {
            cfg.set("backup.mode", "full");
            cfg.set("retention.tiers", List.of(
                    Map.of("every", "0", "keep", 8),
                    Map.of("every", "1h", "keep", 10)));
        });

        assertEquals(2, settings.tiers().size(), "계단 자체는 멀쩡히 읽힌다");
        assertTrue(settings.tierWarnings().stream().anyMatch(w -> w.contains("18")),
                "몇 벌을 쓰게 되는지 숫자로 말해야 한다: " + settings.tierWarnings());
        assertTrue(settings.tierWarnings().stream().anyMatch(w -> w.contains("differential")),
                "무엇을 바꾸면 되는지도 알려야 한다: " + settings.tierWarnings());
    }

    /** 같은 계단이라도 차등 모드면 문제가 없다. 위 경고가 계단 자체를 탓하는 것이 아님을 못 박는다. */
    @Test
    void theSameTiersAreFineInDifferentialMode() {
        BackupSettings settings = settings(cfg -> {
            cfg.set("backup.mode", "differential");
            cfg.set("retention.tiers", List.of(
                    Map.of("every", "0", "keep", 8),
                    Map.of("every", "1h", "keep", 10)));
        });

        assertTrue(settings.differential());
        assertTrue(settings.tierWarnings().isEmpty(), "경고할 것이 없다: " + settings.tierWarnings());
    }

    /** 계단이 얕으면 full 이어도 그냥 둔다. 경고가 잔소리가 되면 아무도 읽지 않는다. */
    @Test
    void shallowTiersDoNotTriggerTheFullModeWarning() {
        BackupSettings settings = settings(cfg -> {
            cfg.set("backup.mode", "full");
            cfg.set("retention.tiers", List.of(Map.of("every", "0", "keep", 3)));
        });

        assertFalse(settings.differential());
        assertTrue(settings.tierWarnings().isEmpty(), "경고할 것이 없다: " + settings.tierWarnings());
    }

    @Test
    void allTiersBrokenIsCalledOutExplicitly() {
        BackupSettings settings = settings(cfg -> cfg.set("retention.tiers", List.of(
                Map.of("every", "??", "keep", 5))));

        assertTrue(settings.tiers().isEmpty());
        assertTrue(settings.tierWarnings().stream().anyMatch(w -> w.contains("예전 정책")),
                "예전 정책으로 되돌아간다는 사실을 알려야 한다");
    }

    /**
     * 계단을 <b>항목 형태가 아니게</b> 적은 경우도 알려야 한다.
     *
     * <p>{@code getMapList} 는 map 이 아닌 항목을 조용히 버려서, 위 경고에도 걸리지 않고
     * "계단을 안 쓴 것" 과 구별되지 않았다. 그러면 관리자는 자는 동안의 1시간 해상도가
     * 지켜진다고 믿는데 실제로는 예전 정책이 돌고, 그 사실은 되돌려야 하는 날에야 드러난다.</p>
     */
    @Test
    void tiersWrittenInTheWrongShapeAreCalledOut() {
        BackupSettings settings = settings(cfg ->
                cfg.set("retention.tiers", List.of("every: 0, keep: 8", "every: 1h, keep: 10")));

        assertTrue(settings.tiers().isEmpty());
        assertTrue(settings.tierWarnings().stream().anyMatch(w -> w.contains("예전 정책")),
                "예전 정책으로 동작한다는 사실을 알려야 한다: " + settings.tierWarnings());
        assertTrue(settings.tierWarnings().stream().anyMatch(w -> w.contains("every")),
                "올바른 형식을 보여 줘야 한다: " + settings.tierWarnings());
    }

    /** 반대로 일부러 비운 것은 정상적인 사용법이므로 경고하지 않는다. */
    @Test
    void anEmptyTierListIsNotAMistake() {
        BackupSettings settings = settings(cfg -> cfg.set("retention.tiers", List.of()));

        assertTrue(settings.tiers().isEmpty());
        assertTrue(settings.tierWarnings().isEmpty(),
                "계단을 쓰지 않겠다고 적은 것이다: " + settings.tierWarnings());
    }

    /** 계단이 다 솎아내도 최소 보관 개수는 지켜야 한다. */
    @Test
    void minBackupsStillAppliesUnderTiers() throws Exception {
        BackupRepository repo = repository();
        for (int day = 1; day <= 6; day++) {
            put(repo, "d" + day, at(day * 30, 12), BackupType.SCHEDULED, null);
        }

        repo.prune(settings(cfg -> {
            cfg.set("retention.tiers", List.of(Map.of("every", "1h", "keep", 1)));
            cfg.set("retention.min-backups", 3);
        }));

        assertEquals(3, ids(repo).size(), "계단 밖이어도 하한선은 남는다: " + ids(repo));
    }

    // ------------------------------------------------------------------
    // 총 용량 상한

    /**
     * 컨테이너 호스팅에서는 {@code min-free-disk-gb} 가 할당량을 못 본다.
     * 백업 폴더 크기를 직접 재서 지키는 상한이 마지막 방어선이다.
     */
    /** 1 MB 짜리 백업 6개 = 6 MB. 상한을 3.5 MB 로 잡으면 3개만 들어간다. */
    private static final int ONE_MB = 1024 * 1024;
    private static final double CAP_3_5_MB = 3.5 / 1024.0;   // GB 단위
    private static final double CAP_1_5_MB = 1.5 / 1024.0;   // 1개만 들어간다
    private static final double CAP_TINY = 0.0000001;        // 사실상 0 - 아무것도 못 담는다

    @Test
    void totalSizeCapDeletesOldestUntilItFits() throws Exception {
        BackupRepository repo = repository();
        for (int i = 1; i <= 6; i++) {
            putSized(repo, "b" + i, at(i, 12), ONE_MB);
        }

        repo.prune(settings(cfg -> {
            cfg.set("retention.max-total-size-gb", CAP_3_5_MB);
            cfg.set("retention.min-backups", 0);
        }));

        // b1 이 가장 최근(1일 전), b6 이 가장 오래됨(6일 전). 오래된 것부터 지운다.
        assertEquals(List.of("b1", "b2", "b3"), ids(repo), "상한에 맞춰 오래된 것부터 지운다");
    }

    @Test
    void totalSizeCapIsIgnoredWhenSetToZero() throws Exception {
        BackupRepository repo = repository();
        for (int i = 1; i <= 6; i++) {
            putSized(repo, "b" + i, at(i, 12), ONE_MB);
        }

        repo.prune(settings(cfg -> cfg.set("retention.max-total-size-gb", 0)));

        assertEquals(6, ids(repo).size(), "상한이 0 이면 아무것도 하지 않는다");
    }

    /** 상한을 맞추려다 되돌릴 백업을 다 지우면 안 된다. 최소 보관 개수가 우선이다. */
    @Test
    void minBackupsWinsOverTheTotalSizeCap() throws Exception {
        BackupRepository repo = repository();
        for (int i = 1; i <= 6; i++) {
            putSized(repo, "b" + i, at(i, 12), ONE_MB);
        }

        repo.prune(settings(cfg -> {
            cfg.set("retention.max-total-size-gb", CAP_TINY); // 어떻게 해도 못 맞춘다
            cfg.set("retention.min-backups", 3);
        }));

        assertEquals(3, ids(repo).size(), "용량보다 최소 보관 개수가 우선한다");
    }

    /**
     * 가장 오래된 것이 차등본을 거느린 기준 백업이면 한 번에 못 지운다.
     *
     * <p>기준은 딸린 차등본이 살아 있는 동안 못 지우는데, 그 차등본들은 기준보다 뒤에서
     * 지워진다. 한 번만 훑으면 기준을 지나친 뒤에야 지울 수 있게 되어 상한을 넘긴 채
     * 끝난다. 정작 기준이 가장 큰 파일인 경우가 많다.</p>
     */
    @Test
    void totalSizeCapReachesTheLimitEvenWhenABaseBlocksTheFirstPass() throws Exception {
        BackupRepository repo = repository();
        // 오래된 순: base(2MB) → d1 → d2, 그리고 최근 것 하나
        BackupEntry base = putSized(repo, "base", at(9, 12), 2 * ONE_MB, null);
        putSized(repo, "d1", at(8, 12), ONE_MB, base.id());
        putSized(repo, "d2", at(7, 12), ONE_MB, base.id());
        putSized(repo, "recent", at(1, 12), ONE_MB, null);

        // 총 5MB. 상한 1.5MB → recent 만 남아야 한다.
        repo.prune(settings(cfg -> {
            cfg.set("retention.max-total-size-gb", 1.5 / 1024.0);
            cfg.set("retention.min-backups", 1);
        }));

        assertEquals(List.of("recent"), ids(repo), "기준 백업까지 지워 상한을 맞춰야 한다");
    }

    /**
     * 보호된 백업도 최소 보관 개수에 포함해서 센다.
     *
     * <p>{@code rescueToMinimum} 이 그렇게 세므로 용량 상한 쪽도 같아야 한다. 따로 세면
     * 잠근 백업이 많을 때 최소 개수만큼을 그 위에 <b>또</b> 남기게 되어, 그만큼 상한을 못 맞춘다.</p>
     */
    @Test
    void protectedBackupsCountTowardTheMinimumUnderTheSizeCap() throws Exception {
        BackupRepository repo = repository();
        BackupEntry locked = putSized(repo, "locked", at(9, 12), ONE_MB);
        assertTrue(repo.setLocked(locked, true));
        for (int i = 1; i <= 4; i++) {
            putSized(repo, "b" + i, at(i, 12), ONE_MB);
        }

        // 총 5MB, 상한 1.5MB(=1개만 들어감), 최소 1개.
        // 잠근 것 하나가 이미 최소 개수를 채우므로 나머지 넷은 전부 나가야 한다.
        // 따로 세면 b1 이 최소 개수 몫으로 하나 더 남아 상한을 못 맞춘다.
        repo.prune(settings(cfg -> {
            cfg.set("retention.max-total-size-gb", CAP_1_5_MB);
            cfg.set("retention.min-backups", 1);
        }));

        assertEquals(List.of("locked"), ids(repo),
                "잠근 백업이 최소 개수를 채웠으므로 나머지는 상한에 맞춰 정리된다");
    }

    /** /wb lock 은 어떤 정책보다도 세다. 총 용량 상한도 예외가 아니다. */
    @Test
    void lockedBackupsSurviveTheTotalSizeCap() throws Exception {
        BackupRepository repo = repository();
        BackupEntry locked = putSized(repo, "locked", at(9, 12), ONE_MB);
        assertTrue(repo.setLocked(locked, true));
        putSized(repo, "b1", at(1, 12), ONE_MB);

        repo.prune(settings(cfg -> {
            cfg.set("retention.max-total-size-gb", CAP_TINY);
            cfg.set("retention.min-backups", 0);
        }));

        assertTrue(ids(repo).contains("locked"), "잠근 백업은 용량 상한으로도 지우지 않는다");
    }

    // ------------------------------------------------------------------
    // 디스크 공간 확보 (freeUpSpace)

    /**
     * 공간 확보도 최소 보관 개수 아래로는 내려가지 않는다.
     *
     * <p>여기가 빠져 있으면 하필 디스크가 빠듯한 서버 - 되돌릴 백업이 가장 필요한 상황에서 -
     * 백업 한 번이 하한선을 1개까지 깎아 낸다. 설정과 문서는 "어떤 정책으로도" 라고 약속한다.</p>
     */
    @Test
    void freeUpSpaceNeverGoesBelowTheMinimumBackupCount() throws Exception {
        BackupRepository repo = repository();
        for (int i = 1; i <= 6; i++) {
            putSized(repo, "b" + i, at(i, 12), ONE_MB);
        }

        // 6MB 를 통째로 요구해도 최소 3개는 남아야 한다.
        long freed = repo.freeUpSpace(settings(cfg -> cfg.set("retention.min-backups", 3)), 6L * ONE_MB);

        assertEquals(List.of("b1", "b2", "b3"), ids(repo), "최신 3개가 남는다");
        assertEquals(3L * ONE_MB, freed, "지운 만큼만 확보했다고 보고해야 한다");
    }

    /** 하한선을 꺼 두면 예전처럼 최소 1개는 남긴다. 전멸시키지는 않는다. */
    @Test
    void freeUpSpaceStillLeavesOneWhenNoMinimumIsSet() throws Exception {
        BackupRepository repo = repository();
        for (int i = 1; i <= 3; i++) {
            putSized(repo, "b" + i, at(i, 12), ONE_MB);
        }

        repo.freeUpSpace(settings(cfg -> cfg.set("retention.min-backups", 0)), 3L * ONE_MB);

        assertEquals(List.of("b1"), ids(repo), "하한이 없어도 가장 최근 하나는 남는다");
    }

    /**
     * 손상된 백업은 하한선에 세지 않는다. 복원에 쓸 수 없는 것을 남겨 놓고
     * "3개 지켰다" 고 하면 정작 되돌릴 때 아무것도 없다.
     */
    @Test
    void brokenBackupsDoNotCountTowardTheMinimumWhenFreeingSpace() throws Exception {
        BackupRepository repo = repository();
        putSized(repo, "good1", at(1, 12), ONE_MB);
        putSized(repo, "good2", at(2, 12), ONE_MB);
        // 사이드카도 zip 내부 메타도 없다 = 압축이 끝나지 않았거나 깨진 백업
        Files.write(repo.directory().resolve(BackupEntry.archiveName("broken")), new byte[ONE_MB]);

        repo.freeUpSpace(settings(cfg -> cfg.set("retention.min-backups", 2)), 3L * ONE_MB);

        assertEquals(List.of("good1", "good2"), ids(repo),
                "손상된 백업은 하한선과 무관하게 지워진다");
    }

    /**
     * 잠근 백업은 <b>손상으로 보여도</b> 보호를 잃지 않는다.
     *
     * <p>README 는 "{@code /wb lock} 으로 잠근 백업은 어떤 정책으로도 지워지지 않습니다" 라고
     * 약속한다. 그런데 보호 판정이 손상된 백업을 먼저 걸러 내고 있어서, 잠근 백업이 나중에
     * 손상으로 잡히면 조용히 보호를 잃고 정책에 지워졌다.</p>
     *
     * <p>"손상" 에는 <b>메타데이터를 읽지 못했다</b> 가 포함되고 그것은 일시적일 수 있다(네트워크
     * 저장소, 외부 동기화 중). 관리자가 영구 보관하려고 직접 잠근 것을 그 판정 하나로 풀어
     * 버리면, 정작 남겨 두려던 그 백업이 사라진다. 잠금 마커는 사이드카와 별개 파일이라
     * 메타데이터를 못 읽는 상황에서도 그대로 읽히므로, 그 의사는 확실히 알 수 있다.</p>
     */
    @Test
    void aLockedBackupKeepsItsProtectionEvenWhenItLooksBroken() throws Exception {
        BackupRepository repo = repository();
        put(repo, "healthy", at(1, 12), BackupType.SCHEDULED, null);

        // 사이드카도 zip 메타도 읽을 수 없지만 잠금 마커는 남아 있다.
        String id = "20260801-030000";
        Files.write(repo.directory().resolve(BackupEntry.archiveName(id)), new byte[ONE_MB]);
        Files.writeString(repo.directory().resolve(BackupEntry.lockName(id)), id, StandardCharsets.UTF_8);

        repo.prune(settings(cfg -> {
            cfg.set("retention.max-age-days", 1);
            cfg.set("retention.min-backups", 0);
        }));

        assertTrue(ids(repo).contains(id),
                "/wb lock 은 어떤 정책보다 우선한다고 문서가 약속한다: " + ids(repo));
    }

    /**
     * 보관 정리도 <b>전부 손상으로 보인다는 이유로</b> 폴더를 비우지 않는다.
     *
     * <p>"손상" 판정에는 "사이드카와 zip 메타를 둘 다 못 읽었다" 가 포함된다. 정말 깨진 백업일
     * 수도 있지만, 네트워크 저장소의 일시적인 I/O 오류이거나 백업 폴더를 밖에서 동기화하는
     * 중(zip 은 도착했고 사이드카는 아직)일 수도 있다 - README 가 백업본을 외부로 복사하라고
     * 권하므로 후자는 실제로 일어난다.</p>
     *
     * <p>{@link BackupRepository#freeUpSpace} 는 이 규칙을 지키는데 정리 쪽에는 없었다.
     * 한쪽만 지키면 다른 쪽이 비운다. 하필 계단식이 <b>기본값</b>이라 기본 설정에서 걸린다 -
     * 계단 대표는 멀쩡한 백업에서만 뽑히므로, 전부 손상으로 보이면 남길 것이 하나도 없다.</p>
     */
    @Test
    void pruneNeverEmptiesTheFolderWhenNothingLooksReadable() throws Exception {
        BackupRepository repo = repository();
        for (int i = 1; i <= 3; i++) {
            Files.write(repo.directory().resolve(BackupEntry.archiveName("broken" + i)), new byte[ONE_MB]);
        }

        repo.prune(settings(cfg -> cfg.set("retention.tiers",
                List.of(Map.of("every", "0", "keep", 8)))));

        assertEquals(1, ids(repo).size(),
                "전부 손상으로 보여도 되돌릴 곳을 통째로 없애면 안 된다: " + ids(repo));
    }

    /** 총 용량 상한 경로도 같다. 상한이 한 개보다 작으면 전부 지우려 든다. */
    @Test
    void theTotalSizeCapAlsoLeavesOneWhenNothingLooksReadable() throws Exception {
        BackupRepository repo = repository();
        for (int i = 1; i <= 3; i++) {
            Files.write(repo.directory().resolve(BackupEntry.archiveName("broken" + i)), new byte[ONE_MB]);
        }

        // 0.0005GB = 약 512KB. 한 개(1MB)조차 넘는 상한이다.
        repo.prune(settings(cfg -> cfg.set("retention.max-total-size-gb", 0.0005)));

        assertEquals(1, ids(repo).size(),
                "상한을 못 맞추더라도 마지막 하나는 남긴다: " + ids(repo));
    }

    /** 반대로 <b>읽을 수 있는</b> 백업이 있었다면 min-backups: 0 의 결정을 존중한다. */
    @Test
    void readableBackupsAreStillDeletableDownToZero() throws Exception {
        BackupRepository repo = repository();
        put(repo, "old", at(40, 9), BackupType.SCHEDULED, null);

        repo.prune(settings(cfg -> {
            cfg.set("retention.max-age-days", 14);
            cfg.set("retention.min-backups", 0);
        }));

        assertTrue(ids(repo).isEmpty(), "하한선을 끈 것은 관리자의 명시적인 선택이다");
    }

    /**
     * 공간 확보도 마지막 하나는 남긴다.
     *
     * <p>"손상" 판정에는 "사이드카와 zip 메타를 둘 다 못 읽었다" 도 포함된다. 디스크가 잠깐
     * 삐끗해서 멀쩡한 백업이 그렇게 잡힐 수 있는데, 그 판정 하나를 믿고 백업 폴더를 통째로
     * 비우면 되돌릴 곳이 아예 없어진다.</p>
     */
    @Test
    void freeUpSpaceNeverEmptiesTheFolderEvenIfEverythingLooksBroken() throws Exception {
        BackupRepository repo = repository();
        for (int i = 1; i <= 3; i++) {
            Files.write(repo.directory().resolve(BackupEntry.archiveName("broken" + i)), new byte[ONE_MB]);
        }

        repo.freeUpSpace(settings(cfg -> cfg.set("retention.min-backups", 0)), 3L * ONE_MB);

        assertEquals(1, ids(repo).size(), "전부 손상으로 보여도 하나는 남는다");
    }

    /**
     * 딸린 차등본을 지운 뒤에는 그 기준 백업도 <b>같은 회차에</b> 지울 수 있어야 한다.
     *
     * <p>기준은 대개 딸린 차등본보다 오래되어 목록을 오래된 것부터 훑을 때 먼저 만난다.
     * 그때는 아직 차등본이 살아 있으니 건너뛰는데, 한 번만 훑으면 그 뒤로 차등본이 지워져도
     * 기준을 다시 볼 기회가 없다. 정작 기준이 가장 큰 파일인 경우가 많아서, 공간이 급한
     * 상황에서 확보량이 크게 모자라게 된다. {@code prune} 은 이미 변화가 없을 때까지 돈다.</p>
     */
    @Test
    void freeUpSpaceDeletesABaseOnceItsDifferentialsAreGone() throws Exception {
        BackupRepository repo = repository();
        BackupEntry base = putSized(repo, "base", at(9, 12), 4 * ONE_MB, null); // 가장 오래되고 가장 크다
        putSized(repo, "diff", at(8, 12), ONE_MB, base.id());
        BackupEntry locked = putSized(repo, "locked", at(1, 12), ONE_MB);
        assertTrue(repo.setLocked(locked, true)); // 잠긴 백업이 남으므로 기준도 지울 수 있다

        long freed = repo.freeUpSpace(settings(cfg -> cfg.set("retention.min-backups", 0)), 5L * ONE_MB);

        assertEquals(5L * ONE_MB, freed, "차등본이 사라졌으면 그 기준까지 지워 공간을 맞춰야 한다");
        assertEquals(List.of("locked"), ids(repo));
    }

    /** 잠근 백업은 공간 확보로도 지우지 않는다. */
    @Test
    void freeUpSpaceRespectsLockedBackups() throws Exception {
        BackupRepository repo = repository();
        BackupEntry locked = putSized(repo, "locked", at(9, 12), ONE_MB);
        assertTrue(repo.setLocked(locked, true));
        putSized(repo, "b1", at(1, 12), ONE_MB);

        repo.freeUpSpace(settings(cfg -> cfg.set("retention.min-backups", 0)), 2L * ONE_MB);

        assertTrue(ids(repo).contains("locked"), "잠근 백업은 공간이 급해도 남는다");
    }

    /**
     * 지정한 크기의 백업을 심는다.
     *
     * <p>{@code archiveBytes} 는 메타데이터가 아니라 <b>실제 파일 크기</b>에서 읽으므로,
     * 크기를 흉내 내려면 그만큼을 실제로 써야 한다.</p>
     */
    private BackupEntry putSized(BackupRepository repo, String id, Instant createdAt, int bytes)
            throws IOException {
        return putSized(repo, id, createdAt, bytes, null);
    }

    private BackupEntry putSized(BackupRepository repo, String id, Instant createdAt, int bytes, String baseId)
            throws IOException {
        Path archive = repo.directory().resolve(BackupEntry.archiveName(id));
        Files.write(archive, new byte[bytes]);
        BackupEntry entry = new BackupEntry(id, archive, createdAt, BackupType.SCHEDULED, null,
                Files.size(archive), 0L, 0, List.of("world"), List.of("world"), List.of(),
                "test", false, true, baseId, true);
        repo.writeMeta(entry);
        return entry;
    }

    // ------------------------------------------------------------------
    // 플레이어 데이터 포함 여부 기록

    /**
     * 복원하고 나서야 인벤토리가 안 돌아온다는 걸 알게 되면 늦는다.
     * 백업 시점에 기록해 두고 목록·복원 확인창에서 미리 보여 줘야 한다.
     */
    @Test
    void playerDataFlagSurvivesAMetadataRoundTrip() throws Exception {
        BackupRepository repo = repository();
        BackupEntry included = put(repo, "with-players", at(1, 9), BackupType.MANUAL, null, Boolean.TRUE);
        BackupEntry missing = put(repo, "no-players", at(2, 9), BackupType.MANUAL, null, Boolean.FALSE);

        assertTrue(reload(repo, included.id()).hasPlayerData());
        assertFalse(reload(repo, included.id()).playerDataUnknown());

        assertFalse(reload(repo, missing.id()).hasPlayerData());
        assertFalse(reload(repo, missing.id()).playerDataUnknown(), "기록은 있고 값이 false 인 경우");
    }

    /**
     * 이 정보를 기록하지 않던 시절의 백업은 "없음" 이 아니라 <b>"모름"</b> 이다.
     * 모름을 없음으로 단정하면 멀쩡한 옛 백업까지 경고를 달게 되고,
     * 모름을 있음으로 뭉개면 정작 필요할 때 인벤토리가 안 돌아온다.
     */
    @Test
    void oldBackupsWithoutTheFlagAreReportedAsUnknown() throws Exception {
        BackupRepository repo = repository();
        BackupEntry entry = put(repo, "legacy", at(3, 9), BackupType.SCHEDULED, null, null);

        // 옛 버전이 쓰던 사이드카에는 player-data 키 자체가 없다.
        Path meta = entry.metaFile();
        String yaml = Files.readString(meta, StandardCharsets.UTF_8);
        assertFalse(yaml.contains("player-data:"), "이 테스트는 키가 없는 상태를 재현해야 한다");

        BackupEntry loaded = reload(repo, "legacy");
        assertTrue(loaded.playerDataUnknown(), "키가 없으면 모름이다");
        assertFalse(loaded.hasPlayerData(), "모름을 포함으로 뭉개면 안 된다");
    }

    private BackupEntry reload(BackupRepository repo, String id) {
        return repo.list().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // 남은 찌꺼기 정리

    @Test
    void cleanupOrphansRemovesTempAndDanglingSidecars() throws Exception {
        BackupRepository repo = repository();
        BackupEntry healthy = put(repo, "healthy", at(1, 9), BackupType.SCHEDULED, null);
        Path dir = repo.directory();

        Files.writeString(dir.resolve("wb-20260101-000000.zip.tmp"), "압축 도중 서버가 죽었다");
        Files.writeString(dir.resolve("wb-20260101-000001.yml"), "zip 이 사라진 메타");
        Files.writeString(dir.resolve("wb-20260101-000002.locked"), "zip 이 사라진 보호 마커");

        assertEquals(3, repo.cleanupOrphans());

        assertTrue(Files.exists(healthy.archive()), "정상 백업은 건드리지 않는다");
        assertTrue(Files.exists(healthy.metaFile()), "짝이 맞는 사이드카도 남는다");
        assertFalse(Files.exists(dir.resolve("wb-20260101-000000.zip.tmp")));
        assertFalse(Files.exists(dir.resolve("wb-20260101-000001.yml")));
        assertFalse(Files.exists(dir.resolve("wb-20260101-000002.locked")));
    }

    // ------------------------------------------------------------------
    // 목록 캐시

    /**
     * 훑는 동안 저장소가 바뀌면 그 결과를 캐시에 넣지 않는다.
     *
     * <p>{@code list()} 는 짧은 캐시를 둔다. 그런데 스캔이 도는 사이에 다른 스레드가 저장소를
     * 바꾸면, 스캔이 끝나며 <b>바뀌기 전의 목록</b>을 캐시에 써 넣는다. 캐시 유효 시간 동안
     * 그 옛 목록이 통용된다.</p>
     *
     * <p>여기서는 {@code /wb lock} 을 쓴다. 잠갔다고 답을 받은 백업이 목록에서는 여전히
     * 안 잠긴 것으로 보이면, 바로 뒤이어 도는 보관 정리가 그 백업을 지운다. 관리자가
     * 남겨 두려고 잠근 백업이 사라지는 것이라 실패 방향이 최악이다. 보관 정리는 비동기로
     * 돌고 {@code /wb lock}·탭 완성은 메인 스레드에서 도니 실제로 겹칠 수 있는 구조다.</p>
     */
    @Test
    void aScanThatRacedWithAChangeIsNotCached() throws Exception {
        GatedRepository repo = new GatedRepository(tmp.resolve("server/plugins/WorldBackUp/backups"));
        repo.ensureDirectory();
        put(repo, "a", at(3, 9), BackupType.SCHEDULED, null);
        put(repo, "b", at(2, 9), BackupType.SCHEDULED, null);
        put(repo, "c", at(1, 9), BackupType.SCHEDULED, null);
        repo.arm();

        // 첫 항목을 읽어 낸 직후에 스캔을 붙잡아 둔다.
        Thread scanner = new Thread(repo::list, "list-scan");
        scanner.start();
        assertTrue(repo.scanning.await(5, TimeUnit.SECONDS), "스캔이 시작되어야 한다");

        // 스캔이 이미 읽어 간 그 백업을 잠근다.
        BackupEntry alreadyRead = repo.firstRead;
        assertNotNull(alreadyRead, "스캔이 항목 하나는 읽었어야 한다");
        assertTrue(repo.setLocked(alreadyRead, true));

        repo.released.countDown();
        scanner.join(5_000);

        BackupEntry reloaded = repo.list().stream()
                .filter(e -> e.id().equals(alreadyRead.id())).findFirst().orElseThrow();
        assertTrue(reloaded.locked(),
                "잠갔다고 답한 백업이 목록에서 안 잠긴 것으로 보이면 보관 정리가 지워 버린다");

        // 보관 정리가 실제로 그 백업을 지키는지까지 확인한다.
        repo.prune(settings(cfg -> {
            cfg.set("retention.max-age-days", 1);
            cfg.set("retention.max-backups", 1);
        }));
        assertTrue(ids(repo).contains(alreadyRead.id()), "잠근 백업은 남아야 한다");
    }

    /** 첫 항목을 읽어 낸 <b>직후</b> 한 번 멈춰 서서, 스캔이 도는 중임을 테스트가 알 수 있게 한다. */
    private static final class GatedRepository extends BackupRepository {

        final CountDownLatch scanning = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);

        /** 붙잡기 직전에 스캔이 읽어 낸 항목. */
        volatile BackupEntry firstRead;

        private final AtomicBoolean gate = new AtomicBoolean(false);

        GatedRepository(Path directory) {
            super(directory, LOG);
        }

        /** 준비가 끝난 뒤에만 멈춘다. 백업을 심는 동안의 read 는 그대로 통과시킨다. */
        void arm() {
            gate.set(true);
        }

        @Override
        public Optional<BackupEntry> read(Path archive) {
            Optional<BackupEntry> result = super.read(archive);
            if (gate.compareAndSet(true, false)) {
                firstRead = result.orElse(null);
                scanning.countDown();
                try {
                    released.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result;
        }
    }

    // ------------------------------------------------------------------
    // 도우미

    private BackupRepository repository() throws IOException {
        BackupRepository repo = new BackupRepository(tmp.resolve("server/plugins/WorldBackUp/backups"), LOG);
        repo.ensureDirectory();
        return repo;
    }

    /** 보관 정책을 전부 꺼 둔 기본값 위에, 테스트가 필요한 항목만 켠다. */
    private BackupSettings settings(Consumer<YamlConfiguration> tweak) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("retention.max-backups", 0);
        cfg.set("retention.min-backups", 0);
        cfg.set("retention.max-age-days", 0);
        cfg.set("retention.keep-daily", 0);
        cfg.set("retention.protect-manual", false);
        cfg.set("retention.max-protected", 0);
        tweak.accept(cfg);
        return BackupSettings.load(cfg, tmp.resolve("server/plugins/WorldBackUp"), tmp.resolve("server"));
    }

    /**
     * 가짜 백업 하나를 심는다. 사이드카 메타가 있으면 저장소는 zip 을 열지 않으므로
     * 내용은 아무거나 좋다. 중요한 것은 파일 이름과 메타에 적힌 시각이다.
     */
    private BackupEntry put(BackupRepository repo, String id, Instant createdAt,
                            BackupType type, String baseId) throws IOException {
        return put(repo, id, createdAt, type, baseId, Boolean.TRUE);
    }

    /** @param playerData null 이면 이 정보를 기록하지 않던 시절의 백업을 재현한다 */
    private BackupEntry put(BackupRepository repo, String id, Instant createdAt,
                            BackupType type, String baseId, Boolean playerData) throws IOException {
        Path archive = repo.directory().resolve(BackupEntry.archiveName(id));
        Files.writeString(archive, "payload-" + id, StandardCharsets.UTF_8);
        BackupEntry entry = new BackupEntry(id, archive, createdAt, type, null,
                Files.size(archive), 0L, 0, List.of("world"), List.of("world"), List.of(),
                "test", false, true, baseId, playerData);
        repo.writeMeta(entry);
        return entry;
    }

    /** 남아 있는 백업 id 를 최신순으로. */
    private List<String> ids(BackupRepository repo) {
        return repo.list().stream().map(BackupEntry::id).toList();
    }

    /**
     * {@code daysAgo} 일 전 {@code hour} 시 정각.
     * "지금 - N일" 로 만들면 자정 근처에 실행될 때 날짜가 어긋나므로 날짜를 직접 고정한다.
     */
    private static Instant at(int daysAgo, int hour) {
        return LocalDate.now(ZoneId.systemDefault())
                .minusDays(daysAgo)
                .atTime(hour, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant();
    }
}
