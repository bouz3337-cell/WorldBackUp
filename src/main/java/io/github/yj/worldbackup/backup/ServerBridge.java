package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.config.BackupSettings;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/**
 * {@link BackupService} 가 서버와 닿는 <b>모든</b> 지점.
 *
 * <p>백업 로직에서 가장 검증하고 싶은 것들 - 복원 실패 정지 중에 보관 정리를 멈추는가,
 * 압축이 끝난 백업을 뒷정리 실패로 지우지 않는가, 어떤 경로로 실패해도 자동 저장을 되돌리는가 -
 * 은 전부 {@code execute()} 안의 분기다. 그런데 그 메서드가 {@code Bukkit.getWorlds()} 나
 * {@code Bukkit.savePlayers()} 를 직접 부르는 한, 서버 인스턴스 없이는 한 줄도 돌려 볼 수 없다.
 * 그래서 지우고·되돌리는 판단은 {@code BackupService} 에 남기고, 서버를 실제로 건드리는
 * 동작만 이 인터페이스로 빼낸다.</p>
 *
 * <p>구현은 두 개뿐이다 - 실서버용 {@code PaperServerBridge} 와 테스트용 가짜.
 * 여기에 판단을 넣지 않는다. 여기가 두꺼워지면 검증할 수 없는 코드가 다시 늘어난다.</p>
 */
public interface ServerBridge {

    Logger logger();

    /** 지금 유효한 설정. {@code /wb reload} 로 갈아 끼워지므로 매번 물어본다. */
    BackupSettings settings();

    /** 지금 유효한 저장소. 설정과 같은 이유로 매번 물어본다. */
    BackupRepository repository();

    /** 복원 실패 기록이 남아 자동 작업이 멈춰 있는 상태인지. */
    boolean restoreFailureHold();

    boolean hasOnlinePlayers();

    /** 백업 메타데이터에 남길 서버 버전 문자열. */
    String serverVersion();

    /** 접속 중인 플레이어 데이터를 디스크로 내린다. */
    void savePlayers();

    /** 로드된 월드 전부. 대상 필터링은 호출자가 한다. */
    List<WorldHandle> worlds();

    /** 이름으로 월드를 찾는다. 이미 언로드됐으면 비어 있다. */
    Optional<WorldHandle> world(String name);

    void runAsync(Runnable task);

    /** 결과가 필요 없는 메인 스레드 작업. 서버가 종료 중이면 <b>조용히 포기한다.</b> */
    void runSyncQuietly(Runnable task);

    /** 메인 스레드에서 실행하고 결과를 기다린다. */
    <T> T callSync(Callable<T> callable, long timeoutSeconds) throws Exception;

    /** {@code permission} 이 null 이면 모두에게. */
    void broadcast(String message, String permission);

    /** 로드된 월드 하나. */
    interface WorldHandle {

        String name();

        /**
         * 서버가 알려 주는 월드 폴더 (절대·정규화).
         *
         * <p>버전에 따라 차원 폴더일 수 있다. 진짜 월드 뿌리를 찾는 것은
         * {@link WorldLayout#levelRoot(Path, Path)} 의 몫이다.</p>
         */
        Path folder();

        boolean autoSave();

        void autoSave(boolean value);

        /**
         * 청크 쓰기가 <b>디스크에 끝날 때까지 기다린다.</b> ({@code World#save(true)})
         *
         * <p>기다리지 않으면 서버의 I/O 스레드가 아직 쓰고 있는 region 파일을 압축하게 되어,
         * 헤더와 데이터가 어긋난 조각이 백업에 담긴다.</p>
         */
        void saveNow();
    }
}
