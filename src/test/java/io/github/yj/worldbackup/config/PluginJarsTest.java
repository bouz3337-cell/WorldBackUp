package io.github.yj.worldbackup.config;

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
 * 플러그인 jar 는 <b>담기되 되돌려지지 않는다.</b>
 *
 * <p>복원은 월드가 올라오기 전({@code onLoad})에 도는데, 그 시점에는 서버가 이미 모든 플러그인
 * jar 를 열어 둔 뒤다. 윈도우에서는 파일이 잠겨 실패하고, 리눅스에서는 성공하지만 이번 세션에는
 * 아무 효과가 없이 <b>다음 재시작에 조용히 옛 버전으로 돌아간다.</b> 그러면 사흘 전으로 되돌린
 * 서버가 사흘 전 버전의 플러그인으로 올라오는데, 그 사실은 아무 데도 적히지 않는다.</p>
 *
 * <p>그래서 이 경계는 설정으로 풀 수 있으면 안 된다. 여기서 못 박는다.</p>
 */
class PluginJarsTest {

    @TempDir
    Path tmp;

    /** 어떤 설정이든 복원은 jar 를 덮어쓰지 않는다. */
    @Test
    void restoreNeverOverwritesPluginJars() {
        for (String mode : List.of("all", "data", "none")) {
            assertTrue(settings(cfg -> cfg.set("targets.plugins", mode))
                            .preservePatterns().contains("plugins/*.jar"),
                    "plugins: " + mode + " 에서도 jar 는 그대로 두어야 한다");
        }
    }

    /** 관리자가 preserve 를 직접 적어도 그 규칙이 밀려나면 안 된다. */
    @Test
    void aCustomPreserveListStillKeepsTheJarRule() {
        List<String> preserve = settings(cfg -> cfg.set("restore.preserve", List.of("**/my.db")))
                .preservePatterns();

        assertTrue(preserve.contains("**/my.db"), "관리자가 적은 것은 그대로 살아야 한다");
        assertTrue(preserve.contains("plugins/*.jar"));
    }

    /**
     * {@code plugins/} <b>바로 아래</b>만 남긴다.
     *
     * <p>플러그인이 자기 폴더 안에 두는 라이브러리 jar 는 그 플러그인의 데이터다. 함께 담고
     * 함께 되돌려야 한다 - 그것까지 지키면 데이터만 옛 것으로 돌아가고 라이브러리는 그대로라
     * 짝이 맞지 않는다.</p>
     */
    @Test
    void onlyTheTopLevelJarsAreSpared() {
        GlobMatcher preserve = new GlobMatcher(settings(cfg -> {
        }).preservePatterns());

        assertTrue(preserve.matchesFile("plugins/Economy.jar"));
        assertFalse(preserve.matchesFile("plugins/Economy/libs/driver.jar"));
    }

    /** {@code plugins: data} 는 백업에서도 <b>같은 경계로</b> jar 를 뺀다. */
    @Test
    void dataModeExcludesTheSameJars() {
        GlobMatcher exclude = settings(cfg -> cfg.set("targets.plugins", "data")).exclude();

        assertTrue(exclude.matchesFile("plugins/Economy.jar"));
        assertFalse(exclude.matchesFile("plugins/Economy/libs/driver.jar"));
        assertFalse(exclude.matchesFile("plugins/Economy/balances.yml"));
    }

    /** {@code all} 과 {@code none} 은 백업에서 jar 를 빼지 않는다. */
    @Test
    void theOtherModesDoNotExcludeJars() {
        for (String mode : List.of("all", "none")) {
            assertFalse(settings(cfg -> cfg.set("targets.plugins", mode))
                            .exclude().matchesFile("plugins/Economy.jar"),
                    "plugins: " + mode + " 는 jar 제외 패턴을 만들지 않는다");
        }
    }

    /**
     * 알아듣지 못한 값은 담는 쪽으로 둔다.
     *
     * <p>오타 하나로 플러그인 데이터가 <b>조용히</b> 빠지는 것이 최악이다 - 되돌려야 하는
     * 날에야 드러난다. 반대로 예상보다 많이 담기는 것은 백업 크기로 바로 보인다.</p>
     */
    @Test
    void anUnknownValueFallsBackToBackingThemUp() {
        assertEquals(BackupSettings.Plugins.ALL,
                settings(cfg -> cfg.set("targets.plugins", "예")).plugins());
    }

