package io.github.yj.worldbackup.listener;

import io.github.yj.worldbackup.WorldBackUpPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 마지막 백업 이후 월드에 변화가 있었는지 표시한다.
 * (backup.skip-if-no-players 옵션에서 사용)
 */
public final class ActivityListener implements Listener {

    private final WorldBackUpPlugin plugin;

    public ActivityListener(WorldBackUpPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.backupService().markWorldChanged();
        // 플레이어 데이터 폴더는 첫 접속 때 생긴다. 그전에 "못 찾음" 으로 굳은 판단을 풀어 준다.
        plugin.refreshPlayerDataIfMissing();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        plugin.backupService().markWorldChanged();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        plugin.backupService().markWorldChanged();
    }
}
