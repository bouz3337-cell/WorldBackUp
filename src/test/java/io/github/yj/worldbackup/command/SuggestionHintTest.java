package io.github.yj.worldbackup.command;

import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.BackupType;
import io.github.yj.worldbackup.command.WorldBackUpCommand.TimeHint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 탭 완성 설명이 <b>거짓말하지 않는지</b> 검증한다.
 *
 * <p>{@code 30m} 옆에 "2026-08-17 08:45 로 되돌립니다" 라고 적어 두고 실제로는 다른 백업이
 * 골라진다면, 그 설명은 없느니만 못하다. 사고 직후에 그걸 믿고 누르기 때문이다.
 * 그래서 설명을 만드는 계산이 명령이 실제로 쓰는 계산과 같은지를 못 박아 둔다.</p>
 */
class SuggestionHintTest {

    private static final Logger LOG = Logger.getLogger("SuggestionHintTest");

    @TempDir
    Path tmp;

    /**
     * 설명에 적힌 백업이 명령이 실제로 고르는 백업과 같아야 한다.
     *
     * <p>{@link BackupRepository#resolveAtOrBefore(Instant)} 가 정답이다. 두 계산이 갈라지면
     * 여기서 깨진다.</p>
     */
    @Test
    void everyHintPointsAtWhatTheCommandWouldActuallyPick() throws Exception {
        BackupRepository repo = repository();
        put(repo, "now", minutesAgo(2), true);
        put(repo, "h1", minutesAgo(70), true);
        put(repo, "h5", minutesAgo(300), true);
        put(repo, "d2", minutesAgo(60 * 48), true);

        Instant now = Instant.now();
        List<BackupEntry> entries = repo.list();

        for (TimeHint hint : WorldBackUpCommand.TIME_HINTS) {
            if (hint.back() == null) continue; // latest 는 아래에서 따로 본다
            BackupEntry shown = hint.resolve(entries, now);
            Optional<BackupEntry> picked = repo.resolveAtOrBefore(now.minus(hint.back()));

            assertEquals(picked.map(BackupEntry::id).orElse(null),
                    shown == null ? null : shown.id(),
                    hint.token() + " 의 설명과 실제로 골라지는 백업이 달라졌다");
        }
    }

    /** {@code latest} 는 손상 여부와 무관하게 가장 최근 것이다. 명령도 그렇게 동작한다. */
    @Test
    void latestFollowsTheCommandEvenWhenTheNewestIsBroken() throws Exception {
        BackupRepository repo = repository();
        put(repo, "healthy", minutesAgo(60), true);
        // 메타를 못 읽는 백업의 시각은 파일 이름에서 나온다. 그러니 이름도 실제 시각으로 만들어야
        // 한다. 예전에는 시각 하나를 박아 넣어서, 벽시계가 그 한 시간 안에 있을 때만 이 백업이
        // 최신이 되었다 - 하루만 지나면 그냥 깨지는 테스트였다.
        Files.writeString(
                repo.directory().resolve(BackupEntry.archiveName(BackupEntry.newId(minutesAgo(10)))),
                "메타를 읽을 수 없는 조각", StandardCharsets.UTF_8);

        List<BackupEntry> entries = repo.list();
        BackupEntry shown = latest().resolve(entries, Instant.now());

        assertEquals(repo.resolve("latest").orElseThrow().id(), shown.id(),
                "latest 는 목록 맨 위를 그대로 가리켜야 한다");
        assertTrue(WorldBackUpCommand.describe(shown).contains("손상"),
                "복원할 수 없는 백업이면 설명에 그렇게 적혀야 한다");
    }

    /** 보관 범위보다 과거를 가리키는 토큰은 아예 띄우지 않는다. 없는 선택지를 보여 주지 않는다. */
    @Test
    void hintsBeyondTheRetainedRangeAreDropped() throws Exception {
        BackupRepository repo = repository();
        put(repo, "only", minutesAgo(30), true);

        List<BackupEntry> entries = repo.list();
        assertNull(new TimeHint("7d", "7일 전", Duration.ofDays(7)).resolve(entries, Instant.now()),
                "7일 전 백업이 없으면 그 줄은 나오면 안 된다");
    }

