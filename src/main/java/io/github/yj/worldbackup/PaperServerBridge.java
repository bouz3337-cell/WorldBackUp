package io.github.yj.worldbackup;

import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.ServerBridge;
import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.util.Msg;
import io.github.yj.worldbackup.util.Sched;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/**
 * {@link ServerBridge} 의 Paper 구현.
 *
 * <p>백업 쪽에서 {@code Bukkit} 을 만지는 곳은 이 파일 하나다. 판단은 하지 않고 넘기기만 한다 -
 * 여기에 분기가 생기면 그 분기는 서버 없이 검증할 수 없는 코드가 된다.</p>
 */
public final class PaperServerBridge implements ServerBridge {

    private final WorldBackUpPlugin plugin;

    public PaperServerBridge(WorldBackUpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Logger logger() {
        return plugin.getLogger();
    }

    @Override
    public BackupSettings settings() {
        return plugin.settings();
    }

    @Override
    public BackupRepository repository() {
        return plugin.repository();
    }

    @Override
    public boolean restoreFailureHold() {
        return plugin.restoreFailureHold();
    }

    @Override
    public boolean hasOnlinePlayers() {
        return !Bukkit.getOnlinePlayers().isEmpty();
    }

    @Override
    public String serverVersion() {
        return Bukkit.getBukkitVersion();
    }

    @Override
    public void savePlayers() {
        Bukkit.savePlayers();
    }

    @Override
    public List<WorldHandle> worlds() {
        List<WorldHandle> handles = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            handles.add(new BukkitWorld(world));
        }
        return handles;
    }

    @Override
    public Optional<WorldHandle> world(String name) {
        World world = Bukkit.getWorld(name);
        return world == null ? Optional.empty() : Optional.of(new BukkitWorld(world));
    }

    @Override
    public void runAsync(Runnable task) {
        Sched.async(plugin, task);
    }

    @Override
    public void runSyncQuietly(Runnable task) {
        Sched.syncQuietly(plugin, task);
    }

    @Override
    public <T> T callSync(Callable<T> callable, long timeoutSeconds) throws Exception {
        return Sched.callSync(plugin, callable, timeoutSeconds);
    }

    @Override
    public void broadcast(String message, String permission) {
        Msg.broadcast(message, permission);
    }

    private record BukkitWorld(World world) implements WorldHandle {

        @Override
        public String name() {
            return world.getName();
        }

        @Override
        public Path folder() {
            return world.getWorldPath().toAbsolutePath().normalize();
        }

        @Override
        public boolean autoSave() {
            return world.isAutoSave();
        }

        @Override
        public void autoSave(boolean value) {
            world.setAutoSave(value);
        }

        @Override
        public void saveQueued() {
            // 예약만 하고 즉시 돌아온다. 예열용이다.
            world.save(false);
        }

        @Override
        public void saveNow() {
            // save(true) 여야 한다. 인자 없는 save() 는 save(false) 이고, 그건 청크 쓰기를
            // 예약만 하고 즉시 돌아온다.
            world.save(true);
        }
    }
}
