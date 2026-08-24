package io.github.yj.worldbackup.config;

import io.github.yj.worldbackup.backup.Archiver;
import io.github.yj.worldbackup.backup.RetentionTiers;
import io.github.yj.worldbackup.restore.PendingRestore;
import io.github.yj.worldbackup.restore.RestoreApplier;
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

    /**
     * 플러그인 폴더를 어디까지 담을지.
     *
     * <p>월드만 되돌리면 경제 잔고·보호구역·권한처럼 <b>플러그인이 들고 있는 상태</b>는 그대로
     * 남아 월드와 어긋난다. 되돌린 시점에는 없던 상점 거래가 그대로 살아 있는 식이다.</p>
     */
    public enum Plugins {
        /** 담지 않는다. */
        NONE,
        /** 설정과 데이터만. {@code plugins/} 바로 아래의 jar 는 뺀다. */
        DATA,
        /** jar 까지 전부. 서버를 통째로 잃었을 때 아카이브 하나로 되세울 수 있다. */
        ALL
    }

    // backup
    private final boolean enabled;
    private final boolean differential;
    private final int fullEvery;
    private final int intervalMinutes;
    private final int initialDelayMinutes;
    private final boolean onStartup;
    private final boolean onShutdown;
    private final boolean skipIfNoPlayers;
    private final int maxSkippedCycles;
    private final int compressionLevel;
    private final long flushSettleMillis;
    /** OneBack - 서버 한 벌을 통째로 담은 아카이브. 이 폴더만 챙기면 아무 데서나 서버를 다시 연다. */
    private final boolean oneBackEnabled;
    private final Path oneBackDir;
    private final int oneBackKeep;
    private final int oneBackIntervalHours;
    private final GlobMatcher oneBackExclude;

    /** 새 버전 확인. jar 하나로 업데이트되는 플러그인이라, 새 버전이 나온 줄 아는 것이 중요하다. */
    private final boolean updateCheck;
    private final boolean updateAutoDownload;
    private final String updateRepository;

    private final Path backupDir;
    private final boolean broadcast;
    private final String broadcastPermission;

    // targets
    private final List<String> worlds;
    private final List<String> serverFiles;
    private final List<String> extraPaths;
    private final Plugins plugins;
    private final Path pluginsDir;
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
    private final Path ownConfigFile;

    /** {@code backup.directory} 의 기본값. 값을 옮겨도 옛 아카이브가 이 이름으로 남아 있다. */
    private static final String DEFAULT_ARCHIVE_FOLDER = "backups";
    private static final String DEFAULT_ONEBACK_FOLDER = "OneBack";

    /** plugin.yml 의 website 와 같은 곳을 가리켜야 한다. */
    private static final String DEFAULT_REPOSITORY = "bouz3337-cell/WorldBackUp";

    /** {@link org.bukkit.plugin.java.JavaPlugin#saveDefaultConfig()} 가 만드는 파일 이름. */
    private static final String CONFIG_FILE_NAME = "config.yml";

    private BackupSettings(FileConfiguration cfg, Path dataFolder, Path serverRoot) {
        this.serverRoot = serverRoot;
        this.ownConfigFile = dataFolder.resolve(CONFIG_FILE_NAME).toAbsolutePath().normalize();

        this.enabled = cfg.getBoolean("backup.enabled", true);
        String mode = String.valueOf(cfg.getString("backup.mode", "differential")).trim().toLowerCase(Locale.ROOT);
        this.differential = mode.startsWith("diff") || mode.startsWith("차등");
        this.fullEvery = Math.max(0, cfg.getInt("backup.full-every", 24));
        this.intervalMinutes = Math.max(1, cfg.getInt("backup.interval-minutes", 30));
        this.initialDelayMinutes = Math.max(0, cfg.getInt("backup.initial-delay-minutes", 10));
        this.onStartup = cfg.getBoolean("backup.on-startup", false);
        this.onShutdown = cfg.getBoolean("backup.on-shutdown", false);
        this.skipIfNoPlayers = cfg.getBoolean("backup.skip-if-no-players", true);
        this.maxSkippedCycles = Math.max(0, cfg.getInt("backup.max-skipped-cycles", 48));
        this.compressionLevel = Math.min(9, Math.max(0, cfg.getInt("backup.compression-level", 4)));
        this.flushSettleMillis = Math.max(0, cfg.getInt("backup.flush-settle-seconds", 3)) * 1000L;
        this.broadcast = cfg.getBoolean("backup.broadcast", true);
        this.broadcastPermission = cfg.getBoolean("backup.broadcast-permission-only", true)
                ? "worldbackup.notify" : null;

        String dir = cfg.getString("backup.directory", DEFAULT_ARCHIVE_FOLDER);
        Path configured = Paths.get(dir == null || dir.isBlank() ? DEFAULT_ARCHIVE_FOLDER : dir);
        this.backupDir = (configured.isAbsolute() ? configured : dataFolder.resolve(configured))
                .toAbsolutePath().normalize();

        // 전부 복사해서 잠근다. "불변 설정 스냅샷" 이 이 클래스의 계약인데, getStringList 가
        // 돌려주는 리스트를 그대로 들고 있으면 호출자가 백업 대상을 바꿔 버릴 수 있다.
        this.worlds = List.copyOf(lower(cfg.getStringList("targets.worlds")));
        this.serverFiles = List.copyOf(cfg.getStringList("targets.server-files"));
        this.extraPaths = List.copyOf(cfg.getStringList("targets.extra-paths"));

        // plugins/ 그 자체. "plugins" 라는 이름을 여기 적지 않고 데이터 폴더의 부모를 쓴다 -
        // --plugins 로 폴더를 옮긴 서버에서 엉뚱한 곳을 가리키지 않도록.
        this.pluginsDir = dataFolder.toAbsolutePath().normalize().getParent();
        this.plugins = readPlugins(cfg.getString("targets.plugins", "all"));

        // OneBack - 서버 한 벌을 통째로 담는다. 상대 경로는 <b>서버 폴더</b> 기준이다.
        // (백업 폴더는 plugins/WorldBackUp/ 기준인데, 이쪽은 서버 전체를 담는 것이라
        //  "서버 폴더 옆에 둔다" 는 감각이 맞다)
        this.oneBackEnabled = cfg.getBoolean("oneback.enabled", true);
        String oneDir = cfg.getString("oneback.directory", DEFAULT_ONEBACK_FOLDER);
        Path oneConfigured = Paths.get(oneDir == null || oneDir.isBlank()
                ? DEFAULT_ONEBACK_FOLDER : oneDir);
        this.oneBackDir = (oneConfigured.isAbsolute() ? oneConfigured : serverRoot.resolve(oneConfigured))
                .toAbsolutePath().normalize();
        this.oneBackKeep = Math.max(1, cfg.getInt("oneback.keep", 2));
        this.oneBackIntervalHours = Math.max(0, cfg.getInt("oneback.interval-hours", 0));

        this.updateCheck = cfg.getBoolean("update.check", true);
        this.updateAutoDownload = cfg.getBoolean("update.auto-download", false);
        String repo = cfg.getString("update.repository", DEFAULT_REPOSITORY);
        this.updateRepository = repo == null || repo.isBlank() ? DEFAULT_REPOSITORY : repo.trim();

        // 플러그인이 스스로 만들어 쓰는 것들과 백업 폴더. 백업에서도 빼고, 복원에서도 지키므로
        // 따로 모아 둔다.
        List<String> ownState = new ArrayList<>();
        addOwnStateExclusions(ownState, serverRoot, dataFolder);
        addSelfExclusion(ownState, serverRoot, backupDir);
        // OneBack 파일 하나가 서버 폴더 크기만큼이다. 평소 백업이 이걸 삼키면 백업이 두 배가
        // 되고, 그다음 OneBack 은 그 백업까지 삼킨다.
        addSelfExclusion(ownState, serverRoot, oneBackDir);

        List<String> excludes = new ArrayList<>(cfg.getStringList("targets.exclude"));
        for (String pattern : ownState) addPattern(excludes, pattern);
        if (plugins == Plugins.DATA) addPattern(excludes, pluginJarPattern(serverRoot, pluginsDir));
        this.excludePatterns = List.copyOf(excludes);
        this.exclude = new GlobMatcher(excludes);

        // OneBack 은 서버 폴더를 통째로 담으므로 <b>평소 제외 패턴을 쓰지 않는다.</b>
        // 그쪽은 "월드를 되돌리는 데 필요 없는 것" 을 빼는 목록이라, 그대로 쓰면 정작 서버를
        // 다시 여는 데 필요한 것(plugins/*.jar, cache/)까지 빠진다. 대신 자기 자신과 평소
        // 백업 폴더만은 반드시 빼야 한다 - 넣으면 아카이브가 아카이브를 삼킨다.
        List<String> oneBackExcludes = new ArrayList<>(cfg.getStringList("oneback.exclude"));
        addSelfExclusion(oneBackExcludes, serverRoot, oneBackDir);
        addSelfExclusion(oneBackExcludes, serverRoot, backupDir);
        addPattern(oneBackExcludes, "**/session.lock");
        addPattern(oneBackExcludes, "**/*.mca.*.backup");
        // 복원 예약·실패 표식·replaced 는 담기면 안 된다. 되살아나면 다음 부팅이 또 복원한다.
        addOwnStateExclusions(oneBackExcludes, serverRoot, dataFolder);
        this.oneBackExclude = new GlobMatcher(oneBackExcludes);

        this.tiers = readTiers(cfg);
        this.maxBackups = Math.max(0, cfg.getInt("retention.max-backups", 48));
        this.minBackups = Math.max(0, cfg.getInt("retention.min-backups", 5));
        this.maxAgeDays = Math.max(0, cfg.getInt("retention.max-age-days", 14));
        this.keepDaily = Math.max(0, cfg.getInt("retention.keep-daily", 7));
        this.protectManual = cfg.getBoolean("retention.protect-manual", true);
        this.maxProtected = Math.max(0, cfg.getInt("retention.max-protected", 10));
        // 두 값 모두 소수점을 허용한다. 작은 서버는 0.5(=512MB) 처럼 잡고 싶을 수 있다.
        //
        // min-free-disk-gb 는 예전에 getLong 이었다. 그러면 0.5 가 조용히 0 이 되어 - 디스크
        // 여유를 확보해 달라고 적어 둔 값이 "여유 없음" 으로 바뀐다. 하필 공간이 빠듯한 서버에서만
        // 그렇게 되고, 아무 경고도 없어서 백업이 디스크를 끝까지 채운 뒤에야 드러난다.
        this.minFreeDiskBytes = gigabytesToBytes(cfg.getDouble("retention.min-free-disk-gb", 5));
        this.maxTotalBytes = gigabytesToBytes(cfg.getDouble("retention.max-total-size-gb", 0));

        this.countdownSeconds = Math.max(0, cfg.getInt("restore.countdown-seconds", 15));
        this.safetyBackup = cfg.getBoolean("restore.create-safety-backup", true);
        this.keepReplacedFiles = cfg.getBoolean("restore.keep-replaced-files", true);
        this.keepReplacedMax = Math.max(0, cfg.getInt("restore.keep-replaced-max", 3));
        this.verifyArchive = cfg.getBoolean("restore.verify-archive", true);
        this.confirmTimeoutSeconds = Math.max(10, cfg.getInt("restore.confirm-timeout-seconds", 60));
        List<String> preserveList = new ArrayList<>(cfg.getStringList("restore.preserve"));
        if (preserveList.isEmpty()) preserveList.add("**/session.lock");
        // 설정과 무관하게 언제나. 이유는 pluginJarPattern 에 적어 두었다.
        addPattern(preserveList, pluginJarPattern(serverRoot, pluginsDir));
        // 우리 폴더도 <b>지금 설정 기준으로</b> 지킨다.
        //
        // 복원이 지키는 목록은 두 곳에서 온다 - 여기와, 그 백업을 만들 때 쓰인 제외 패턴이다
        // ({@link io.github.yj.worldbackup.restore.RestoreService#restorePreserve}). 뒤쪽에는
        // <b>그때의</b> 폴더 이름만 적혀 있다. backup.directory 를 바꾼 뒤 옛 백업을 복원하면
        // 지금 백업 폴더는 어느 목록에도 없는데, plugins/ 가 복원 대상이 된 뒤로는 그 차이가
        // 곧 "복원 한 번에 지금 백업이 전멸" 이다. 되돌리기를 취소할 방법까지 함께 사라진다.
        for (String pattern : ownState) addPattern(preserveList, pattern);
        this.preservePatterns = List.copyOf(preserveList);

        warnIfDeepTiersMeetFullMode();
    }

    /**
     * 전체 백업 몇 벌부터 계단 설정을 다시 보라고 할지.
     *
     * <p>전체 백업은 한 벌이 곧 월드 하나다. 이 개수를 넘어가면 "월드 크기 × 개수" 가
     * 웬만한 서버의 디스크를 넘어선다.</p>
     */
    private static final int FULL_MODE_TIER_LIMIT = 8;

    /**
     * {@code mode: full} 과 깊은 계단이 만나면 알린다.
     *
     * <p>이 조합은 <b>조용히</b> 실패하는 것이 문제다. 디스크가 먼저 차서 공간 확보 로직이
     * 매 주기 오래된 백업을 지우고 다시 채우는데, {@code /wb status} 는 그동안 계속
     * "계단식 5단계 (최대 27개)" 를 보여 준다. 관리자는 자는 동안의 1시간 해상도가 지켜지고
     * 있다고 믿지만 실제로는 최근 몇 개만 남아 있고, 그 사실은 정작 되돌려야 하는 날에야
     * 드러난다. 컨테이너 호스팅이면 {@code min-free-disk-gb} 가 호스트 디스크를 보므로
     * 그 브레이크조차 걸리지 않는다.</p>
     */
    private void warnIfDeepTiersMeetFullMode() {
        if (differential || tiers.isEmpty()) return;
        int planned = 0;
        for (RetentionTiers.Tier tier : tiers) planned += tier.keep();
        if (planned <= FULL_MODE_TIER_LIMIT) return;

        tierWarnings.add("backup.mode 가 full 인데 retention.tiers 는 " + planned
                + "개를 남기려 합니다. 전체 백업 " + planned + "벌은 월드 크기의 " + planned + "배를 씁니다.");
        tierWarnings.add("  그대로 두면 디스크가 먼저 차서 계단이 약속한 시간대가 만들어지지 않습니다."
                + " backup.mode 를 differential 로 바꾸거나 tiers 의 keep 값을 줄이세요.");
        if (maxTotalBytes <= 0) {
            tierWarnings.add("  retention.max-total-size-gb 도 0(무제한)이라 상한이 없습니다."
                    + " 호스팅 환경이라면 반드시 값을 넣으세요.");
        }
    }

    public static BackupSettings load(FileConfiguration cfg, Path dataFolder, Path serverRoot) {
        return new BackupSettings(cfg, dataFolder, serverRoot);
    }

    /** 서버 루트 안에 있는 플러그인 소유 폴더를 백업 대상에서 빼 준다. */
    private static void addSelfExclusion(List<String> excludes, Path serverRoot, Path own) {
        String relative = FileUtil.relativize(serverRoot, own);
        if (relative == null) return; // 서버 폴더 밖이면 애초에 백업되지 않는다
        addPattern(excludes, relative);
        addPattern(excludes, relative + "/**");
    }

    /**
     * 플러그인이 <b>스스로 만들어 쓰는 상태</b>만 백업에서 뺀다.
     *
     * <p>예전에는 데이터 폴더를 통째로 제외했는데, 그러면 {@code config.yml} 도 함께 빠졌다.
     * 서버를 그 시점으로 되돌려도 <b>보관 정책만은 되돌아오지 않는다</b>는 뜻이라, 백업이 어떻게
     * 만들어졌는지가 정작 백업 안에 없었다. 그래서 제외를 "우리 폴더 전체" 에서 "우리가 쓰는
     * 상태" 로 좁힌다.</p>
     *
     * <p>이름은 전부 <b>원래 정의된 곳의 상수</b>에서 가져온다. 문자열을 여기 옮겨 적으면 한쪽만
     * 바뀌었을 때 그 파일이 조용히 백업에 담기는데, 담기면 특히 나쁜 것이 셋 있다.</p>
     * <ul>
     *   <li>{@code backups/} · {@code replaced/} - 월드가 몇 벌씩 들어 있다. 빠뜨리면 백업이
     *       자기를 삼키며 눈덩이처럼 불어난다.</li>
     *   <li>{@link PendingRestore#FILE_NAME} - 복원 <b>예약</b>이다. 백업에 담겨 복원으로
     *       되살아나면 그 다음 부팅이 또 복원을 시작한다.</li>
     *   <li>{@link RestoreApplier#FAILURE_PREFIX} 표식 - 이 파일이 있는 동안 자동 백업이 멈춘다.
     *       옛 표식이 되살아나면 아무도 손대지 않은 서버가 영구히 정지 상태로 들어간다.</li>
     * </ul>
     */
    private static void addOwnStateExclusions(List<String> excludes, Path serverRoot, Path dataFolder) {
        String base = FileUtil.relativize(serverRoot, dataFolder);
        if (base == null) return; // 서버 폴더 밖이면 애초에 백업되지 않는다

        for (String folder : List.of(DEFAULT_ARCHIVE_FOLDER, RestoreApplier.REPLACED_FOLDER)) {
            addPattern(excludes, base + "/" + folder);
            addPattern(excludes, base + "/" + folder + "/**");
        }
        for (String file : List.of(
                PendingRestore.FILE_NAME,
                PendingRestore.PROCESSING_NAME,
                PendingRestore.REPORT_NAME,
                // .yml 과 해제된 .yml.resolved 를 함께 잡는다
                RestoreApplier.FAILURE_PREFIX + "*",
                // 원자적 쓰기가 쓰다 만 조각
                "*" + Archiver.TEMP_SUFFIX)) {
            addPattern(excludes, base + "/" + file);
        }
    }

    private static void addPattern(List<String> excludes, String pattern) {
        if (pattern != null && !excludes.contains(pattern)) excludes.add(pattern);
    }

    /**
     * {@code targets.plugins} 를 읽는다. 알아듣지 못한 값은 담는 쪽으로 둔다.
     *
     * <p>오타 하나로 플러그인 데이터가 <b>조용히</b> 빠지는 것보다 예상보다 많이 담기는 편이
     * 낫다. 전자는 되돌려야 하는 날에야 드러나고, 후자는 백업 크기로 바로 보인다.</p>
     */
    private static Plugins readPlugins(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "none", "false", "off", "no", "없음", "안함" -> Plugins.NONE;
            case "data", "설정", "데이터" -> Plugins.DATA;
            default -> Plugins.ALL;
        };
    }

    /**
     * {@code plugins/*.jar}. 플러그인 폴더가 서버 폴더 밖이면 null.
     *
     * <p>이 패턴은 두 곳에 쓰이는데 이유가 서로 다르다.</p>
     * <ul>
     *   <li>{@code plugins: data} 일 때의 <b>백업 제외</b>. jar 없이 설정만 담고 싶은 경우다.</li>
     *   <li><b>복원 보존</b> - 이쪽은 설정과 무관하게 <b>언제나</b>다. 복원은 월드가 올라오기
     *       전({@code onLoad})에 도는데, 그 시점에는 서버가 이미 모든 플러그인 jar 를 열어
     *       둔 뒤다. 윈도우에서는 파일이 잠겨 실패하고, 리눅스에서는 성공하지만 이번 세션에는
     *       아무 효과가 없다 - 대신 <b>다음 재시작에 조용히 옛 버전으로 돌아간다.</b> 게다가
     *       사흘 전으로 되돌릴 때 원하는 것은 대개 "지금 코드 + 그때 데이터" 이지 사흘 전
     *       버전의 플러그인이 아니다. 아카이브에는 계속 담기므로(서버를 통째로 잃었을 때 쓸
     *       수 있게) 필요하면 zip 에서 직접 꺼내면 된다.</li>
     * </ul>
     *
     * <p>{@code plugins/} <b>바로 아래</b>만 잡는다. 플러그인이 자기 폴더 안에 두는 라이브러리
     * jar 는 그 플러그인의 데이터라서 함께 담고 함께 되돌려야 한다.</p>
     */
    private static String pluginJarPattern(Path serverRoot, Path pluginsDir) {
        if (pluginsDir == null) return null;
        String relative = FileUtil.relativize(serverRoot, pluginsDir);
        return relative == null ? null : relative + "/*.jar";
    }

    /**
     * GB 설정값을 바이트로. 음수는 0(제한 없음)으로 본다.
     *
     * <p>{@code public} 인 이유는 하나뿐이다 - 소수점이 조용히 0 으로 잘리면 관리자가 걸어 둔
     * 디스크 브레이크가 <b>없는 것과 같아진다.</b> 그 경계를 테스트로 못 박아 둔다.</p>
     */
    public static long gigabytesToBytes(double gigabytes) {
        if (!(gigabytes > 0)) return 0L; // 음수·0·NaN
        return (long) (gigabytes * 1024L * 1024L * 1024L);
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

        // 계단을 적었는데 <b>항목 형태가 아예 아닌</b> 경우.
        //
        // getMapList 는 map 이 아닌 항목을 조용히 버리므로 위 경고에도 걸리지 않는다
        // (raw 가 빈 목록이 되어 "계단을 안 쓴 것" 과 구별되지 않는다). 그러면 관리자는
        // 자는 동안의 1시간 해상도가 지켜지고 있다고 믿는데 실제로는 예전 정책이 돌고,
        // 그 사실은 정작 되돌려야 하는 날에야 드러난다.
        //
        // "tiers: []" 로 <b>일부러</b> 비운 것은 정상적인 사용법이므로 경고하지 않는다.
        if (raw.isEmpty()) {
            List<?> written = cfg.getList("retention.tiers");
            if (written != null && !written.isEmpty()) {
                tierWarnings.add("retention.tiers 에 " + written.size()
                        + "개가 적혀 있는데 계단으로 읽을 수 있는 항목이 없습니다. 예전 정책"
                        + "(max-backups/max-age-days/keep-daily)으로 동작합니다.");
                tierWarnings.add("  형식은 - { every: 1h, keep: 10 } 처럼 every 와 keep 을 가진"
                        + " 항목이어야 합니다. /wb status 에 실제로 적용된 정책이 표시됩니다.");
            }
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

    /**
     * 연속으로 이만큼 건너뛰었으면 변경이 없어 보여도 한 번은 백업한다. (0 = 하한 없음)
     *
     * <p>{@code skip-if-no-players} 의 "변경 없음" 판단은 블록 설치·파괴·접속만 본다.
     * 강제 로드된 청크의 농장이나 플러그인이 직접 쓰는 데이터는 잡히지 않으므로, 하한이
     * 없으면 무인 기간의 백업이 통째로 비어 버린다.</p>
     */
    public int maxSkippedCycles() { return maxSkippedCycles; }

    public int compressionLevel() { return compressionLevel; }

    /**
     * 예열한 쓰기가 디스크로 내려갈 시간을 얼마나 줄지. (0 = 예열하지 않음)
     *
     * <p>백업이 서버를 멈추는 이유는 청크를 직렬화하는 비용이 아니라 <b>큐가 빠지기를
     * 기다리는 것</b>이다. 워치독 스레드 덤프에 {@code MoonriseRegionFileIO.partialFlush}
     * 의 {@code linearLongBackoff} 로 찍히는 그 대기다. 그래서 기다리기 <b>전에</b> 쓰기를
     * 걸어 두고, 서버가 정상적으로 도는 동안 I/O 스레드가 그것을 내려쓰게 둔다. 그동안
     * 서버는 멈추지 않는다 - 이 시간은 백업 스레드가 잠들어 있을 뿐이다.</p>
     *
     * <p>그 뒤의 진짜 flush 는 그 사이에 새로 더러워진 청크만 기다리면 된다.</p>
     */
    public long flushSettleMillis() { return flushSettleMillis; }

    public boolean oneBackEnabled() { return oneBackEnabled; }

    /** OneBack 저장 폴더. 상대 경로는 <b>서버 폴더</b> 기준으로 이미 풀어 두었다. */
    public Path oneBackDir() { return oneBackDir; }

    public int oneBackKeep() { return oneBackKeep; }

    /** 0 이면 자동으로 만들지 않는다({@code /wb oneback} 으로만). */
    public int oneBackIntervalHours() { return oneBackIntervalHours; }

    public GlobMatcher oneBackExclude() { return oneBackExclude; }

    public boolean updateCheck() { return updateCheck; }

    /** true 면 새 버전을 찾았을 때 사람 확인 없이 내려받아 다음 재시작에 적용한다. */
    public boolean updateAutoDownload() { return updateAutoDownload; }

    public String updateRepository() { return updateRepository; }

    public Path backupDir() { return backupDir; }

    public boolean broadcast() { return broadcast; }

    public String broadcastPermission() { return broadcastPermission; }

    public List<String> serverFiles() { return serverFiles; }

    public List<String> extraPaths() { return extraPaths; }

    /**
     * 플러그인 폴더를 어디까지 담을지.
     *
     * <p>월드만 되돌리면 플러그인이 들고 있는 상태는 그대로 남아 월드와 어긋난다.
     * jar 는 담기더라도 복원이 덮어쓰지 않는다 - {@link #preservePatterns()}.</p>
     */
    public Plugins plugins() { return plugins; }

    /**
     * {@code plugins/} 폴더. 데이터 폴더의 부모라 {@code --plugins} 로 옮긴 서버에서도 맞다.
     *
     * <p>데이터 폴더가 파일시스템 루트에 바로 놓인 경우에만 null 이다.</p>
     */
    public Path pluginsDir() { return pluginsDir; }

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

    /**
     * 플러그인 자기 {@code config.yml} 의 절대 경로. 이것도 백업에 담는다.
     *
     * <p>담지 않으면 서버를 되돌려도 그 시점의 보관 정책은 되돌아오지 않는다 - 백업이 어떻게
     * 만들어졌는지가 백업 안에 없는 셈이다. 나머지 자기 폴더(아카이브·{@code replaced/}·예약
     * 파일·실패 표식)는 {@link #exclude()} 가 계속 막는다.</p>
     *
     * <p>원치 않으면 {@code targets.exclude} 에 이 경로를 넣으면 된다. 그때는 대상으로도
     * 잡히지 않는다.</p>
     */
    public Path ownConfigFile() { return ownConfigFile; }
}
