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
        Path archive = repo.directory().resolve(BackupEntry.archiveName(id));
        Files.writeString(archive, "payload-" + id, StandardCharsets.UTF_8);
        BackupEntry entry = new BackupEntry(id, archive, createdAt, type, null,
                Files.size(archive), 0L, 0, List.of("world"), List.of("world"), List.of(),
                "test", false, true, baseId);
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
