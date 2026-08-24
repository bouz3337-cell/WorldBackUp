package io.github.yj.worldbackup.restore;

import io.github.yj.worldbackup.util.GlobMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 복원을 <b>확정하기 전에</b> 내리는 판단들.
 *
 * <p>{@link RestoreApplier} 는 파일을 실제로 옮기는 쪽이라 테스트가 두텁지만, 그 앞에서
 * "무엇을 대상으로 삼을지 · 무엇을 그대로 둘지 · 시작해도 되는지" 를 정하는 쪽은 서버가 있어야
 * 돌아가는 코드에 묻혀 있었다. 그 판단만 떼어 내 경계를 못 박는다.</p>
 *
 * <p>여기가 틀리면 복원이 <b>요청하지 않은 파일을 덮어쓰거나</b>, 되돌릴 수 있었던 복원을
 * 막는다. 둘 다 조용히 일어난다.</p>
 */
class RestoreDecisionTest {

    private static final List<String> WORLDS = List.of("world", "world_nether");

    /** 백업 하나에 흔히 들어 있는 최상위 경로들. */
    private static final List<String> ROOTS = List.of(
            "world",
            "world_nether",
            "server.properties",
            "ops.json",
            "plugins/LuckPerms",
            "plugins/WorldBackUp/config.yml");

    // ------------------------------------------------------------------
    // /wb restore [ID] worlds

    /**
     * {@code worlds} 는 월드 폴더만 고른다.
     *
     * <p>이게 새면 "월드만" 을 요청한 관리자가 경고 한 줄 없이 {@code server.properties} 와
     * {@code ops.json} 까지 백업 시점으로 되돌리게 된다.</p>
     */
    @Test
    void worldsOnlyPicksNothingButWorlds() {
        assertEquals(List.of("world", "world_nether"),
                RestoreService.selectedRoots(ROOTS, WORLDS, true));
    }

    /** 플러그인 자기 설정도 월드가 아니다. (백업 대상에 들어가므로 명시적으로 확인한다) */
    @Test
    void worldsOnlyLeavesThePluginsOwnConfigAlone() {
        assertFalse(RestoreService.selectedRoots(ROOTS, WORLDS, true)
                .contains("plugins/WorldBackUp/config.yml"));
    }

    /** {@code world-container} 나 {@code --universe} 로 월드를 옮겨 둔 서버. */
    @Test
    void worldsOnlyFindsWorldsUnderAContainerFolder() {
        List<String> roots = List.of("universe/world", "universe/world_nether", "server.properties");

        assertEquals(List.of("universe/world", "universe/world_nether"),
                RestoreService.selectedRoots(roots, WORLDS, true));
    }

    /**
     * 이름이 겹쳐 보이는 경로를 월드로 착각하지 않는다.
     *
     * <p>{@code endsWith("/" + 월드이름)} 이라 꼬리가 우연히 같은 것("myworld")은 걸리지 않아야
     * 한다. 걸리면 월드가 아닌 폴더를 비우고 그 자리에 월드를 풀게 된다.</p>
     */
    @Test
    void worldsOnlyDoesNotMatchPathsThatMerelyEndWithSimilarNames() {
        List<String> roots = List.of("plugins/myworld", "worldedit", "world");

        assertEquals(List.of("world"), RestoreService.selectedRoots(roots, List.of("world"), true));
    }

    /**
     * 월드를 하나도 못 찾으면 <b>빈 목록</b>이다.
     *
     * <p>예전에는 전체 경로로 되돌아갔다. 호출자가 이 경우를 거부하는 데 이 계약이 필요하다 -
     * 여기서 전체를 돌려주면 "월드만" 요청이 서버 설정까지 덮어쓰는 복원으로 바뀐다.</p>
     */
    @Test
    void worldsOnlyReturnsNothingWhenTheArchiveKnowsNoWorlds() {
        assertTrue(RestoreService.selectedRoots(ROOTS, List.of(), true).isEmpty());
    }

    /** 옵션을 쓰지 않았으면 담긴 그대로다. */
    @Test
    void aFullRestoreKeepsEveryRoot() {
        assertEquals(ROOTS, RestoreService.selectedRoots(ROOTS, WORLDS, false));
    }

    // ------------------------------------------------------------------
    // 그대로 둘 것

    /**
     * 백업에서 제외했던 것은 복원 때도 건드리지 않는다.
     *
     * <p>이 합침이 빠지면 복원이 백업에 <b>없는</b> 파일(로그·캐시·플러그인 자기 상태)을
     * 지우기만 한다. 특히 {@code replaced/} 가 여기 걸려 있어서, 빠지면 방금 밀어낸 옛 월드를
     * 복원 도중에 스스로 지운다.</p>
     */
    @Test
    void everythingExcludedFromTheBackupIsPreservedOnRestore() {
        List<String> preserve = RestoreService.restorePreserve(
                List.of("**/session.lock"),
                List.of("**/logs/**", "plugins/WorldBackUp/replaced/**"));

        assertTrue(preserve.containsAll(
                List.of("**/session.lock", "**/logs/**", "plugins/WorldBackUp/replaced/**")));
    }

