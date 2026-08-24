package io.github.yj.worldbackup.restore;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 복원이 되돌린 op·화이트리스트를 <b>지금 돌고 있는 서버</b>에 반영한다.
 *
 * <p>왜 필요한지는 {@link UserLists} 에 적어 두었다. 요약하면 - 서버는 이 파일들을 부팅
 * 초기에 읽어 메모리에 올려 두고, 복원({@code onLoad})은 그보다 뒤에 돈다. 그래서 파일만
 * 되돌리면 <b>op 목록이 그대로였다.</b> 그리고 다음 {@code /op} 한 번에 메모리가 파일을
 * 덮어써서, 되돌려 놓은 파일마저 사라졌다.</p>
 *
 * <p>여기서 하는 일은 하나다 - 파일에 적힌 대로 <b>메모리를 맞춘다.</b> 넣고 빼는 데는 Bukkit
 * API 만 쓴다(NMS 없음). 그러면 이번 세션부터 바로 그 목록이 적용되고, 서버가 언제 파일을
 * 다시 쓰든 백업 시점 그대로가 유지된다.</p>
 *
 * <p><b>밴은 여기서 다루지 않는다.</b> 복원이 밴 목록을 아예 건드리지 않기 때문이다 -
 * {@link UserLists#NEVER_RESTORED}.</p>
 *
 * <p><b>반드시 메인 스레드에서</b>({@code onEnable}) 부른다.</p>
 */
public final class UserListSync {

    private UserListSync() {
    }

    /**
     * 방금 복원이 되돌린 파일들을 살아 있는 서버에 반영한다.
     *
     * @param restored   이번 복원이 실제로 되돌린 {@link UserLists#NOTABLE} 파일 이름들
     * @param serverRoot 서버 실행 폴더 ({@code ops.json} 이 있는 곳)
     */
    public static void apply(Set<String> restored, Path serverRoot, Logger log) {
        if (restored == null || restored.isEmpty()) return;

        if (restored.contains(UserLists.OPS)) {
            guard(log, UserLists.OPS, () -> syncOps(serverRoot, log));
        }
        if (restored.contains(UserLists.WHITELIST)) {
            guard(log, UserLists.WHITELIST, () -> syncWhitelist(serverRoot, log));
        }
        // 밴 목록은 여기 없다. 복원이 아예 건드리지 않기 때문이다 -
        // {@link UserLists#NEVER_RESTORED} 참고. 밴한 사람은 되돌린 뒤에도 밴인 채로 남는다.

        warnAboutRestartOnly(restored, log);
    }

    /**
     * 목록 하나가 실패해도 나머지는 맞춘다.
     *
     * <p>여기서 던져 나가면 {@code onEnable} 이 통째로 실패해 플러그인이 꺼진다 - 복원 직후,
     * 되돌릴 수단이 가장 필요한 순간에 백업 플러그인이 없는 서버가 된다.</p>
     */
    private static void guard(Logger log, String what, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            log.log(Level.SEVERE, "[복원] " + what + " 을 서버에 반영하지 못했습니다. "
                    + "파일은 되돌아가 있으니 서버를 한 번 더 재시작하면 적용됩니다.", t);
        }
    }

    // ------------------------------------------------------------------
    // op

    private static void syncOps(Path serverRoot, Logger log) {
        Path file = serverRoot.resolve(UserLists.OPS);
        List<UserLists.Op> wanted;
        try {
            wanted = UserLists.readOps(file);
        } catch (IOException e) {
            log.severe("[복원] " + UserLists.OPS + " 을 읽지 못해 op 목록을 그대로 둡니다: " + e.getMessage());
            return;
        }

        Map<UUID, OfflinePlayer> live = byUuid(Bukkit.getOperators());
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UserLists.Op op : wanted) names.put(op.uuid(), op.name());

        UserLists.Diff<UUID> diff = UserLists.diff(live.keySet(), names.keySet());
        if (diff.isEmpty()) {
            log.info("[복원] op 목록은 이미 백업 시점과 같습니다. (" + live.size() + "명)");
            return;
        }

        byte[] original = snapshot(file, log);
        try {
            for (UUID uuid : diff.add()) {
                Bukkit.getOfflinePlayer(uuid).setOp(true);
                log.warning("[복원] op 부여: " + label(names.get(uuid), uuid));
            }
            for (UUID uuid : diff.remove()) {
                OfflinePlayer player = live.get(uuid);
                player.setOp(false);
                log.warning("[복원] op 해제: " + label(player.getName(), uuid));
            }
        } finally {
            rewrite(file, original, log);
        }
        log.warning("[복원] op 목록을 백업 시점으로 맞췄습니다. (부여 " + diff.add().size()
                + "명, 해제 " + diff.remove().size() + "명)");
        if (names.isEmpty()) {
            log.warning("[복원] 이 백업 시점에는 op 가 아무도 없었습니다. "
                    + "게임 안에서 되돌릴 수 없으니 콘솔에서 op <이름> 을 쓰세요.");
        }
    }

    // ------------------------------------------------------------------
    // 화이트리스트

    private static void syncWhitelist(Path serverRoot, Logger log) {
        Path file = serverRoot.resolve(UserLists.WHITELIST);
        List<UserLists.Member> wanted;
        try {
            wanted = UserLists.readWhitelist(file);
        } catch (IOException e) {
            log.severe("[복원] " + UserLists.WHITELIST + " 을 읽지 못해 화이트리스트를 그대로 둡니다: "
                    + e.getMessage());
            return;
        }

        // 서버가 스스로 다시 읽게 해 본다. 이 길로 되면 이름까지 파일 그대로 남아
        // 아래 되쓰기가 필요 없어진다. (op 에는 이런 API 가 없다)
        try {
            Bukkit.reloadWhitelist();
        } catch (Throwable ignored) {
            // 구현이 없는 포크일 수 있다. 아래에서 어차피 직접 맞춘다.
        }

        Map<UUID, OfflinePlayer> live = byUuid(Bukkit.getWhitelistedPlayers());
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UserLists.Member member : wanted) names.put(member.uuid(), member.name());

        UserLists.Diff<UUID> diff = UserLists.diff(live.keySet(), names.keySet());
        if (diff.isEmpty()) {
            // 위 reloadWhitelist 가 먹혔거나, 애초에 달라진 것이 없었다. 어느 쪽이든 지금
            // 서버가 들고 있는 것이 백업 시점 그대로다.
            log.info("[복원] 화이트리스트를 백업 시점으로 맞췄습니다. (" + live.size() + "명)");
            return;
        }

        byte[] original = snapshot(file, log);
        try {
            for (UUID uuid : diff.add()) {
                Bukkit.getOfflinePlayer(uuid).setWhitelisted(true);
            }
            for (UUID uuid : diff.remove()) {
                live.get(uuid).setWhitelisted(false);
            }
        } finally {
            rewrite(file, original, log);
        }
        log.warning("[복원] 화이트리스트를 백업 시점으로 맞췄습니다. (추가 " + diff.add().size()
                + "명, 제외 " + diff.remove().size() + "명)");
    }

    // ------------------------------------------------------------------

    /**
     * 되돌려 놓았지만 이번 세션에는 적용할 수 없는 파일들을 알린다.
     *
     * <p>조용히 두면 관리자는 복원이 전부 먹힌 줄 안다. {@code server.properties} 의
     * 화이트리스트 여부나 난이도가 예전 그대로인 것을 나중에, 대개 사고로 알게 된다.</p>
     */
    private static void warnAboutRestartOnly(Set<String> restored, Logger log) {
        List<String> pending = new ArrayList<>();
        for (String name : restored) {
            if (UserLists.RESTART_ONLY.contains(name)) pending.add(name);
        }
        if (pending.isEmpty()) return;
        pending.sort(null);

        log.warning("[복원] 아래 파일은 되돌려 놓았지만 서버가 시작할 때 이미 읽어 둔 것이라, "
                + "이번 세션에는 예전 값으로 돕니다:");
        log.warning("[복원]   " + String.join(", ", pending));
        log.warning("[복원] 그 값까지 적용하려면 서버를 한 번 더 재시작하세요. "
                + "(복원 자체는 끝났으므로 그냥 껐다 켜면 됩니다)");
    }

    /**
     * API 로 목록을 고치기 <b>전에</b> 파일 내용을 붙들어 둔다.
     *
     * <p>서버는 op 를 하나 넣고 뺄 때마다 <b>메모리를 파일로 다시 쓴다.</b> 그 메모리에는
     * 백업이 갖고 있던 부가 정보 - 이름, {@code level}, {@code bypassesPlayerLimit} - 가
     * 온전히 들어 있지 않다. 접속한 적 없는 UUID 는 이름이 빈 문자열이 되고,
     * {@code level} 은 {@code op-permission-level} 로 통일된다. 다 맞춘 뒤 이 내용을 그대로
     * 되쓰면 파일은 백업 시점 그대로 남고, 메모리에는 "누가 op 인지" 가 정확히 들어간다.</p>
     */
    private static byte[] snapshot(Path file, Logger log) {
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            log.warning("[복원] " + file.getFileName() + " 을 붙들어 두지 못했습니다: " + e.getMessage());
            return null;
        }
    }

    private static void rewrite(Path file, byte[] original, Logger log) {
        if (original == null) return;
        try {
            Files.write(file, original);
        } catch (IOException e) {
            log.warning("[복원] " + file.getFileName() + " 을 백업 시점 내용으로 되돌리지 못했습니다: "
                    + e.getMessage() + " (서버가 들고 있는 목록은 이미 맞춰졌습니다)");
        }
    }

    private static Map<UUID, OfflinePlayer> byUuid(Collection<OfflinePlayer> players) {
        Map<UUID, OfflinePlayer> map = new LinkedHashMap<>();
        for (OfflinePlayer player : players) {
            if (player != null && player.getUniqueId() != null) map.put(player.getUniqueId(), player);
        }
        return map;
    }

    private static String label(String name, UUID uuid) {
        return name == null || name.isBlank() ? uuid.toString() : name + " (" + uuid + ")";
    }
}
