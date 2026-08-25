package io.github.yj.worldbackup.restore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 되돌린 op·화이트리스트가 <b>살아 있는 서버에까지</b> 반영되는지.
 *
 * <p>이 플러그인을 고치게 만든 사고가 정확히 여기였다 - 복원이 {@code ops.json} 을 되돌려
 * 놓았는데 <b>op 목록은 그대로였다.</b> 서버가 부팅 초기에 그 파일을 읽어 메모리에 올려 두고,
 * 복원은 그보다 뒤에 돌기 때문이다. 파일만 맞추는 것으로는 부족하다.</p>
 *
 * <p>여기서 쓰는 {@link FakeRoster} 는 <b>서버가 파일을 되쓰는 것까지</b> 흉내 낸다. 진짜
 * 서버는 op 를 하나 넣고 뺄 때마다 메모리를 {@code ops.json} 으로 다시 쓰는데, 그 메모리에는
 * 이름도 {@code level} 도 온전히 없다. 흉내를 여기까지 내지 않으면 {@code snapshot}/
 * {@code rewrite} 한 쌍이 왜 있는지 검증되지 않는다.</p>
 */
class UserListSyncTest {

    private static final UUID ADMIN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GRIEFER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FRIEND = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @TempDir
    Path serverRoot;

    private List<String> logged;
    private Logger log;

    @BeforeEach
    void setUp() {
        logged = new ArrayList<>();
        log = collectingLogger(logged);
    }

    // ------------------------------------------------------------------
    // 사고 그 자체

    /**
     * 관리자가 op 를 잃고 테러범이 op 를 얻은 상태에서 되돌린다.
     *
     * <p>이것이 처음에 보고된 증상이다. 파일은 되돌아갔는데 서버가 들고 있는 목록은 그대로라,
     * 관리자는 여전히 아무것도 못 하고 테러범은 여전히 op 였다.</p>
     */
    @Test
    void theAdminGetsOpBackAndTheGrieferLosesIt() throws IOException {
        writeOps(op(ADMIN, "Admin", 4)); // 백업 시점: 관리자만 op
        FakeRoster roster = new FakeRoster(serverRoot).withOps(GRIEFER); // 지금: 테러범만 op
        roster.name(GRIEFER, "Griefer");

        UserListSync.apply(Set.of(UserLists.OPS), serverRoot, log, roster);

        assertEquals(Set.of(ADMIN), roster.ops, "재시작을 기다리지 않고 이번 세션부터 맞아야 한다");
        assertTrue(logged.stream().anyMatch(line -> line.contains("op 부여")));
        assertTrue(logged.stream().anyMatch(line -> line.contains("op 해제")));
    }

    /**
     * 맞추고 난 뒤 파일이 <b>백업 시점 그대로</b>여야 한다.
     *
     * <p>서버는 op 를 고칠 때마다 메모리를 파일로 쏟아낸다. 그 메모리에는 이름이 없고
     * {@code level} 은 기본값으로 통일된다. 그대로 두면 되돌린 파일이 맞추는 과정에서 망가진다 -
     * 목록은 맞는데 내용이 깎인다.</p>
     */
    @Test
    void theFileKeepsItsOriginalNameAndLevel() throws IOException {
        writeOps(op(ADMIN, "Admin", 2)); // level 2 - 서버 기본값(4)과 다르게 둔다
        FakeRoster roster = new FakeRoster(serverRoot).withOps(GRIEFER);

        UserListSync.apply(Set.of(UserLists.OPS), serverRoot, log, roster);

        assertTrue(roster.rewroteFile, "서버가 파일을 덮어쓰는 상황을 실제로 거쳐야 하는 시험이다");

        List<UserLists.Op> onDisk = UserLists.readOps(serverRoot.resolve(UserLists.OPS));
        assertEquals(1, onDisk.size());
        assertEquals("Admin", onDisk.get(0).name(), "서버가 덮어쓴 빈 이름이 남으면 안 된다");
        assertEquals(2, onDisk.get(0).level(), "level 이 기본값으로 뭉개지면 권한이 달라진다");
    }

    /** 이미 같으면 아무것도 하지 않는다. 멀쩡한 파일을 괜히 다시 쓸 이유가 없다. */
    @Test
    void nothingHappensWhenTheListAlreadyMatches() throws IOException {
        writeOps(op(ADMIN, "Admin", 4));
        FakeRoster roster = new FakeRoster(serverRoot).withOps(ADMIN);

        UserListSync.apply(Set.of(UserLists.OPS), serverRoot, log, roster);

        assertEquals(Set.of(ADMIN), roster.ops);
        assertFalse(roster.rewroteFile, "건드릴 것이 없으면 파일도 그대로 둔다");
    }

    // ------------------------------------------------------------------
    // 빈 파일과 깨진 파일은 다르다