    /** 손상된 백업은 시각 토큰의 대상이 되지 않는다. 복원에 쓸 수 없기 때문이다. */
    @Test
    void brokenBackupsAreNeverTheTargetOfATimeToken() throws Exception {
        BackupRepository repo = repository();
        put(repo, "broken-newer", minutesAgo(90), false);
        put(repo, "healthy-older", minutesAgo(200), true);

        BackupEntry shown = new TimeHint("1h", "1시간 전", Duration.ofHours(1))
                .resolve(repo.list(), Instant.now());

        assertEquals("healthy-older", shown.id(), "손상된 백업을 건너뛰고 그 이전 것을 골라야 한다");
    }

    /** 되돌리기 전에 알아야 할 것이 설명에 들어 있어야 한다. */
    @Test
    void descriptionCarriesWhatMattersBeforeRollingBack() throws Exception {
        BackupRepository repo = repository();
        BackupEntry entry = new BackupEntry("x", repo.directory().resolve("wb-x.zip"),
                minutesAgo(10), BackupType.MANUAL, null, 1024L * 1024, 0L, 0,
                List.of("world"), List.of("world"), List.of(), "test",
                true, true, "base-1", Boolean.FALSE);

        String text = WorldBackUpCommand.describe(entry);

        assertTrue(text.contains("1.0 MB"), text);
        assertTrue(text.contains("수동"), text);
        assertTrue(text.contains("차등"), text);
        assertTrue(text.contains("보호"), text);
        assertTrue(text.contains("플레이어 없음"), "인벤토리가 없다는 사실이 가장 중요하다: " + text);
    }

    /** 날짜 줄에는 그 날 무엇이 얼마나 있는지가 들어간다. 하나씩 눌러 보게 두지 않는다. */
    @Test
    void dayDescriptionShowsCountSpanAndSize() throws Exception {
        BackupRepository repo = repository();
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        BackupEntry newest = put(repo, "a", today.atTime(9, 15).atZone(ZoneId.systemDefault()).toInstant(), true);
        BackupEntry oldest = put(repo, "b", today.atTime(3, 0).atZone(ZoneId.systemDefault()).toInstant(), true);

        String text = WorldBackUpCommand.describeDay(today, List.of(newest, oldest));

        assertTrue(text.contains("(오늘)"), text);
        assertTrue(text.contains("2개"), text);
        assertTrue(text.contains("03:00~09:15"), "가장 이른 시각부터 늦은 시각까지: " + text);
    }

    // ------------------------------------------------------------------

    private static TimeHint latest() {
        return WorldBackUpCommand.TIME_HINTS.stream()
                .filter(hint -> hint.back() == null).findFirst().orElseThrow();
    }

    private BackupRepository repository() throws IOException {
        BackupRepository repo = new BackupRepository(tmp.resolve("backups"), LOG);
        repo.ensureDirectory();
        return repo;
    }

    private BackupEntry put(BackupRepository repo, String id, Instant createdAt, boolean complete)
            throws IOException {
        Path archive = repo.directory().resolve(BackupEntry.archiveName(id));
        Files.writeString(archive, "payload-" + id, StandardCharsets.UTF_8);
        BackupEntry entry = new BackupEntry(id, archive, createdAt, BackupType.SCHEDULED, null,
                Files.size(archive), 0L, 0, List.of("world"), List.of("world"), List.of(),
                "test", false, true, complete ? null : "사라진-기준", true);
        repo.writeMeta(entry);
        return entry;
    }

    private static Instant minutesAgo(int minutes) {
        return Instant.now().minus(Duration.ofMinutes(minutes));
    }
}
