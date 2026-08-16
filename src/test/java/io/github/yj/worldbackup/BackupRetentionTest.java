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
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void allTiersBrokenIsCalledOutExplicitly() {
        BackupSettings settings = settings(cfg -> cfg.set("retention.tiers", List.of(
                Map.of("every", "??", "keep", 5))));

        assertTrue(settings.tiers().isEmpty());
        assertTrue(settings.tierWarnings().stream().anyMatch(w -> w.contains("예전 정책")),
                "예전 정책으로 되돌아간다는 사실을 알려야 한다");
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