    /**
     * 읽지 못하는 {@code ops.json} 이면 <b>아무도 건드리지 않는다.</b>
     *
     * <p>이 구분이 이 테스트에서 제일 중요하다. 깨진 파일을 "op 가 아무도 없음" 으로 읽으면
     * 파일 하나 깨졌다는 이유로 <b>서버 전원이 op 를 잃는다.</b> 그것도 복원 직후, 손쓸 사람이
     * 가장 필요한 순간에.</p>
     */
    @Test
    void abrokenFileLeavesEveryoneAlone() throws IOException {
        Files.writeString(serverRoot.resolve(UserLists.OPS), "{ 이건 JSON 이 아니다", StandardCharsets.UTF_8);
        FakeRoster roster = new FakeRoster(serverRoot).withOps(ADMIN, FRIEND);

        UserListSync.apply(Set.of(UserLists.OPS), serverRoot, log, roster);

        assertEquals(Set.of(ADMIN, FRIEND), roster.ops, "깨진 파일 하나로 전원이 op 를 잃으면 안 된다");
        assertTrue(logged.stream().anyMatch(line -> line.contains("그대로 둡니다")),
                "조용히 넘어가면 관리자는 맞춰진 줄 안다");
    }

    /**
     * 반대로 <b>비어 있는</b> {@code ops.json} 은 "그때는 op 가 없었다" 는 뜻이다. 그대로 맞춘다.
     *
     * <p>깨진 파일과 결과가 정반대라, 둘을 같이 못 박아 둔다.</p>
     */
    @Test
    void anEmptyListMeansNobodyWasOpAndIsHonoured() throws IOException {
        Files.writeString(serverRoot.resolve(UserLists.OPS), "[]", StandardCharsets.UTF_8);
        FakeRoster roster = new FakeRoster(serverRoot).withOps(GRIEFER);

        UserListSync.apply(Set.of(UserLists.OPS), serverRoot, log, roster);

        assertTrue(roster.ops.isEmpty());
        assertTrue(logged.stream().anyMatch(line -> line.contains("콘솔에서 op")),
                "게임 안에서 되돌릴 수 없는 상태이니 나가는 길을 알려 줘야 한다");
    }

    // ------------------------------------------------------------------
    // 화이트리스트

    @Test
    void theWhitelistIsSyncedTheSameWay() throws IOException {
        writeWhitelist(member(ADMIN, "Admin"), member(FRIEND, "Friend"));
        FakeRoster roster = new FakeRoster(serverRoot).withWhitelist(GRIEFER);

        UserListSync.apply(Set.of(UserLists.WHITELIST), serverRoot, log, roster);

        assertEquals(Set.of(ADMIN, FRIEND), roster.whitelist);
        assertTrue(roster.reloadAttempted, "서버가 스스로 다시 읽게 해 보는 것이 먼저다");
    }

    /** 서버가 스스로 다시 읽어 이미 맞다면 더 손대지 않는다. */
    @Test
    void aServerThatRereadsTheFileItselfNeedsNoFurtherWork() throws IOException {
        writeWhitelist(member(ADMIN, "Admin"));
        FakeRoster roster = new FakeRoster(serverRoot).withWhitelist(GRIEFER);
        roster.reloadAppliesFile = true; // reloadWhitelist 가 먹히는 서버

        UserListSync.apply(Set.of(UserLists.WHITELIST), serverRoot, log, roster);

        assertEquals(Set.of(ADMIN), roster.whitelist);
        assertFalse(roster.rewroteFile, "서버가 알아서 읽었으면 되쓸 것도 없다");
    }

    // ------------------------------------------------------------------
    // 밴

    /**
     * 밴 목록은 이 클래스가 아예 모른다.
     *
     * <p>되돌린 뒤에도 테러범은 밴인 채로 남아야 한다. 밴까지 되돌리면 쫓아낸 사람이 다시
     * 들어온다 - 복원의 목적을 스스로 무너뜨린다.</p>
     */
    @Test
    void bansAreNotTouchedEvenIfHandedIn() throws IOException {
        writeOps(op(ADMIN, "Admin", 4));
        FakeRoster roster = new FakeRoster(serverRoot).withOps(ADMIN);

        UserListSync.apply(UserLists.NEVER_RESTORED, serverRoot, log, roster);

        assertTrue(logged.isEmpty(), "밴 이름만 넘어오면 할 일이 없다");
        assertEquals(Set.of(ADMIN), roster.ops);
    }

    // ------------------------------------------------------------------
    // 실패해도 서버는 뜬다

    /**
     * op 를 맞추다 실패해도 화이트리스트는 맞춘다.
     *
     * <p>여기서 예외가 밖으로 나가면 {@code onEnable} 이 통째로 실패해 <b>백업 플러그인이 꺼진
     * 서버</b>가 된다. 복원 직후는 되돌릴 수단이 가장 필요한 순간이다.</p>
     */
    @Test
    void oneListFailingDoesNotStopTheOther() throws IOException {
        writeOps(op(ADMIN, "Admin", 4));
        writeWhitelist(member(FRIEND, "Friend"));
        FakeRoster roster = new FakeRoster(serverRoot).withOps(GRIEFER).withWhitelist(GRIEFER);
        roster.failOnSetOp = true;

        UserListSync.apply(Set.of(UserLists.OPS, UserLists.WHITELIST), serverRoot, log, roster);

        assertEquals(Set.of(FRIEND), roster.whitelist, "op 가 넘어져도 화이트리스트는 맞아야 한다");
        assertTrue(logged.stream().anyMatch(line -> line.contains("재시작하면 적용됩니다")),
                "실패했으면 남은 방법을 알려 줘야 한다");
    }

