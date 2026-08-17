package io.github.yj.worldbackup.restore;

import io.github.yj.worldbackup.WorldBackUpPlugin;
import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.BackupType;
import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.util.FileUtil;
import io.github.yj.worldbackup.util.Msg;
import io.github.yj.worldbackup.util.Sched;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/** 복원 요청 -> 확인 -> 카운트다운 -> 서버 종료(다음 부팅 시 적용) 흐름을 관리한다. */
public final class RestoreService {

    /** @param roots 복원 대상 경로. 요청 시점에 확정해 두어 확인 단계에서 다시 계산하지 않는다. */
    private record Confirmation(BackupEntry entry, String requester, boolean worldsOnly,
                                List<String> roots, long expiresAt) {
    }

    private final WorldBackUpPlugin plugin;
    private volatile Confirmation confirmation;
    private ScheduledTask countdownTask;

    public RestoreService(WorldBackUpPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isCountingDown() {
        return countdownTask != null;
    }

    /** 1단계: 복원 요청. 실제 실행은 /wb confirm 이후. */
    public void request(CommandSender sender, BackupEntry entry, boolean worldsOnly) {
        if (isCountingDown()) {
            Msg.send(sender, "<red>이미 복원 카운트다운이 진행 중입니다. <white>/wb cancel</white> 로 취소하세요.</red>");
            return;
        }
        if (plugin.backupService().isRunning()) {
            Msg.send(sender, "<red>백업이 진행 중입니다. 완료 후 다시 시도하세요.</red>");
            return;
        }
        if (!entry.complete()) {
            Msg.send(sender, "<red>복원할 수 없는 백업입니다.</red>");
            Msg.send(sender, entry.isDifferential()
                    ? "<gray>차등 백업인데 기준이 되는 전체 백업이 없습니다.</gray>"
                    : "<gray>압축이 끝나기 전에 서버가 종료된 것으로 보입니다.</gray>");
            Msg.send(sender, "<gray><white>/wb delete " + entry.id()
                    + "</white> 로 지우고 다른 백업을 사용하세요.</gray>");
            return;
        }

        List<String> roots = selectedRoots(entry.roots(), entry.worlds(), worldsOnly);
        if (worldsOnly && roots.isEmpty()) {
            Msg.send(sender, "<red>이 백업에서 월드 폴더를 찾을 수 없어 <white>worlds</white> 복원을 할 수 없습니다.</red>");
            Msg.send(sender, "<gray>월드 정보가 없는 백업입니다. 전체를 되돌리려면 "
                    + "<white>/wb restore " + entry.id() + "</white> 를 쓰세요.</gray>");
            return;
        }

        BackupSettings settings = plugin.settings();
        long expires = System.currentTimeMillis() + settings.confirmTimeoutSeconds() * 1000L;
        confirmation = new Confirmation(entry, sender.getName(), worldsOnly, roots, expires);

        Msg.sendRaw(sender, "<dark_red><bold>────────────── 복원 확인 ──────────────</bold></dark_red>");
        Msg.sendRaw(sender, " <gray>백업 ID :</gray> <white>" + entry.id() + "</white>");
        Msg.sendRaw(sender, " <gray>생성 시각:</gray> <white>" + entry.displayTime() + "</white>");
        Msg.sendRaw(sender, " <gray>크기     :</gray> <white>" + FileUtil.humanBytes(entry.archiveBytes()) + "</white>");
        if (entry.isDifferential()) {
            Msg.sendRaw(sender, " <gray>방식     :</gray> <yellow>차등</yellow> <dark_gray>(기준 백업 "
                    + entry.baseId() + " 와 함께 복원됩니다)</dark_gray>");
        }
        Msg.sendRaw(sender, " <gray>대상     :</gray> <white>"
                + (roots.isEmpty() ? "(메타데이터 없음 - 덮어쓰기만)" : String.join(", ", roots)) + "</white>");
        // 공간이 모자라면 복원이 반쯤 진행된 채로 끊긴다. 확정하기 <b>전에</b> 알려 준다.
        if (!reportDiskSpace(sender, entry)) {
            confirmation = null; // /wb confirm 으로 밀고 나갈 수 없게 요청을 지운다
            return;
        }
        // 인벤토리가 안 들어 있는 백업이면 여기서 알려야 한다. 되돌린 뒤에 알면 늦는다.
        if (!entry.hasPlayerData()) {
            Msg.sendRaw(sender, "");
            if (entry.playerDataUnknown()) {
                Msg.sendRaw(sender, " <yellow><bold>이 백업에 플레이어 데이터가 있는지 알 수 없습니다.</bold></yellow>");
                Msg.sendRaw(sender, " <gray>옛 버전으로 만든 백업입니다. 인벤토리·경험치가 되돌아오지 않을 수 있습니다.</gray>");
            } else {
                Msg.sendRaw(sender, " <red><bold>이 백업에는 플레이어 데이터가 없습니다.</bold></red>");
                Msg.sendRaw(sender, " <gray>지형은 되돌아오지만 인벤토리·좌표·경험치는 지금 상태 그대로 남습니다.</gray>");
            }
        }

        Msg.sendRaw(sender, "");
        Msg.sendRaw(sender, " <red>이 시점 이후의 모든 변경사항(건축물, 인벤토리 등)이 사라집니다.</red>");
        Msg.sendRaw(sender, " <red>서버가 자동으로 종료되며, 다시 켜질 때 복원이 적용됩니다.</red>");
        if (plugin.settings().safetyBackup()) {
            Msg.sendRaw(sender, " <gray>복원 직전 현재 상태를 자동으로 한 번 더 백업합니다.</gray>");
        }
        Msg.sendRaw(sender, "");
        Msg.sendRaw(sender, " <yellow>계속하려면 <click:suggest_command:'/wb confirm'><white><bold>/wb confirm</bold></white></click> "
                + "<gray>(" + settings.confirmTimeoutSeconds() + "초 안에)</gray></yellow>");
        Msg.sendRaw(sender, "<dark_red><bold>──────────────────────────────────────</bold></dark_red>");
    }

    /**
     * 복원에 필요한 공간과 남은 공간을 확인 화면에 적는다.
     *
     * <p>복원은 "비우고 푸는" 순서라 도중에 공간이 떨어지면 반쯤 복원된 월드가 남는다.
     * {@code onLoad} 단계에도 같은 점검이 있지만 그때는 서버를 이미 껐다 켠 뒤다 - 확정하기
     * 전에 여기서 알려 주는 편이 훨씬 낫다.</p>
     *
     * <p>필요한 양은 이 백업의 스냅샷 크기로 어림잡는다. {@code worlds} 만 되돌릴 때는 실제보다
     * 크게 잡히는데, 그 방향이 안전하다. 정확한 값은 매니페스트를 읽어야 알 수 있고 그것은
     * 명령 처리 중에 하기엔 무거운 일이다.</p>
     *
     * @return 계속 진행해도 되면 true
     */
    private boolean reportDiskSpace(CommandSender sender, BackupEntry entry) {
        long needed = entry.originalBytes();
        long free = FileUtil.usableSpace(plugin.settings().serverRoot());
        if (needed <= 0) return true; // 크기 기록이 없는 옛 백업 - onLoad 점검에 맡긴다

        String line = " <gray>공간     :</gray> <white>" + FileUtil.humanBytes(needed)
                + "</white> <dark_gray>필요 · " + FileUtil.humanBytes(free) + " 남음</dark_gray>";

        if (!hasRoomToConfirm(needed, free)) {
            Msg.sendRaw(sender, line);
            Msg.sendRaw(sender, "");
            Msg.send(sender, "<red><bold>디스크 여유 공간이 부족해 복원을 시작할 수 없습니다.</bold></red>");
            Msg.send(sender, "<gray>복원은 기존 데이터를 비우고 푸는 순서라, 도중에 공간이 떨어지면 "
                    + "월드가 반쯤 복원된 상태로 남습니다. 그래서 시작하지 않습니다.</gray>");
            Msg.send(sender, "<gray>공간을 확보한 뒤 다시 시도하세요. "
                    + "<white>plugins/WorldBackUp/replaced/</white> 에 이전 복원이 밀어낸 월드가 "
                    + "쌓여 있을 수 있습니다.</gray>");
            return false;
        }

        if (isTightAfterRestore(needed, free, plugin.settings().minFreeDiskBytes())) {
            Msg.sendRaw(sender, line + " <yellow>(빠듯합니다)</yellow>");
            Msg.sendRaw(sender, " <yellow>복원 뒤 남는 공간이 "
                    + FileUtil.humanBytes(free - needed) + " 뿐입니다.</yellow>");
        } else {
            Msg.sendRaw(sender, line);
        }
        return true;
    }

    /** 2단계: 확인. 안전 백업 후 카운트다운을 시작한다. */
    public void confirm(CommandSender sender) {
        Confirmation current = confirmation;
        if (current == null) {
            Msg.send(sender, "<red>대기 중인 복원 요청이 없습니다. <white>/wb restore [ID]</white> 를 먼저 실행하세요.</red>");
            return;
        }
        if (System.currentTimeMillis() > current.expiresAt()) {
            confirmation = null;
            Msg.send(sender, "<red>확인 시간이 지났습니다. 처음부터 다시 시도하세요.</red>");
            return;
        }
        // 요청과 확정 사이(기본 60초)에 자동 백업 주기가 걸릴 수 있다. 그러면 아래 안전 백업이
        // "이미 백업이 진행 중" 으로 실패하면서 복원이 중단되는데, 그 메시지만으로는 무엇이
        // 잘못됐는지 알 수 없다. 요청은 살려 둔다 - 잠시 뒤 /wb confirm 만 다시 치면 된다.
        if (plugin.backupService().isRunning()) {
            Msg.send(sender, "<red>백업이 진행 중이라 지금은 복원을 시작할 수 없습니다.</red>");
            Msg.send(sender, "<gray>진행률 " + plugin.backupService().progressText()
                    + " · 끝나면 <white>/wb confirm</white> 을 다시 입력하세요. "
                    + "요청은 그대로 남아 있습니다.</gray>");
            return;
        }
        confirmation = null;

        if (plugin.settings().safetyBackup()) {
            Msg.send(sender, "<gray>복원 전 안전 백업을 만드는 중입니다...</gray>");
            plugin.backupService()
                    .startAsync(BackupType.PRE_RESTORE, "복원 전 자동 백업 (" + current.entry().id() + ")", sender)
                    .whenComplete((entry, error) -> Sched.syncQuietly(plugin, () -> {
                        if (error != null) {
                            Msg.send(sender, "<red>안전 백업에 실패해 복원을 중단합니다: "
                                    + Msg.sanitize(String.valueOf(error.getMessage())) + "</red>");
                            // 여기서 막히면 되돌릴 방법이 아예 없는 것처럼 보인다. 실제로는 안전
                            // 백업을 포기하면 복원할 수 있고, 하필 그 판단이 필요한 상황(디스크가
                            // 빠듯하거나 복원 실패 정지 중)에서만 이 경로로 들어온다. 위에 실제
                            // 원인이 찍히므로 여기서는 원인을 단정하지 않고 선택지만 적는다.
                            Msg.send(sender, "<gray>공간이 모자란 것이면 자리를 먼저 확보하세요. "
                                    + "그래도 지금 되돌려야 한다면 <white>restore.create-safety-backup: false</white> "
                                    + "로 바꾸고 <white>/wb reload</white> 후 다시 시도할 수 있습니다.</gray>");
                            Msg.send(sender, "<gray>다만 그러면 <red>지금 상태로는 되돌아올 수 없습니다.</red></gray>");
                            return;
                        }
                        Msg.send(sender, "<green>안전 백업 완료: <white>" + entry.id() + "</white></green>");
                        beginCountdown(sender, current);
                    }));
        } else {
            beginCountdown(sender, current);
        }
    }

    public void cancel(CommandSender sender) {
        boolean cancelled = false;
        if (confirmation != null) {
            confirmation = null;
            cancelled = true;
        }
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
            PendingRestore.clear(plugin.getDataFolder().toPath());
            cancelled = true;
            Msg.broadcast("<green>복원이 취소되었습니다.</green>", null);
        }
        Msg.send(sender, cancelled ? "<green>복원 요청을 취소했습니다.</green>" : "<gray>취소할 복원 요청이 없습니다.</gray>");
    }

