package io.github.yj.worldbackup.config;

import io.github.yj.worldbackup.backup.Archiver;
import io.github.yj.worldbackup.restore.PendingRestore;
import io.github.yj.worldbackup.restore.RestoreApplier;
import io.github.yj.worldbackup.util.GlobMatcher;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 플러그인 자기 폴더에서 <b>무엇을 담고 무엇을 빼는지</b>.
 *
 * <p>한때는 데이터 폴더를 통째로 제외했다. 그러면 {@code config.yml} 까지 함께 빠져서, 서버를
 * 되돌려도 그 시점의 보관 정책은 되돌아오지 않았다 - 백업이 어떻게 만들어졌는지가 정작 백업
 * 안에 없는 셈이다. 지금은 "우리가 쓰는 상태" 만 뺀다.</p>
 *
 * <p>그래서 이 경계가 <b>양쪽으로</b> 위험하다. 넓으면 설정이 빠지고, 좁으면 복원 예약이나
 * 실패 표식이 백업에 담겼다가 복원으로 되살아난다. 그 둘을 여기서 못 박는다.</p>
 */
class OwnDataFolderExclusionTest {

    @TempDir
    Path tmp;

    /** 이것을 담지 않으면 복원이 보관 정책을 되돌리지 못한다. */
    @Test
    void theOwnConfigIsBackedUp() {
        assertFalse(exclude(cfg -> {
        }).matchesFile("plugins/WorldBackUp/config.yml"));
    }

    @Test
    void theOwnConfigPathIsResolvedInsideTheDataFolder() {
        BackupSettings settings = settings(cfg -> {
        });
        assertEquals(tmp.resolve("server/plugins/WorldBackUp/config.yml").toAbsolutePath().normalize(),
                settings.ownConfigFile());
    }

    /**
     * 아카이브와 {@code replaced/} 스냅샷은 절대 담기지 않는다.
     *
     * <p>둘 다 월드가 몇 벌씩 들어 있는 곳이다. 빠뜨리면 백업이 자기를 삼키며 눈덩이가 된다.</p>
     */
    @Test
    void archivesAndReplacedSnapshotsAreExcluded() {
        GlobMatcher exclude = exclude(cfg -> {
        });

        assertTrue(exclude.matchesDirectory("plugins/WorldBackUp/backups"));
        assertTrue(exclude.matchesFile("plugins/WorldBackUp/backups/wb-20260818-030000.zip"));
        assertTrue(exclude.matchesDirectory("plugins/WorldBackUp/replaced"));
        assertTrue(exclude.matchesFile("plugins/WorldBackUp/replaced/20260818-030000/world/level.dat"));
    }

    /**
     * 복원을 조종하는 파일들은 절대 담기지 않는다.
     *
     * <p>여기가 이 테스트의 핵심이다. 담기면 복원으로 <b>되살아나는데</b>, 되살아난 결과가
     * 파일마다 다르게 나쁘다.</p>
     * <ul>
     *   <li>{@code pending-restore.yml} - 복원 예약이다. 되살아나면 다음 부팅이 또 복원한다.</li>
     *   <li>{@code pending-restore.processing.yml} - 복원이 도는 <b>동안</b> 존재하는 파일이다.
     *       복원 대상에 들면 자기 진행 표식을 스스로 치우게 된다.</li>
     *   <li>{@code restore-failed-*.yml} - 이 파일이 있는 동안 자동 백업이 멈춘다. 옛 표식이
     *       되살아나면 아무도 손대지 않은 서버가 영구히 정지 상태로 들어간다.</li>
     * </ul>
     */
    @Test
    void restoreControlFilesAreExcluded() {
        GlobMatcher exclude = exclude(cfg -> {
        });

        assertTrue(exclude.matchesFile("plugins/WorldBackUp/" + PendingRestore.FILE_NAME));
        assertTrue(exclude.matchesFile("plugins/WorldBackUp/" + PendingRestore.PROCESSING_NAME));
        assertTrue(exclude.matchesFile("plugins/WorldBackUp/" + PendingRestore.REPORT_NAME));
        assertTrue(exclude.matchesFile(
                "plugins/WorldBackUp/" + RestoreApplier.FAILURE_PREFIX + "20260818-030000.yml"));
        // 해제된 표식도 같은 이유로 담지 않는다. 되살아나면 다시 표식이 된다.
        assertTrue(exclude.matchesFile(
                "plugins/WorldBackUp/" + RestoreApplier.FAILURE_PREFIX + "20260818-030000.yml.resolved"));
        assertTrue(exclude.matchesFile(
                "plugins/WorldBackUp/wb-20260818-030000.zip" + Archiver.TEMP_SUFFIX));
    }

    /**
     * 저장 위치를 옮겨도 <b>기본 위치</b>는 계속 제외된다.
     *
     * <p>{@code backup.directory} 를 바꾼 서버에는 옛 아카이브가 기본 위치에 그대로 남아 있다.
     * 그 폴더를 제외하는 근거를 "지금 설정된 위치" 하나에만 두면, 옮긴 순간 옛 아카이브 전체가
     * 백업 대상이 된다.</p>
     */
    @Test
    void theDefaultArchiveFolderStaysExcludedAfterMovingTheDirectory() {
        GlobMatcher exclude = exclude(cfg ->
                cfg.set("backup.directory", tmp.resolve("elsewhere").toString()));

        assertTrue(exclude.matchesFile("plugins/WorldBackUp/backups/wb-20260818-030000.zip"),
                "옮기기 전에 쌓인 아카이브가 백업 대상이 되어서는 안 된다");
    }

    /** 서버 폴더 안에 있는 새 저장 위치도 제외된다. (자기를 삼키지 않기 위한 원래 규칙) */
    @Test
    void aRelocatedArchiveFolderInsideTheServerIsExcludedToo() {
        GlobMatcher exclude = exclude(cfg -> cfg.set("backup.directory", "../../wb-archives"));

        assertTrue(exclude.matchesFile("wb-archives/wb-20260818-030000.zip"));
    }

    /** 자기 설정을 담기 싫으면 평범한 제외 패턴으로 뺄 수 있다. */
    @Test
    void anAdminCanExcludeTheOwnConfig() {
        GlobMatcher exclude = exclude(cfg ->
                cfg.set("targets.exclude", List.of("plugins/WorldBackUp/config.yml")));

        assertTrue(exclude.matchesFile("plugins/WorldBackUp/config.yml"));
    }

    /** 데이터 폴더가 서버 폴더 밖이면 애초에 백업되지 않으므로 아무 패턴도 필요 없다. */
    @Test
    void aDataFolderOutsideTheServerNeedsNoPatterns() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("targets.exclude", List.of());
        BackupSettings settings = BackupSettings.load(cfg,
                tmp.resolve("outside/WorldBackUp"), tmp.resolve("server"));

        assertTrue(settings.excludePatterns().isEmpty(),
                "서버 폴더 밖의 경로에 대해 패턴을 만들어 두면 엉뚱한 경로를 걸러 낼 수 있다");
    }

    // ------------------------------------------------------------------

    private GlobMatcher exclude(Consumer<YamlConfiguration> tweak) {
        return settings(tweak).exclude();
    }

    private BackupSettings settings(Consumer<YamlConfiguration> tweak) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("targets.exclude", List.of()); // 기본 제외 패턴과 섞이지 않게 비운다
        tweak.accept(cfg);
        return BackupSettings.load(cfg,
                tmp.resolve("server/plugins/WorldBackUp"), tmp.resolve("server"));
    }
}