    /**
     * 재시작해야 먹는 파일은 되돌려 놓고 <b>말해 준다.</b>
     *
     * <p>조용히 두면 관리자는 복원이 전부 먹힌 줄 안다. {@code server.properties} 의 난이도나
     * 화이트리스트 여부가 예전 그대로인 것을 나중에, 대개 또 다른 사고로 알게 된다.</p>
     */
    @Test
    void filesThatNeedARestartAreReported() {
        FakeRoster roster = new FakeRoster(serverRoot);

        UserListSync.apply(Set.of("server.properties"), serverRoot, log, roster);

        assertTrue(logged.stream().anyMatch(line -> line.contains("server.properties")));
        assertTrue(logged.stream().anyMatch(line -> line.contains("재시작")));
    }

    // ------------------------------------------------------------------
    // 가짜 서버

    /**
     * 명단을 들고 있는 가짜 서버.
     *
     * <p>진짜 서버처럼 <b>고칠 때마다 파일을 덮어쓴다.</b> 그때 이름은 비우고 {@code level} 은
     * 기본값 4 로 통일한다 - 메모리에 그만큼밖에 없기 때문이다.</p>
     */
    private static final class FakeRoster implements UserListSync.Roster {

        private final Path serverRoot;
        final Set<UUID> ops = new LinkedHashSet<>();
        final Set<UUID> whitelist = new LinkedHashSet<>();
        private final Map<UUID, String> names = new HashMap<>();

        boolean reloadAttempted;
        boolean reloadAppliesFile;
        boolean rewroteFile;
        boolean failOnSetOp;

        FakeRoster(Path serverRoot) {
            this.serverRoot = serverRoot;
        }

        FakeRoster withOps(UUID... uuids) {
            ops.addAll(List.of(uuids));
            return this;
        }

        FakeRoster withWhitelist(UUID... uuids) {
            whitelist.addAll(List.of(uuids));
            return this;
        }

        void name(UUID uuid, String name) {
            names.put(uuid, name);
        }

        @Override
        public Set<UUID> operators() {
            return new LinkedHashSet<>(ops);
        }

        @Override
        public Set<UUID> whitelisted() {
            return new LinkedHashSet<>(whitelist);
        }

        @Override
        public void setOp(UUID uuid, boolean value) {
            if (failOnSetOp) throw new IllegalStateException("op 를 고치지 못했다");
            if (value) ops.add(uuid); else ops.remove(uuid);
            dump(UserLists.OPS, ops, true);
        }

        @Override
        public void setWhitelisted(UUID uuid, boolean value) {
            if (value) whitelist.add(uuid); else whitelist.remove(uuid);
            dump(UserLists.WHITELIST, whitelist, false);
        }

        @Override
        public void reloadWhitelist() {
            reloadAttempted = true;
            if (!reloadAppliesFile) return;
            try {
                whitelist.clear();
                for (UserLists.Member member : UserLists.readWhitelist(serverRoot.resolve(UserLists.WHITELIST))) {
                    whitelist.add(member.uuid());
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String nameOf(UUID uuid) {
            return names.get(uuid);
        }

        /** 서버가 메모리를 파일로 쏟아내는 그 동작. 이름과 level 이 깎여 나간다. */
        private void dump(String fileName, Set<UUID> members, boolean withLevel) {
            rewroteFile = true;
            StringBuilder json = new StringBuilder("[\n");
            int index = 0;
            for (UUID uuid : members) {
                if (index++ > 0) json.append(",\n");
                json.append("  {\"uuid\": \"").append(uuid).append("\", \"name\": \"\"");
                if (withLevel) json.append(", \"level\": 4, \"bypassesPlayerLimit\": false");
                json.append('}');
            }
            json.append("\n]\n");
            try {
                Files.writeString(serverRoot.resolve(fileName), json.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 거들기

    private void writeOps(String... entries) throws IOException {
        Files.writeString(serverRoot.resolve(UserLists.OPS),
                "[\n" + String.join(",\n", entries) + "\n]\n", StandardCharsets.UTF_8);
    }

    private void writeWhitelist(String... entries) throws IOException {
        Files.writeString(serverRoot.resolve(UserLists.WHITELIST),
                "[\n" + String.join(",\n", entries) + "\n]\n", StandardCharsets.UTF_8);
    }

    private static String op(UUID uuid, String name, int level) {
        return "  {\"uuid\": \"" + uuid + "\", \"name\": \"" + name + "\", \"level\": " + level
                + ", \"bypassesPlayerLimit\": false}";
    }

    private static String member(UUID uuid, String name) {
        return "  {\"uuid\": \"" + uuid + "\", \"name\": \"" + name + "\"}";
    }

    private static Logger collectingLogger(List<String> sink) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                sink.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }
}
