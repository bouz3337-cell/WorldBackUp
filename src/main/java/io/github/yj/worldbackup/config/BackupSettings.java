package io.github.yj.worldbackup.config;

import io.github.yj.worldbackup.backup.RetentionTiers;
import io.github.yj.worldbackup.util.FileUtil;
import io.github.yj.worldbackup.util.GlobMatcher;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** config.yml 을 읽어들인 불변 설정 스냅샷. */
public final class BackupSettings {

    // backup
    private final boolean enabled;
    private final boolean differential;
    private final int fullEvery;
    private final int intervalMinutes;
    private final int initialDelayMinutes;
    private final boolean onStartup;
    private final boolean onShutdown;
    private final boolean skipIfNoPlayers;
    private final int compressionLevel;
    private final Path backupDir;
    private final boolean broadcast;
    private final String broadcastPermission;

    // targets
    private final List<String> worlds;
    private final List<String> serverFiles;
    private final List<String> extraPaths;
    private final List<String> excludePatterns;
    private final GlobMatcher exclude;

    // retention
    private final List<RetentionTiers.Tier> tiers;

    /**
     * 계단 설정을 읽으며 생긴 경고.
     *
     * <p>형식이 깨진 계단을 조용히 버리면 관리자가 기대한 시간대가 소리 없이 비어 버린다.
     * 여기 모아 두고 플러그인이 로드할 때 콘솔에 띄운다.
     * ({@link org.bukkit.Bukkit} 을 여기서 쓰면 서버 없이 도는 테스트가 깨진다)</p>
     */
    private final List<String> tierWarnings = new ArrayList<>();
    private final int maxBackups;
    private final int minBackups;
    private final int maxAgeDays;
    private final int keepDaily;
    private final boolean protectManual;
    private final int maxProtected;
    private final long minFreeDiskBytes;
    private final long maxTotalBytes;

    // restore
    private final int countdownSeconds;
    private final boolean safetyBackup;
    private final boolean keepReplacedFiles;
    private final int keepReplacedMax;
    private final boolean verifyArchive;
    private final int confirmTimeoutSeconds;
    private final List<String> preservePatterns;

    private final Path serverRoot;

    private BackupSettings(FileConfiguration cfg, Path dataFolder, Path serverRoot) {
        this.serverRoot = serverRoot;

        this.enabled = cfg.getBoolean("backup.enabled", true);
        String mode = String.valueOf(cfg.getString("backup.mode", "full")).trim().toLowerCase(Locale.ROOT);
        this.differential = mode.startsWith("diff") || mode.startsWith("차등");
        this.fullEvery = Math.max(0, cfg.getInt("backup.full-every", 24));
        this.intervalMinutes = Math.max(1, cfg.getInt("backup.interval-minutes", 30));
        this.initialDelayMinutes = Math.max(0, cfg.getInt("backup.initial-delay-minutes", 10));
        this.onStartup = cfg.getBoolean("backup.on-startup", false);
        this.onShutdown = cfg.getBoolean("backup.on-shutdown", false);
        this.skipIfNoPlayers = cfg.getBoolean("backup.skip-if-no-players", true);
        this.compressionLevel = Math.min(9, Math.max(0, cfg.getInt("backup.compression-level", 4)));
        this.broadcast = cfg.getBoolean("backup.broadcast", true);
        this.broadcastPermission = cfg.getBoolean("backup.broadcast-permission-only", true)
                ? "worldbackup.notify" : null;

        String dir = cfg.getString("backup.directory", "backups");
        Path configured = Paths.get(dir == null || dir.isBlank() ? "backups" : dir);
        this.backupDir = (configured.isAbsolute() ? configured : dataFolder.resolve(configured))
                .toAbsolutePath().normalize();

        this.worlds = lower(cfg.getStringList("targets.worlds"));
        this.serverFiles = cfg.getStringList("targets.server-files");
        this.extraPaths = cfg.getStringList("targets.extra-paths");

        List<String> excludes = new ArrayList<>(cfg.getStringList("targets.exclude"));
        // 플러그인 자기 폴더(backups/, replaced/, 예약 파일 등)와 백업 폴더는 절대 백업하지 않는다.
        // replaced/ 에는 복원 때 밀어낸 옛 월드가 통째로 들어 있어서, 빠뜨리면 백업이 눈덩이처럼 불어난다.
        addSelfExclusion(excludes, serverRoot, dataFolder);
        addSelfExclusion(excludes, serverRoot, backupDir);
        this.excludePatterns = List.copyOf(excludes);
        this.exclude = new GlobMatcher(excludes);

        this.tiers = readTiers(cfg);
        this.maxBackups = Math.max(0, cfg.getInt("retention.max-backups", 48));
        this.minBackups = Math.max(0, cfg.getInt("retention.min-backups", 5));
        this.maxAgeDays = Math.max(0, cfg.getInt("retention.max-age-days", 14));
        this.keepDaily = Math.max(0, cfg.getInt("retention.keep-daily", 7));
        this.protectManual = cfg.getBoolean("retention.protect-manual", true);
        this.maxProtected = Math.max(0, cfg.getInt("retention.max-protected", 10));
        this.minFreeDiskBytes = Math.max(0L, cfg.getLong("retention.min-free-disk-gb", 5)) * 1024L * 1024L * 1024L;
        // 소수점을 허용한다. 작은 서버는 0.5(=512MB) 처럼 잡고 싶을 수 있다.
        this.maxTotalBytes = (long) (Math.max(0.0, cfg.getDouble("retention.max-total-size-gb", 0))
                * 1024L * 1024L * 1024L);

        this.countdownSeconds = Math.max(0, cfg.getInt("restore.countdown-seconds", 15));
        this.safetyBackup = cfg.getBoolean("restore.create-safety-backup", true);
        this.keepReplacedFiles = cfg.getBoolean("restore.keep-replaced-files", true);
        this.keepReplacedMax = Math.max(0, cfg.getInt("restore.keep-replaced-max", 3));
        this.verifyArchive = cfg.getBoolean("restore.verify-archive", true);
        this.confirmTimeoutSeconds = Math.max(10, cfg.getInt("restore.confirm-timeout-seconds", 60));
        List<String> preserveList = cfg.getStringList("restore.preserve");
        if (preserveList.isEmpty()) preserveList = List.of("**/session.lock");
        this.preservePatterns = List.copyOf(preserveList);
    }

