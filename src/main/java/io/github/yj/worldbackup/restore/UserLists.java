package io.github.yj.worldbackup.restore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 서버가 <b>플러그인보다 먼저</b> 읽어 메모리에 올려 두는 목록 파일들.
 *
 * <p>{@code ops.json} · {@code whitelist.json} 은 서버가 부팅 아주 초기에(플레이어 목록을
 * 만들면서) 읽는다. 복원은 {@code onLoad} 에서 도는데 그 시점은 그보다 <b>뒤</b>라, 파일만
 * 되돌려 놓으면 서버는 여전히 복원 전 목록을 들고 돈다. 되돌렸는데도 op 가 그대로였던 이유가
 * 이것이다.</p>
 *
 * <p>더 나쁜 것은 그다음이다. 서버는 {@code /op} 한 번에 <b>메모리를 파일로 덮어쓴다.</b>
 * 그대로 두면 방금 복원한 파일이 조용히 사라져, 다음 재시작에도 되돌아오지 않는다 -
 * 복원이 아예 없었던 것이 된다.</p>
 *
 * <p>밴 목록은 <b>일부러 여기 없다.</b> 되돌리지 않기 때문이다 - {@link #NEVER_RESTORED}.</p>
 *
 * <p>이 클래스는 파일을 읽고 무엇이 달라졌는지만 계산한다. 서버를 만지지 않으므로 경계를
 * 그대로 검증할 수 있다. 실제 반영은 {@link UserListSync} 가 한다.</p>
 */
public final class UserLists {

    /** 서버가 먼저 읽어 두는, 그래서 복원 뒤 다시 맞춰 줘야 하는 목록 파일들. */
    public static final String OPS = "ops.json";
    public static final String WHITELIST = "whitelist.json";

    /** 위 둘. {@link RestoreApplier} 가 "이 중 무엇을 실제로 되돌렸는지" 를 기록하는 데 쓴다. */
    public static final Set<String> TRACKED = Set.of(OPS, WHITELIST);

    /**
     * <b>복원이 절대 덮어쓰지 않는</b> 파일들. 밴 목록이다.
     *
     * <p>이 플러그인을 쓰는 가장 흔한 순간이 "테러범을 밴하고 그 전으로 되돌리는" 것이다.
     * 그런데 밴 목록까지 백업 시점으로 되돌리면 <b>방금 건 밴이 함께 풀린다.</b> 되돌리자마자
     * 같은 사람이 다시 들어올 수 있는 셈이라, 복원이 문제를 반쯤 되살리는 것과 같다.</p>
     *
     * <p>그래서 밴은 파일도 서버 메모리도 건드리지 않는다. 지금 밴된 사람은 복원 뒤에도
     * 그대로 밴이다. 백업에는 계속 담기므로(서버를 통째로 잃었을 때 쓸 수 있게) 필요하면
     * 아카이브에서 직접 꺼내면 된다.</p>
     *
     * <p>{@link RestoreService#restorePreserve} 가 이 목록을 복원 보존 패턴에 언제나 얹는다.</p>
     */
    public static final Set<String> NEVER_RESTORED = Set.of("banned-players.json", "banned-ips.json");

    /**
     * 되돌리기는 하지만 <b>이번 세션에는 반영할 방법이 없는</b> 파일들.
     *
     * <p>서버가 시작하면서 읽어 두는데 다시 읽히는 API 가 없다. 조용히 두면 관리자는 복원이
     * 전부 먹힌 줄 알게 되므로, 이름을 대며 "한 번 더 재시작해야 적용된다" 고 알린다.</p>
     */
    public static final Set<String> RESTART_ONLY = Set.of(
            "server.properties",
            "bukkit.yml",
            "spigot.yml",
            "permissions.yml",
            "config/paper-global.yml",
            "config/paper-world-defaults.yml");

    /**
     * 복원이 되돌렸다면 사람이 알아야 하는 파일 전부.
     *
     * <p>{@link RestoreApplier} 는 이 이름들만 따로 세어 둔다. 복원은 파일 수십만 개를
     * 다루므로 전부 기억할 수 없고, 기억해야 할 이유가 있는 것은 이 목록뿐이다 -
     * 하나는 지금 맞춰 줘야 하고({@link #TRACKED}), 하나는 맞춰 줄 수 없다고 알려야 한다
     * ({@link #RESTART_ONLY}).</p>
     */
    public static final Set<String> NOTABLE = union(TRACKED, RESTART_ONLY);

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> all = new LinkedHashSet<>(left);
        all.addAll(right);
        return Set.copyOf(all);
    }

    private UserLists() {
    }

    // ------------------------------------------------------------------
    // 파일에 적힌 것

    /** {@code ops.json} 한 줄. */
    public record Op(UUID uuid, String name, int level, boolean bypassesPlayerLimit) {
    }

    /** {@code whitelist.json} 한 줄. */
    public record Member(UUID uuid, String name) {
    }

    /**
     * {@code ops.json} 을 읽는다.
     *
     * <p>내용이 이상하면 <b>예외를 던진다.</b> 빈 목록으로 넘기면 호출자가 "op 가 아무도 없던
     * 백업" 으로 받아들여 <b>전원을 op 에서 내린다</b> - 파일 하나를 못 읽었다는 이유로 관리자가
     * 자기 서버에서 아무것도 못 하게 되는 셈이다. 못 읽은 것과 비어 있는 것은 반드시 구분한다.</p>
     */
    public static List<Op> readOps(Path file) throws IOException {
        List<Op> ops = new ArrayList<>();
        for (JsonObject entry : readArray(file)) {
            UUID uuid = uuid(entry, "uuid");
            if (uuid == null) continue; // 이름만 적힌 아주 옛 형식. UUID 없이는 맞춰 줄 수 없다.
            ops.add(new Op(uuid, string(entry, "name"),
                    integer(entry, "level"),
                    bool(entry, "bypassesPlayerLimit")));
        }
        return ops;
    }

    /** {@code whitelist.json} 을 읽는다. 규칙은 {@link #readOps} 와 같다. */
    public static List<Member> readWhitelist(Path file) throws IOException {
        List<Member> members = new ArrayList<>();
        for (JsonObject entry : readArray(file)) {
            UUID uuid = uuid(entry, "uuid");
            if (uuid == null) continue;
            members.add(new Member(uuid, string(entry, "name")));
        }
        return members;
    }


    // ------------------------------------------------------------------
    // 무엇이 달라졌는지

    /**
     * 지금 서버가 들고 있는 것과 파일에 적힌 것의 차이.
     *
     * @param add    파일에는 있는데 서버에는 없는 것 (넣어야 한다)
     * @param remove 서버에는 있는데 파일에는 없는 것 (빼야 한다)
     */
    public record Diff<T>(List<T> add, List<T> remove) {

        public boolean isEmpty() {
            return add.isEmpty() && remove.isEmpty();
        }
    }

    /**
     * {@code live} 를 {@code desired} 로 만들려면 무엇을 넣고 빼야 하는지.
     *
     * <p>결과 순서를 고정한다. 콘솔에 "누가 op 에서 내려갔는지" 를 그대로 적기 때문에, 돌릴
     * 때마다 순서가 바뀌면 두 복원의 기록을 견줄 수 없다.</p>
     */
    public static <T> Diff<T> diff(Set<T> live, Set<T> desired) {
        List<T> add = new ArrayList<>();
        for (T item : desired) {
            if (!live.contains(item)) add.add(item);
        }
        List<T> remove = new ArrayList<>();
        for (T item : live) {
            if (!desired.contains(item)) remove.add(item);
        }
        Comparator<T> byText = Comparator.comparing(String::valueOf);
        add.sort(byText);
        remove.sort(byText);
        return new Diff<>(List.copyOf(add), List.copyOf(remove));
    }

    // ------------------------------------------------------------------

    /**
     * JSON 배열 파일을 객체 목록으로 읽는다.
     *
     * <p>파일이 없으면 빈 목록이다 - 그때의 뜻은 "아무도 없다" 가 맞다. 반대로 <b>내용이
     * JSON 배열이 아니면 예외</b>다. 그 둘을 같이 취급하면 읽기 실패가 곧 "전원 해제" 가 된다.</p>
     */
    private static List<JsonObject> readArray(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        JsonElement root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader);
        } catch (RuntimeException e) { // JsonParseException 계열
            throw new IOException(file.getFileName() + " 을 읽지 못했습니다: " + e.getMessage(), e);
        }
        if (root == null || root.isJsonNull()) return List.of();
        if (!root.isJsonArray()) {
            throw new IOException(file.getFileName() + " 의 형식이 JSON 배열이 아닙니다.");
        }
        JsonArray array = root.getAsJsonArray();
        List<JsonObject> objects = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element != null && element.isJsonObject()) objects.add(element.getAsJsonObject());
        }
        return objects;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        return element.getAsString();
    }

    /** 없거나 숫자가 아니면 -1. 호출자는 이것을 "서버 기본값을 쓰라" 는 뜻으로 읽는다. */
    private static int integer(JsonObject object, String key) {
        String raw = string(object, key);
        if (raw == null) return -1;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return false;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static UUID uuid(JsonObject object, String key) {
        String raw = string(object, key);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