    /** 같은 패턴이 양쪽에 있어도 한 번만 남는다. */
    @Test
    void duplicatePatternsAreNotRepeated() {
        List<String> preserve = RestoreService.restorePreserve(
                List.of("**/session.lock"),
                List.of("**/session.lock", "**/*.log"));

        assertEquals(1, preserve.stream().filter("**/session.lock"::equals).count());
    }

    /**
     * <b>밴 목록은 어떤 설정에서도 복원되지 않는다.</b>
     *
     * <p>이 플러그인을 쓰는 가장 흔한 순간이 "테러범을 밴하고 그 전으로 되돌리는" 것이다.
     * 밴 목록까지 되돌리면 방금 건 밴이 함께 풀려, 되돌리자마자 같은 사람이 다시 들어온다.
     * 복원이 사고를 반쯤 되살리는 셈이라, 설정과 무관하게 항상 보존 목록에 얹는다.</p>
     */
    @Test
    void banListsArePreservedNoMatterWhatTheConfigSays() {
        List<String> preserve = RestoreService.restorePreserve(List.of(), List.of());

        assertTrue(preserve.contains("banned-players.json"), "밴한 사람은 되돌린 뒤에도 밴이어야 한다");
        assertTrue(preserve.contains("banned-ips.json"));
    }

    /** 관리자가 같은 것을 이미 적어 두었어도 두 번 들어가지 않는다. */
    @Test
    void aBanListTheAdminAlreadyListedIsNotAddedTwice() {
        List<String> preserve = RestoreService.restorePreserve(
                List.of("banned-players.json"), List.of());

        assertEquals(1, preserve.stream().filter("banned-players.json"::equals).count());
    }

    /**
     * 보존 목록에 <b>이름을 넣는 것만으로는</b> 지켜지지 않는다.
     *
     * <p>복원은 그 이름을 {@link GlobMatcher} 에 넣어 판단한다. 패턴 문법과 실제 상대 경로가
     * 어긋나면 아무것도 걸리지 않는데, <b>조용히</b> 통과한다 - 밴 목록이 지켜지는 줄 알고
     * 되돌렸는데 밴이 풀려 있게 된다. 그래서 실제 매칭까지 확인한다.</p>
     */
    @Test
    void thePreservedBanListsActuallyMatchTheirRealPaths() {
        GlobMatcher preserve = new GlobMatcher(
                RestoreService.restorePreserve(List.of(), List.of()));

        assertTrue(preserve.matchesFile("banned-players.json"));
        assertTrue(preserve.matchesFile("banned-ips.json"));
        // 옆 파일까지 함께 지켜지면 안 된다 - op 는 되돌려야 한다
        assertFalse(preserve.matchesFile("ops.json"));
    }

    // ------------------------------------------------------------------
    // 확정 전 공간 점검

    /** 딱 맞는 것은 통과다. 경계에서 막으면 되돌릴 수 있는 복원을 막는 셈이다. */
    @Test
    void exactlyEnoughSpaceIsEnough() {
        assertTrue(RestoreService.hasRoomToConfirm(1_000L, 1_000L));
        assertFalse(RestoreService.hasRoomToConfirm(1_000L, 999L));
    }

    /**
     * 크기 기록이 없는 옛 백업은 막지 않는다.
     *
     * <p>알 수 없다는 이유로 되돌릴 방법을 없애는 것보다, 월드를 건드리기 전에 정확히 재는
     * {@code onLoad} 점검({@link RestoreApplier#hasRoomToRestore})에 맡기는 편이 낫다.</p>
     */
    @Test
    void anOldBackupWithoutASizeRecordIsNeverBlockedHere() {
        assertTrue(RestoreService.hasRoomToConfirm(0L, 0L));
        assertTrue(RestoreService.hasRoomToConfirm(-1L, 0L));
    }

    /** 빠듯하다는 경고는 "통과했지만 여유가 최소치보다 적을 때" 만 뜬다. */
    @Test
    void theTightWarningOnlyFiresWhenItPassedButBarely() {
        // 10 필요 · 15 남음 · 최소 여유 8 -> 남는 5 는 8 보다 적다
        assertTrue(RestoreService.isTightAfterRestore(10L, 15L, 8L));
        // 여유가 넉넉하면 조용하다
        assertFalse(RestoreService.isTightAfterRestore(10L, 100L, 8L));
        // 애초에 막힐 상황이면 경고가 아니라 거부다. 두 메시지가 겹치지 않게 한다.
        assertFalse(RestoreService.isTightAfterRestore(10L, 5L, 8L));
        // 기록이 없는 옛 백업은 판단 대상이 아니다
        assertFalse(RestoreService.isTightAfterRestore(0L, 5L, 8L));
    }
}
