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

        List<String> roots = selectedRoots(entry, worldsOnly);
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
        confirmation = null;

        if (plugin.settings().safetyBackup()) {
            Msg.send(sender, "<gray>복원 전 안전 백업을 만드는 중입니다...</gray>");
            plugin.backupService()
                    .startAsync(BackupType.PRE_RESTORE, "복원 전 자동 백업 (" + current.entry().id() + ")", sender)
                    .whenComplete((entry, error) -> Sched.syncQuietly(plugin, () -> {
                        if (error != null) {
                            Msg.send(sender, "<red>안전 백업에 실패해 복원을 중단합니다: "
                                    + Msg.sanitize(String.valueOf(error.getMessage())) + "</red>");
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

        // 백업에서 제외했던 파일(로그, 캐시 등)은 복원 때도 건드리지 않는다.
        List<String> preserve = new ArrayList<>(settings.preservePatterns());
        for (String pattern : current.entry().excludes()) {
            if (!preserve.contains(pattern)) preserve.add(pattern);
        }

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

    /**
     * worldsOnly 옵션에 따라 복원할 최상위 경로를 고른다.
     *
     * <p>월드를 하나도 찾지 못하면 <b>빈 목록</b>을 돌려준다. 예전에는 전체 경로로 되돌아갔는데,
     * "월드만" 을 요청한 관리자가 경고 한 줄 없이 {@code server.properties} 까지 덮어쓰게 되어
     * 위험했다. 호출자가 이 경우를 명시적으로 거부한다.</p>
     */
    private List<String> selectedRoots(BackupEntry entry, boolean worldsOnly) {
        if (!worldsOnly) return entry.roots();
        List<String> roots = new ArrayList<>();
        for (String root : entry.roots()) {
            for (String world : entry.worlds()) {
                if (root.equalsIgnoreCase(world) || root.toLowerCase(Locale.ROOT)
                        .endsWith("/" + world.toLowerCase(Locale.ROOT))) {
                    roots.add(root);
                    break;
                }
            }
        }
        return roots;
    }
}
