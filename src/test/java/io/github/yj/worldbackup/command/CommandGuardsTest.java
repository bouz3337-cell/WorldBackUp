package io.github.yj.worldbackup.command;

import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.BackupType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파괴적인 명령이 <b>무엇을 거부하는지</b> 검증한다.
 *
 * <p>여기 있는 판단들은 얼마 전까지 자동 검증 밖에 있었다. 명령어 처리부가 {@code JavaPlugin} 과
 * Brigadier 에 묶여 서버 없이 인스턴스화되지 않기 때문이다. 그래서 "잠근 백업이 cascade 한 번에
 * 사라지지 않는지" 같은 가장 위험한 계약이 <b>읽어서 확인한 것</b>뿐이었다.
 * {@link CommandGuards} 로 판단만 떼어 낸 뒤 이 파일이 그것을 전부 덮는다.</p>
 */
class CommandGuardsTest {

    // ------------------------------------------------------------------
    // /wb prune

    @Test
    void pruneRunsOnlyWhenNothingIsInTheWay() {
        assertEquals(CommandGuards.Prune.OK, CommandGuards.prune(false, false));
    }

    /**
     * 복원 실패 정지 중에는 보관 정리를 하지 않는다.
     *
     * <p>이 정지가 지키려는 것이 "반쯤 복원된 월드가 백업되면서 멀쩡한 예전 백업이 정책에 밀려
     * 사라지는 것" 이다. 자동 주기·백업 뒤 정리·시작 시 정리·공간 확보가 모두 이 정지를 지키는데
     * 정작 정책을 직접 부르는 이 명령만 빠져 있었다. 하필 디스크가 모자란 상황에서 관리자가
     * 가장 먼저 떠올리는 명령이다.</p>
     */
    @Test
    void pruneIsRefusedWhileARestoreFailureIsUnresolved() {
        assertEquals(CommandGuards.Prune.RESTORE_FAILURE_HOLD, CommandGuards.prune(false, true));
    }

    @Test
    void pruneWaitsForARunningBackup() {
        assertEquals(CommandGuards.Prune.BACKUP_RUNNING, CommandGuards.prune(true, false));
        assertEquals(CommandGuards.Prune.BACKUP_RUNNING, CommandGuards.prune(true, true),
                "둘 다면 먼저 걸리는 쪽을 말한다");
    }

    // ------------------------------------------------------------------
    // /wb delete

    @Test
    void aPlainBackupWithNoDependentsIsDeletable() {
        assertEquals(CommandGuards.Delete.OK,
                CommandGuards.delete(entry("full", false, true, null), false, List.of(), false));
    }

    @Test
    void aLockedBackupIsNeverDeletedByCommand() {
        assertEquals(CommandGuards.Delete.LOCKED,
                CommandGuards.delete(entry("full", true, true, null), false, List.of(), false));
        assertEquals(CommandGuards.Delete.LOCKED,
                CommandGuards.delete(entry("full", true, true, null), false, List.of(), true),
                "cascade 를 붙여도 잠긴 것은 지우지 않는다");
    }

    /** 지금 만들어지는 차등 백업의 기준은 지우지 않는다. 지우면 그 차등본이 태어나자마자 복원 불가다. */
    @Test
    void theBaseOfAnInFlightDifferentialIsProtected() {
        assertEquals(CommandGuards.Delete.PINNED,
                CommandGuards.delete(entry("full", false, true, null), true, List.of(), true));
    }

    /** 딸린 차등본이 있으면 cascade 없이는 지우지 않는다. */
    @Test
    void aBaseWithDependentsNeedsCascade() {
        List<BackupEntry> dependents = List.of(entry("diff", false, true, "full"));

        assertEquals(CommandGuards.Delete.NEEDS_CASCADE,
                CommandGuards.delete(entry("full", false, true, null), false, dependents, false));
        assertEquals(CommandGuards.Delete.OK,
                CommandGuards.delete(entry("full", false, true, null), false, dependents, true));
    }

