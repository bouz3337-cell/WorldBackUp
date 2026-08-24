package io.github.yj.worldbackup.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 복원이 되돌린 <b>op·화이트리스트 파일</b>을 읽고 무엇이 달라졌는지 정하는 부분.
 *
 * <p>이 판단이 그대로 {@code setOp} 으로 이어진다. 여기가 틀리면 복원이 사람을 op 에서
 * 내려 버리는데, 조용히 일어나고 게임 안에서는 되돌릴 수단이 없다(op 를 잃으면 명령을 쓸 수
 * 없다). 그래서 경계를 못 박아 둔다.</p>
 *
 * <p>밴은 여기 없다. 복원이 밴 목록을 아예 건드리지 않기 때문이고, 그 계약도 아래에서
 * 함께 못 박는다.</p>
 */
class UserListsTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path folder;

    // ------------------------------------------------------------------
    // ops.json

    @Test
    void opsAreReadWithTheirLevelAndLimitFlag() throws IOException {
        Path file = write("ops.json", """
                [
                  {"uuid": "11111111-1111-1111-1111-111111111111", "name": "Alice",
                   "level": 4, "bypassesPlayerLimit": true},
                  {"uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob",
                   "level": 2, "bypassesPlayerLimit": false}
                ]
                """);

        List<UserLists.Op> ops = UserLists.readOps(file);

        assertEquals(2, ops.size());
        assertEquals(new UserLists.Op(ALICE, "Alice", 4, true), ops.get(0));
        assertEquals(new UserLists.Op(BOB, "Bob", 2, false), ops.get(1));
    }

    /**
     * 파일을 <b>못 읽는 것</b>과 <b>비어 있는 것</b>은 절대 같지 않다.
     *
     * <p>같이 취급하면 읽기 실패가 곧 "이 백업 시점에는 op 가 아무도 없었다" 가 되고,
     * 호출자는 그대로 <b>전원을 op 에서 내린다.</b> 관리자가 자기 서버에서 아무 명령도 쓸 수
     * 없게 되는데, 원인은 JSON 한 글자다.</p>
     */
    @Test
    void aBrokenOpsFileIsAnErrorAndNeverAnEmptyList() throws IOException {
        Path file = write("ops.json", "[{\"uuid\": \"11111111-1111");

        assertThrows(IOException.class, () -> UserLists.readOps(file));
    }

    /** JSON 이긴 한데 배열이 아닌 경우도 마찬가지다. */
    @Test
    void aFileThatIsNotAJsonArrayIsAnError() throws IOException {
        Path file = write("ops.json", "{\"uuid\": \"11111111-1111-1111-1111-111111111111\"}");

        assertThrows(IOException.class, () -> UserLists.readOps(file));
    }

    /** 반대로 <b>진짜로</b> 비어 있으면 비어 있는 것이다. op 가 없던 시점의 백업은 있을 수 있다. */
    @Test
    void anEmptyListMeansNobodyWasOp() throws IOException {
        assertTrue(UserLists.readOps(write("ops.json", "[]")).isEmpty());
    }

    /** 파일 자체가 없어도 뜻은 "아무도 없다" 다. */
    @Test
    void aMissingFileMeansNobodyWasOp() throws IOException {
        assertTrue(UserLists.readOps(folder.resolve("ops.json")).isEmpty());
    }

    /**
     * UUID 가 없는 항목은 건너뛴다.
     *
     * <p>이름만 적혀 있던 아주 옛 형식이다. 서버는 UUID 로만 사람을 가리므로 맞춰 줄 방법이
     * 없는데, 이름으로 UUID 를 알아내려 하면 서버가 켜지는 중에 모장 API 를 물어보게 된다.</p>
     */
    @Test
    void entriesWithoutAUuidAreSkippedInsteadOfGuessed() throws IOException {
        Path file = write("ops.json", """
                [
                  {"name": "Legacy", "level": 4},
                  {"uuid": "11111111-1111-1111-1111-111111111111", "name": "Alice", "level": 4}
                ]
                """);

        List<UserLists.Op> ops = UserLists.readOps(file);

        assertEquals(1, ops.size());
        assertEquals(ALICE, ops.get(0).uuid());
    }

    /** {@code level} 이 없으면 -1 - "서버 기본값을 쓰라" 는 뜻이다. */
    @Test
    void aMissingLevelFallsBackToTheServerDefault() throws IOException {
        Path file = write("ops.json",
                "[{\"uuid\": \"11111111-1111-1111-1111-111111111111\", \"name\": \"Alice\"}]");

        assertEquals(-1, UserLists.readOps(file).get(0).level());
    }

    // ------------------------------------------------------------------
    // whitelist.json

    @Test
    void theWhitelistIsReadByUuid() throws IOException {
        Path file = write("whitelist.json", """
                [{"uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob"}]
                """);

        assertEquals(List.of(new UserLists.Member(BOB, "Bob")), UserLists.readWhitelist(file));
    }

    // ------------------------------------------------------------------
    // 밴 - 되돌리지 않는다

    /**
     * 밴 목록은 <b>맞춰 주는 대상이 아니다.</b>
     *
     * <p>이 플러그인을 쓰는 가장 흔한 순간이 "테러범을 밴하고 그 전으로 되돌리는" 것이다.
     * 밴까지 되돌리면 방금 건 밴이 함께 풀려, 되돌리자마자 같은 사람이 다시 들어온다.
     * 그래서 밴 파일은 복원이 아예 덮어쓰지 않는다 - 두 목록에 이름이 겹치면 안 된다.</p>
     */
    @Test
    void banListsAreNeverPartOfWhatWeSyncOrRestore() {
        for (String name : UserLists.NEVER_RESTORED) {
            assertFalse(UserLists.TRACKED.contains(name), name + " 은 서버에 맞춰 넣는 대상이 아니다");
            assertFalse(UserLists.NOTABLE.contains(name), name + " 은 복원 대상이 아니다");
        }
        assertTrue(UserLists.NEVER_RESTORED.contains("banned-players.json"));
        assertTrue(UserLists.NEVER_RESTORED.contains("banned-ips.json"));
    }

    // ------------------------------------------------------------------
    // 차이

    @Test
    void theDiffSaysWhatToAddAndWhatToRemove() {
        UserLists.Diff<String> diff = UserLists.diff(ordered("a", "b"), ordered("b", "c"));

        assertEquals(List.of("c"), diff.add());
        assertEquals(List.of("a"), diff.remove());
    }

    /** 같으면 아무것도 하지 않는다 - 그래야 복원한 파일을 서버가 다시 쓰지 않고 그대로 둔다. */
    @Test
    void nothingToDoWhenTheServerAlreadyMatchesTheFile() {
        assertTrue(UserLists.diff(ordered("a", "b"), ordered("b", "a")).isEmpty());
    }

    /** 콘솔에 그대로 적히므로 순서가 고정되어야 두 복원의 기록을 견줄 수 있다. */
    @Test
    void theOrderIsStableSoTheConsoleLogCanBeCompared() {
        UserLists.Diff<String> diff = UserLists.diff(ordered("z", "y"), ordered("c", "a", "b"));

        assertEquals(List.of("a", "b", "c"), diff.add());
        assertEquals(List.of("y", "z"), diff.remove());
    }

    // ------------------------------------------------------------------
    // 목록 자체

    /**
     * {@link RestoreApplier} 는 {@link UserLists#NOTABLE} 로만 무엇을 되돌렸는지 센다.
     *
     * <p>여기서 하나가 빠지면 그 파일은 복원돼도 아무도 모르는 채 지나간다 - 정확히 지금까지
     * {@code ops.json} 에 일어나던 일이다.</p>
     */
    @Test
    void everyFileWeCareAboutIsCounted() {
        assertTrue(UserLists.NOTABLE.containsAll(UserLists.TRACKED));
        assertTrue(UserLists.NOTABLE.containsAll(UserLists.RESTART_ONLY));
        assertEquals(UserLists.TRACKED.size() + UserLists.RESTART_ONLY.size(),
                UserLists.NOTABLE.size(), "두 목록은 겹치면 안 된다 - 맞춰 줄 수 있거나 없거나 둘 중 하나다");
    }

    /** 백업에 담기는 서버 파일 이름과 어긋나면 아무것도 걸리지 않는다. */
    @Test
    void theTrackedNamesMatchTheOnesTheBackupStores() throws IOException {
        String config = Files.readString(Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8);
        for (String name : UserLists.TRACKED) {
            assertTrue(config.contains("\"" + name + "\""),
                    name + " 이 config.yml 의 server-files 에 없다. 백업에 담기지 않으면 복원할 것도 없다");
        }
    }

    // ------------------------------------------------------------------

    private Path write(String name, String content) throws IOException {
        Path file = folder.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Set<String> ordered(String... items) {
        return new LinkedHashSet<>(List.of(items));
    }
}