    public void shutdownTasks() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    // ------------------------------------------------------------------

    private void beginCountdown(CommandSender sender, Confirmation current) {
        BackupSettings settings = plugin.settings();
        Path dataFolder = plugin.getDataFolder().toPath();

        // 백업에서 제외했던 파일(로그, 캐시, 플러그인 자기 상태)은 복원 때도 건드리지 않는다.
        List<String> preserve = restorePreserve(settings.preservePatterns(), current.entry().excludes());

        // 차등 백업은 기준이 되는 전체 백업까지 있어야 복원할 수 있다.
        Path baseArchive = plugin.repository().base(current.entry())
                .map(base -> base.archive().toAbsolutePath())
                .orElse(null);
        if (current.entry().isDifferential() && baseArchive == null) {
            Msg.send(sender, "<red>기준이 되는 전체 백업을 찾을 수 없어 복원을 중단합니다.</red>");
            return;
        }

        PendingRestore pending = new PendingRestore(
                current.entry().id(),
                current.entry().archive().toAbsolutePath(),
                baseArchive,
                current.requester(),
                System.currentTimeMillis(),
                settings.keepReplacedFiles(),
                settings.keepReplacedMax(),
                settings.verifyArchive(),
                preserve,
                current.roots()
        );

        // 예약 파일은 실제로 종료하기 직전에만 기록한다.
        // 카운트다운 도중 서버가 강제로 꺼져도 의도치 않은 복원이 일어나지 않는다.
        if (!Files.isDirectory(dataFolder) && !dataFolder.toFile().mkdirs()) {
            Msg.send(sender, "<red>복원 예약에 실패했습니다. 플러그인 폴더를 만들 수 없습니다.</red>");
            return;
        }

        plugin.getLogger().warning("[복원] " + current.entry().id() + " 복원을 준비합니다. 곧 서버를 종료합니다.");

        final int[] seconds = {Math.max(1, settings.countdownSeconds())};
        countdownTask = Sched.syncTimer(plugin, () -> {
            int remaining = seconds[0];
            if (remaining <= 0) {
                if (countdownTask != null) {
                    countdownTask.cancel();
                    countdownTask = null;
                }
                finishAndShutdown(current.entry(), pending, dataFolder);
                return;
            }
            if (remaining <= 5 || remaining % 5 == 0) {
                Msg.broadcast("<gold><bold>" + remaining + "초</bold></gold> <yellow>후 서버가 종료되고 "
                        + "<white>" + current.entry().displayTime() + "</white> 시점으로 복원됩니다.</yellow>", null);
            }
            seconds[0] = remaining - 1;
        }, 1L, 20L);
    }

