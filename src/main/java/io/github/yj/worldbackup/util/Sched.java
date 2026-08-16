package io.github.yj.worldbackup.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 스케줄링을 한 곳에 모아 둔다.
 *
 * <p>레거시 {@code Bukkit.getScheduler()} 대신 Paper 의 현대 스케줄러
 * ({@code AsyncScheduler} / {@code GlobalRegionScheduler}) 를 쓴다. 일반 Paper 에서 동일하게
 * 동작하면서, 나중에 Folia 로 넘어갈 때 손볼 곳이 이 파일 하나로 줄어든다.</p>
 *
 * <p>주의: 아직 Folia 실서버에서 검증하지 않았으므로 {@code plugin.yml} 에
 * {@code folia-supported} 는 선언하지 않는다. 월드 저장이 리전별로 쪼개져 있는 Folia 에서는
 * "전 월드를 동시에 얼린 스냅샷"이 성립하지 않아 백업 로직 자체를 다시 설계해야 한다.</p>
 */
public final class Sched {

    private Sched() {
    }

    /** 서버의 메인(글로벌) 틱 스레드인지. */
    public static boolean isMain() {
        return Bukkit.isPrimaryThread();
    }

    /** 비동기 작업. 지금 바로 워커 스레드에서 시작한다. */
    public static void async(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }

    /** 메인 스레드 작업. 이미 메인 스레드면 즉시 실행한다. */
    public static void sync(Plugin plugin, Runnable task) {
        if (isMain()) {
            task.run();
            return;
        }
        Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
    }

    /** 메인 스레드 작업. 서버가 종료 중이라 스케줄러가 거부하면 조용히 포기한다. */
    public static void syncQuietly(Plugin plugin, Runnable task) {
        try {
            sync(plugin, task);
        } catch (Exception ignored) {
            // 플러그인이 비활성화되는 중이면 등록이 거부된다.
        }
    }

    public static void syncLater(Plugin plugin, Runnable task, long delayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), Math.max(1L, delayTicks));
    }

    public static ScheduledTask syncTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, ignored -> task.run(), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    public static void cancelAll(Plugin plugin) {
        try {
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } catch (Exception ignored) {
        }
        try {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        } catch (Exception ignored) {
        }
    }

    /**
     * 메인 스레드에서 실행하고 결과를 기다린다.
     *
     * <p>제한 시간을 넘기면 아직 시작되지 않은 작업의 실행 자체를 취소해서, 뒤늦게 서버 상태를
     * 건드리는 일이 없도록 한다.</p>
     */
    public static <T> T callSync(Plugin plugin, Callable<T> callable, long timeoutSeconds) throws Exception {
        if (isMain()) {
            return callable.call();
        }

        AtomicBoolean abandoned = new AtomicBoolean(false);
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> {
            if (abandoned.get()) return;
            try {
                future.complete(callable.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            abandoned.set(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception checked) throw checked;
            if (cause instanceof Error error) throw error;
            throw e;
        }
    }
}