    /**
     * <b>이 테스트가 이 파일의 이유다.</b>
     *
     * <p>잠긴 차등본이 딸린 기준 백업은 {@code cascade} 를 붙였든 안 붙였든 거부해야 한다.
     * 기준을 지우는 것은 곧 그 차등본을 못 쓰게 만드는 것이기 때문이다. 이 검사가 cascade 분기
     * <b>뒤로</b> 밀리면 {@code /wb delete X cascade} 한 번에 관리자가 잠근 백업이 사라진다.
     * 순서가 곧 계약이므로 여기서 못 박는다.</p>
     */
    @Test
    void aLockedDifferentialBlocksItsBaseEvenWithCascade() {
        List<BackupEntry> dependents = List.of(
                entry("diff1", false, true, "full"),
                entry("diff2", true, true, "full")); // 잠긴 차등본

        assertEquals(CommandGuards.Delete.LOCKED_DEPENDENTS,
                CommandGuards.delete(entry("full", false, true, null), false, dependents, false));
        assertEquals(CommandGuards.Delete.LOCKED_DEPENDENTS,
                CommandGuards.delete(entry("full", false, true, null), false, dependents, true),
                "cascade 가 잠긴 차등본을 지나쳐서는 안 된다");
    }

    /** 거부 사유와 관리자에게 보여 줄 목록은 같은 계산이어야 한다. */
    @Test
    void theRefusalAndTheListedLockedDependentsAgree() {
        List<BackupEntry> dependents = List.of(
                entry("diff1", false, true, "full"),
                entry("diff2", true, true, "full"),
                entry("diff3", true, true, "full"));

        assertEquals(CommandGuards.Delete.LOCKED_DEPENDENTS,
                CommandGuards.delete(entry("full", false, true, null), false, dependents, true));
        assertEquals(List.of("diff2", "diff3"),
                CommandGuards.lockedAmong(dependents).stream().map(BackupEntry::id).toList());
    }

    /** 손상된 백업은 잠겨 있지 않다면 그대로 지울 수 있어야 한다. 치울 방법이 없으면 쌓인다. */
    @Test
    void aBrokenBackupIsStillDeletable() {
        assertEquals(CommandGuards.Delete.OK,
                CommandGuards.delete(entry("broken", false, false, null), false, List.of(), false));
    }

    // ------------------------------------------------------------------
    // /wb lock

    @Test
    void aHealthyBackupCanBeLocked() {
        assertEquals(CommandGuards.Lock.OK, CommandGuards.lock(entry("full", false, true, null), true));
    }

    /** 복원에 쓸 수 없는 것을 "남겨 뒀다" 고 믿게 하면 안 된다. */
    @Test
    void aBrokenBackupCannotBeLocked() {
        assertEquals(CommandGuards.Lock.BROKEN, CommandGuards.lock(entry("broken", false, false, null), true));
    }

    /** 해제는 손상 여부와 무관하게 언제나 된다. 막으면 잘못 잠근 것을 되돌릴 수 없다. */
    @Test
    void unlockingIsAlwaysAllowed() {
        assertEquals(CommandGuards.Lock.OK, CommandGuards.lock(entry("broken", true, false, null), false));
        assertEquals(CommandGuards.Lock.OK, CommandGuards.lock(entry("full", true, true, null), false));
    }

    // ------------------------------------------------------------------

    /**
     * 이 판단들이 Bukkit 에 다시 묶이지 않게 못 박는다.
     *
     * <p>{@link CommandGuards} 가 서버 없이 검증되는 것은 <b>Bukkit 을 만지지 않기 때문</b>이다.
     * 편의로 {@code CommandSender} 하나만 받게 고치는 순간 이 파일 전체가 다시 검증 밖으로
     * 나가는데, 그 사실은 아무 테스트도 깨지지 않으므로 눈에 띄지 않는다. 그래서 소스를 직접 본다.</p>
     */
    @Test
    void theGuardsStayIndependentOfTheServer() throws IOException {
        Path source = Path.of("src/main/java/io/github/yj/worldbackup/command/CommandGuards.java");
        assertTrue(Files.isRegularFile(source), "소스를 찾지 못했습니다: " + source.toAbsolutePath());

        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) continue;
            assertTrue(!trimmed.contains("org.bukkit") && !trimmed.contains("io.papermc"),
                    "판단부가 서버에 묶이면 서버 없이 검증할 수 없게 된다: " + trimmed);
        }
    }

    // ------------------------------------------------------------------

    private static BackupEntry entry(String id, boolean locked, boolean complete, String baseId) {
        return new BackupEntry(id, Path.of("backups", BackupEntry.archiveName(id)), Instant.now(),
                BackupType.SCHEDULED, null, 1024L, 2048L, 3, List.of("world"), List.of("world"),
                List.of(), "test", locked, complete, baseId, true);
    }
}