    public static BackupSettings load(FileConfiguration cfg, Path dataFolder, Path serverRoot) {
        return new BackupSettings(cfg, dataFolder, serverRoot);
    }

    /** 서버 루트 안에 있는 플러그인 소유 폴더를 백업 대상에서 빼 준다. */
    private static void addSelfExclusion(List<String> excludes, Path serverRoot, Path own) {
        String relative = FileUtil.relativize(serverRoot, own);
        if (relative == null) return; // 서버 폴더 밖이면 애초에 백업되지 않는다
        if (!excludes.contains(relative)) excludes.add(relative);
        String subtree = relative + "/**";
        if (!excludes.contains(subtree)) excludes.add(subtree);
    }

    /**
     * {@code retention.tiers} 를 읽는다. 비어 있으면 예전 정책(max-backups/max-age-days/keep-daily)을 쓴다.
     *
     * <p>형식이 깨진 항목은 조용히 버리지 않고 건너뛰되, 하나라도 제대로 읽히면 계단식으로 동작한다.
     * 전부 깨졌다면 빈 목록이 되어 예전 정책으로 돌아가므로 백업이 통째로 정리되는 일은 없다.</p>
     */
    private List<RetentionTiers.Tier> readTiers(FileConfiguration cfg) {
        List<RetentionTiers.Tier> tiers = new ArrayList<>();
        List<Map<?, ?>> raw = cfg.getMapList("retention.tiers");
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> item = raw.get(i);
            String label = "retention.tiers[" + i + "]";

            Duration every = parseDuration(String.valueOf(item.get("every")));
            if (every == null) {
                tierWarnings.add(label + ": every 값을 알아듣지 못했습니다 ("
                        + item.get("every") + "). 0, 15m, 6h, 3d 형식만 됩니다. 이 계단은 무시합니다.");
                continue;
            }
            int keep = item.get("keep") instanceof Number number ? number.intValue() : 0;
            if (keep <= 0) {
                tierWarnings.add(label + ": keep 이 " + item.get("keep")
                        + " 이라 이 계단은 아무것도 남기지 않습니다. 무시합니다.");
                continue;
            }
            tiers.add(new RetentionTiers.Tier(every, keep));
        }
        if (tiers.isEmpty() && !raw.isEmpty()) {
            tierWarnings.add("retention.tiers 를 하나도 읽지 못해 예전 정책"
                    + "(max-backups/max-age-days/keep-daily)으로 동작합니다.");
        }
        return List.copyOf(tiers);
    }

    /** {@code 0}, {@code 15m}, {@code 6h}, {@code 3d}. 해석 못 하면 null. */
    private static Duration parseDuration(String text) {
        if (text == null) return null;
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.equals("0")) return Duration.ZERO;
        if (value.length() < 2) return null;
        char unit = value.charAt(value.length() - 1);
        try {
            long amount = Long.parseLong(value.substring(0, value.length() - 1));
            if (amount < 0) return null;
            return switch (unit) {
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> lower(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) out.add(value.toLowerCase(Locale.ROOT));
        return out;
    }

    /** 해당 월드를 백업 대상에 포함할지 여부. */
    public boolean includesWorld(String name) {
        if (worlds.isEmpty() || worlds.contains("*")) return true;
        return worlds.contains(name.toLowerCase(Locale.ROOT));
    }

    public boolean enabled() { return enabled; }

    /** 차등 백업 모드인지. 전체 백업 하나를 기준으로 이후에는 바뀐 파일만 저장한다. */
    public boolean differential() { return differential; }

    /** 차등 백업을 이 개수만큼 만들면 전체 백업을 다시 만든다. (0 = 자동 재생성 안 함) */
    public int fullEvery() { return fullEvery; }

    public int intervalMinutes() { return intervalMinutes; }

    public int initialDelayMinutes() { return initialDelayMinutes; }

    public boolean onStartup() { return onStartup; }

    public boolean onShutdown() { return onShutdown; }

    public boolean skipIfNoPlayers() { return skipIfNoPlayers; }

    public int compressionLevel() { return compressionLevel; }

    public Path backupDir() { return backupDir; }

    public boolean broadcast() { return broadcast; }

    public String broadcastPermission() { return broadcastPermission; }

    public List<String> serverFiles() { return serverFiles; }

    public List<String> extraPaths() { return extraPaths; }

    public List<String> excludePatterns() { return excludePatterns; }

    public GlobMatcher exclude() { return exclude; }

    /**
     * 계단식 보관 설정. 비어 있으면 {@code max-backups}/{@code max-age-days}/{@code keep-daily} 를 쓴다.
     *
     * <p>둘을 섞지 않는다. 계단이 남길 것을 이미 정하는데 개수·나이 상한이 그 위에서 또 깎으면,
     * 관리자가 기대한 시간대가 조용히 비어 버린다.</p>
     */
    public List<RetentionTiers.Tier> tiers() { return tiers; }

    /** 계단 설정을 읽으며 생긴 경고. 비어 있으면 문제없이 읽혔다는 뜻이다. */
    public List<String> tierWarnings() { return List.copyOf(tierWarnings); }

    public int maxBackups() { return maxBackups; }

    /**
     * 어떤 보관 정책으로도 이 개수 아래로는 줄이지 않는다. (0 = 하한 없음)
     *
     * <p>무인 서버가 오래 놀면 나이 정책 하나로 백업이 <b>전멸</b>할 수 있다. 그 사이
     * {@code keep-daily} 는 "최근 N일 안의 백업"만 지키는데 그 기간에 만들어진 백업이 없고,
     * 자동 백업은 {@code protect-manual} 대상도 아니기 때문이다.</p>
     */
    public int minBackups() { return minBackups; }

    public int maxAgeDays() { return maxAgeDays; }

    public int keepDaily() { return keepDaily; }

    public boolean protectManual() { return protectManual; }

    public int maxProtected() { return maxProtected; }

    public long minFreeDiskBytes() { return minFreeDiskBytes; }

    /**
     * 백업 폴더 전체가 넘지 말아야 할 크기. (0 = 무제한)
     *
     * <p>{@link #minFreeDiskBytes()} 는 파일시스템에 남은 공간을 묻는데, 컨테이너로 돌아가는
     * 호스팅에서는 그 값이 <b>호스트 디스크</b>를 가리켜 정작 걸려 있는 할당량을 못 본다.
     * 그러면 백업이 할당량을 향해 자라도 아무도 막지 않는다. 이 값은 백업 폴더 크기를
     * 직접 재서 지키므로 그런 환경에서도 동작한다.</p>
     *
     * <p>계단 설정을 잘못 잡아 예상보다 많이 쌓이는 경우에도 마지막 방어선이 된다.</p>
     */
    public long maxTotalBytes() { return maxTotalBytes; }

    public int countdownSeconds() { return countdownSeconds; }

    public boolean safetyBackup() { return safetyBackup; }

    public boolean keepReplacedFiles() { return keepReplacedFiles; }

    public int keepReplacedMax() { return keepReplacedMax; }

    public boolean verifyArchive() { return verifyArchive; }

    public int confirmTimeoutSeconds() { return confirmTimeoutSeconds; }

    public List<String> preservePatterns() { return preservePatterns; }

    public Path serverRoot() { return serverRoot; }
}