    /**
     * <b>다른 플러그인이 실제로 백업에 담기는가.</b>
     *
     * <p>설정이 맞는 것과 아카이브에 들어가는 것은 다르다. 여기서는 제외 패턴을 실제 경로에
     * 대고 확인한다 - 관리자가 알고 싶은 것은 "plugins: all 이 무슨 뜻이냐" 가 아니라
     * "내 mopi 데이터가 백업에 있느냐" 다.</p>
     *
     * <p>이게 새면 월드만 되돌아오고 경제 잔고·보호구역·이벤트 기록은 지금 것이 남는다.
     * 월드와 어긋난 상태이고, 알아채는 것은 한참 뒤다.</p>
     */
    @Test
    void otherPluginsAreActuallyInsideTheBackup() {
        GlobMatcher exclude = settings(cfg -> {
        }).exclude();

        for (String path : List.of(
                "plugins/mopi.jar",
                "plugins/mopi/config.yml",
                "plugins/mopi/data/players.yml",
                "plugins/EventSystem.jar",
                "plugins/EventSystem/events.db",
                "plugins/WorldBackUp/config.yml")) {
            assertFalse(exclude.matchesFile(path), "백업에 담겨야 한다: " + path);
        }
        // 폴더 단위로도 걸리지 않아야 한다. 걸리면 그 아래를 아예 훑지 않는다.
        assertFalse(exclude.matchesDirectory("plugins"));
        assertFalse(exclude.matchesDirectory("plugins/mopi"));
        assertFalse(exclude.matchesDirectory("plugins/EventSystem/data"));
    }

    /** 반대로 <b>담기면 안 되는 것</b>은 그대로 빠진다. 넓히다가 이 경계가 새면 백업이 곱절이 된다. */
    @Test
    void thePluginsOwnArchivesStayOutEvenWhenPluginsAreBackedUp() {
        GlobMatcher exclude = settings(cfg -> {
        }).exclude();

        assertTrue(exclude.matchesDirectory("plugins/WorldBackUp/backups"));
        assertTrue(exclude.matchesDirectory("plugins/WorldBackUp/replaced"));
        assertTrue(exclude.matchesDirectory("OneBack"),
                "서버 한 벌짜리 아카이브를 평소 백업이 삼키면 백업이 곱절이 된다");
    }

    /** 폴더 이름을 적어 두지 않는다. {@code --plugins} 로 옮긴 서버에서도 맞아야 한다. */
    @Test
    void thePluginsFolderIsTheDataFoldersParent() {
        assertEquals(tmp.resolve("server/plugins").toAbsolutePath().normalize(),
                settings(cfg -> {
                }).pluginsDir());
    }

    /**
     * 백업 폴더 이름을 바꾼 뒤 <b>옛 백업을 복원해도</b> 지금 백업들이 지워지지 않는다.
     *
     * <p>복원이 지킬 목록은 두 곳에서 온다 - 지금 설정과, 그 백업을 만들 때 쓰인 제외 패턴.
     * 뒤쪽에는 <b>그때의</b> 폴더 이름만 적혀 있다. {@code plugins/} 가 복원 대상이 된 뒤로는
     * 그 차이가 곧 "복원 한 번에 지금 백업이 전멸" 이고, 되돌리기를 취소할 방법까지 함께
     * 사라진다.</p>
     */
    @Test
    void theCurrentArchiveFolderSurvivesARestoreOfAnOlderLayout() {
        List<String> preserve = settings(cfg -> cfg.set("backup.directory", "archive"))
                .preservePatterns();

        assertTrue(preserve.contains("plugins/WorldBackUp/archive"));
        assertTrue(preserve.contains("plugins/WorldBackUp/archive/**"));
    }

    /** 복원이 자기 진행 표식과 실패 표식을 스스로 치우지 않는다. */
    @Test
    void restoreDoesNotWipeItsOwnControlFiles() {
        List<String> preserve = settings(cfg -> {
        }).preservePatterns();

        assertTrue(preserve.contains("plugins/WorldBackUp/replaced"));
        assertTrue(preserve.stream().anyMatch(p -> p.contains("restore-failed-")));
    }

    // ------------------------------------------------------------------

    private BackupSettings settings(Consumer<YamlConfiguration> tweak) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("targets.exclude", List.of()); // 기본 제외 패턴과 섞이지 않게 비운다
        tweak.accept(cfg);
        return BackupSettings.load(cfg,
                tmp.resolve("server/plugins/WorldBackUp"), tmp.resolve("server"));
    }
}
