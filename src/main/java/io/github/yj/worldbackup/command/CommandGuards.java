package io.github.yj.worldbackup.command;

import io.github.yj.worldbackup.backup.BackupEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 파괴적인 명령을 실행해도 되는지 판단한다.
 *
 * <p><b>이 파일에는 Bukkit·Paper import 가 하나도 없다.</b> 그것이 이 파일이 존재하는 이유다 -
 * 명령어 처리부는 {@code JavaPlugin} 과 Brigadier 에 묶여 있어서 서버 없이 인스턴스화할 수
 * 없고, 그래서 <b>무엇을 지우지 않는가</b> 하는 가장 위험한 판단들이 자동 검증 밖에 있었다.
 * 판단만 여기로 옮기면 그 부분은 서버 없이 전부 검증된다. (같은 이유로 만든 것들:
 * {@link io.github.yj.worldbackup.backup.ServerBridge},
 * {@link io.github.yj.worldbackup.restore.RestoreService#selectedRoots})</p>
 *
 * <p>안내 문구는 여기 두지 않는다. 문구가 섞이면 판단을 검증하려고 문구까지 붙잡아 두게 되고,
 * 문구를 다듬을 때마다 테스트가 깨진다. 여기서는 <b>왜 거부하는지</b>만 정하고 명령이 그에 맞는
 * 안내를 고른다.</p>
 *
 * <p>검사 <b>순서</b>도 계약이다. 예를 들어 잠긴 차등본이 딸린 기준 백업은 {@code cascade} 를
 * 붙였든 안 붙였든 거부해야 한다 - 순서가 바뀌면 {@code /wb delete X cascade} 한 번에
 * {@code /wb lock} 으로 잠근 백업이 사라진다. 그래서 테스트가 순서를 못 박는다.</p>
 */
public final class CommandGuards {

    private CommandGuards() {
    }

    // ------------------------------------------------------------------
    // /wb prune

    /** {@code /wb prune} 을 지금 돌려도 되는지. */
    public enum Prune {
        OK,
        /** 백업이 도는 중이다. 그 백업이 만드는 파일과 정리가 엉킨다. */
        BACKUP_RUNNING,
        /**
         * 복원 실패 기록이 남아 있다.
         *
         * <p>그 정지가 존재하는 이유가 "반쯤 복원된 월드가 백업되면서 멀쩡한 예전 백업이 정책에
         * 밀려 사라지는 것" 이다. 자동 주기·백업 뒤 정리·시작 시 정리·공간 확보가 모두 이 정지를
         * 지키는데 정작 정책을 직접 부르는 이 명령만 빠져 있었다.</p>
         */
        RESTORE_FAILURE_HOLD
    }

    public static Prune prune(boolean backupRunning, boolean restoreFailureHold) {
        if (backupRunning) return Prune.BACKUP_RUNNING;
        if (restoreFailureHold) return Prune.RESTORE_FAILURE_HOLD;
        return Prune.OK;
    }

    // ------------------------------------------------------------------
    // /wb delete

    /** {@code /wb delete} 로 이 백업을 지워도 되는지. */
    public enum Delete {
        OK,
        /** {@code /wb lock} 으로 잠갔다. 먼저 풀어야 한다. */
        LOCKED,
        /** 지금 만들어지는 차등 백업의 기준이다. 지우면 그 차등본이 태어나자마자 복원 불가가 된다. */
        PINNED,
        /**
         * 잠긴 차등 백업이 이 기준을 붙잡고 있다.
         *
         * <p>기준을 지우는 것은 곧 딸린 차등본을 못 쓰게 만드는 것이라, {@code cascade} 여부와
         * <b>무관하게</b> 거부한다. 이 검사가 뒤로 밀리면 잠근 백업이 cascade 한 번에 사라진다.</p>
         */
        LOCKED_DEPENDENTS,
        /** 딸린 차등본이 있다. 함께 지우려면 {@code cascade} 를 붙여야 한다. */
        NEEDS_CASCADE
    }

    /**
     * @param dependents 이 백업을 기준으로 삼는 차등 백업들
     * @param cascade    딸린 차등본까지 함께 지우겠다고 했는지
     */
    public static Delete delete(BackupEntry entry, boolean pinned,
                                List<BackupEntry> dependents, boolean cascade) {
        if (entry.locked()) return Delete.LOCKED;
        if (pinned) return Delete.PINNED;
        if (!lockedAmong(dependents).isEmpty()) return Delete.LOCKED_DEPENDENTS;
        if (!dependents.isEmpty() && !cascade) return Delete.NEEDS_CASCADE;
        return Delete.OK;
    }

    /**
     * 잠긴 것만 골라낸다.
     *
     * <p>{@link #delete} 의 판단과 관리자에게 보여 줄 목록이 <b>같은 계산</b>이어야 한다.
     * 두 곳에서 따로 걸러 내면 "거부당했는데 목록이 비어 있는" 화면이 나올 수 있다.</p>
     */
    public static List<BackupEntry> lockedAmong(List<BackupEntry> entries) {
        List<BackupEntry> locked = new ArrayList<>();
        for (BackupEntry entry : entries) {
            if (entry.locked()) locked.add(entry);
        }
        return locked;
    }

    // ------------------------------------------------------------------
    // /wb lock

    /** 보호 상태를 바꿔도 되는지. */
    public enum Lock {
        OK,
        /**
         * 손상된 백업은 잠글 수 없다.
         *
         * <p>잠긴 것처럼 보여 놓고 정작 복원에 못 쓰면, 남겨 뒀다고 믿은 쪽이 더 위험하다.
         * 해제({@code /wb unlock})는 손상 여부와 무관하게 언제나 된다 - 막을 이유가 없고,
         * 막으면 잘못 잠근 것을 되돌릴 방법이 없어진다.</p>
         */
        BROKEN
    }

    /** @param locking 잠그려는 것이면 true, 해제하려는 것이면 false */
    public static Lock lock(BackupEntry entry, boolean locking) {
        if (locking && !entry.complete()) return Lock.BROKEN;
        return Lock.OK;
    }
}