    private void finishAndShutdown(BackupEntry entry, PendingRestore pending, Path dataFolder) {
        try {
            pending.write(dataFolder);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "복원 예약 파일을 저장하지 못했습니다. 복원을 중단합니다.", e);
            Msg.broadcast("<red>복원 예약에 실패해 중단했습니다. 콘솔 로그를 확인하세요.</red>", null);
            return;
        }

        String kick = "<red><bold>서버 복원 진행 중</bold></red>\n\n"
                + "<white>" + entry.displayTime() + "</white> <gray>시점으로 되돌리는 중입니다.</gray>\n"
                + "<gray>잠시 후 다시 접속해 주세요.</gray>";
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            player.kick(Msg.parse(kick));
        }
        plugin.getLogger().warning("=================================================");
        plugin.getLogger().warning("[복원] 서버를 종료합니다.");
        plugin.getLogger().warning("[복원] 서버를 다시 시작하면 복원이 자동으로 적용됩니다.");
        plugin.getLogger().warning("[복원] 자동 재시작이 설정되어 있지 않다면 직접 켜 주세요.");
        plugin.getLogger().warning("=================================================");
        Bukkit.shutdown();
    }

    // ------------------------------------------------------------------
    // 판단만 떼어 낸 것들
    //
    // 이 셋은 서버를 만지지 않으므로 경계를 그대로 검증할 수 있다. {@code public} 인 이유는
    // 하나뿐이다 - 잘못되면 복원이 <b>요청하지 않은 파일을 덮어쓰거나</b>, 되돌릴 수 있었던
    // 복원을 막는다. 위쪽 흐름은 서버 없이 돌릴 수 없으니 적어도 판단은 테스트로 못 박아 둔다.
    // (BackupService#hasAmpleRoom, RestoreApplier#hasRoomToRestore 와 같은 규칙이다)

    /**
     * worldsOnly 옵션에 따라 복원할 최상위 경로를 고른다.
     *
     * <p>월드를 하나도 찾지 못하면 <b>빈 목록</b>을 돌려준다. 예전에는 전체 경로로 되돌아갔는데,
     * "월드만" 을 요청한 관리자가 경고 한 줄 없이 {@code server.properties} 까지 덮어쓰게 되어
     * 위험했다. 호출자가 이 경우를 명시적으로 거부한다.</p>
     *
     * @param roots  백업에 담긴 최상위 경로들 (서버 폴더 기준 상대 경로)
     * @param worlds 그 백업에 담긴 월드 이름들
     */
    public static List<String> selectedRoots(List<String> roots, List<String> worlds, boolean worldsOnly) {
        if (!worldsOnly) return roots;
        List<String> selected = new ArrayList<>();
        for (String root : roots) {
            for (String world : worlds) {
                if (root.equalsIgnoreCase(world) || root.toLowerCase(Locale.ROOT)
                        .endsWith("/" + world.toLowerCase(Locale.ROOT))) {
                    selected.add(root);
                    break;
                }
            }
        }
        return selected;
    }

    /**
     * 복원 때 덮어쓰지 않고 그대로 둘 패턴.
     *
     * <p>백업에서 제외했던 것(로그·캐시·플러그인 자기 상태)은 복원 때도 건드리지 않는다.
     * 그 목록이 빠지면 복원이 <b>백업에 없는 파일을 지우기만</b> 한다.</p>
     *
     * @param configured      {@code restore.preserve} 설정
     * @param archiveExcludes 그 백업을 만들 때 쓰인 제외 패턴
     */
    public static List<String> restorePreserve(List<String> configured, List<String> archiveExcludes) {
        List<String> preserve = new ArrayList<>(configured);
        for (String pattern : archiveExcludes) {
            if (!preserve.contains(pattern)) preserve.add(pattern);
        }
        return preserve;
    }

    /**
     * 확정해도 되는 공간이 있는지.
     *
     * <p>{@code needed} 가 0 이하면 크기를 기록하지 않던 옛 백업이다. 그때는 <b>막지 않는다</b> -
     * 알 수 없다는 이유로 되돌릴 방법을 없애는 것보다, {@code onLoad} 의 정확한 점검에
     * 맡기는 편이 낫다.</p>
     */
    public static boolean hasRoomToConfirm(long neededBytes, long freeBytes) {
        if (neededBytes <= 0) return true;
        return freeBytes >= neededBytes;
    }

    /** 복원 뒤 남는 공간이 설정한 최소 여유보다 적은지. 막지는 않고 경고만 하는 데 쓴다. */
    public static boolean isTightAfterRestore(long neededBytes, long freeBytes, long minFreeBytes) {
        if (!hasRoomToConfirm(neededBytes, freeBytes) || neededBytes <= 0) return false;
        return freeBytes - neededBytes < minFreeBytes;
    }
}
