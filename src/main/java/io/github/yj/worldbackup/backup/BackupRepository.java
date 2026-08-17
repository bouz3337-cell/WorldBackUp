package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.util.FileUtil;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 백업 폴더를 읽고 쓰는 저장소. 메타데이터는 zip 옆의 .yml 파일에 둔다.
 *
 * <p>{@code final} 이 아닌 이유는 하나뿐이다 - 테스트가 {@link #prune(BackupSettings)} 이
 * 터지는 상황을 만들어 "압축이 끝난 백업을 뒷정리 실패로 지우지 않는다"를 검증한다.
 * 실서버에서 상속받아 쓰라는 뜻이 아니다.</p>
 */
public class BackupRepository {

    private final Path directory;
    private final Logger log;

    /**
     * 지금 진행 중인 백업이 기준으로 삼고 있는 전체 백업의 id.
     *
     * <p>차등 백업을 만드는 동안에는 그 기준이 사라지면 안 된다. 압축이 끝난 뒤에야 사이드카가
     * 생겨 {@link #dependents} 에 잡히므로, 그 사이는 딸린 차등본이 하나도 없는 것처럼 보여
     * 보관 정책이나 공간 확보 로직이 태연히 지워 버릴 수 있다. 그렇게 되면 방금 만든 차등본은
     * 태어나자마자 복원 불가가 된다. 백업은 한 번에 하나만 도므로 하나만 붙잡으면 충분하다.</p>
     */
    private volatile String pinnedId;

    /** {@link #list()} 캐시 유효 시간. 짧게 잡아 밖에서 폴더를 건드려도 금방 따라잡는다. */
    private static final long LIST_CACHE_MILLIS = 3_000L;

    /**
     * 저장소를 거친 변경이 일어날 때마다 올라가는 번호.
     *
     * <p>훑는 데는 시간이 걸리고 그 사이에 다른 스레드가 저장소를 바꿀 수 있다. 번호를
     * 스캔 <b>전후로</b> 견주어, 달라졌으면 그 결과를 캐시에 넣지 않는다. {@link #invalidate()}
     * 가 캐시를 비우는 것만으로는 부족하다 - 이미 돌고 있던 스캔이 뒤늦게 <b>변경 이전의</b>
     * 목록을 캐시에 써 넣으면, 그 옛 목록이 캐시 유효 시간 내내 통용된다.</p>
     */
    private final AtomicLong generation = new AtomicLong();

    /** @param generation 이 목록을 만들어 낸 스캔이 시작될 때의 {@link #generation} */
    private record Cached(List<BackupEntry> entries, long generation, long at) {
    }

    private volatile Cached cached;

    public BackupRepository(Path directory, Logger log) {
        this.directory = directory;
        this.log = log;
    }

    public Path directory() {
        return directory;
    }

    /** 이 백업을 삭제 대상에서 완전히 제외한다. (진행 중인 차등 백업의 기준) */
    public void pin(String id) {
        this.pinnedId = id;
    }

    public void unpin() {
        this.pinnedId = null;
    }

    public boolean isPinned(BackupEntry entry) {
        String pinned = pinnedId;
        return pinned != null && pinned.equals(entry.id());
    }

    public void ensureDirectory() throws IOException {
        Files.createDirectories(directory);
    }

    /**
     * 최신 백업이 앞에 오도록 정렬된 목록.
     *
     * <p>한 번 훑는 데 백업마다 파일 크기·보호 마커 조회와 YAML 파싱이 따르고, 사이드카가
     * 없으면 zip 까지 연다. 그런데 {@code /wb list}·{@code /wb status}·{@code /wb info}·
     * 탭 완성이 모두 <b>메인 스레드</b>에서 이걸 부르고, 명령 하나가 여러 번 부르기도 한다.
     * 짧은 캐시를 두어 그 반복을 걷어낸다. 저장소를 거치는 변경은 모두 캐시를 버리므로,
     * 뒤처질 수 있는 것은 플러그인 밖에서 백업 폴더를 직접 건드린 경우뿐이다.</p>
     */
    public List<BackupEntry> list() {
        long startedAt = generation.get();
        Cached snapshot = cached;
        if (snapshot != null && snapshot.generation() == startedAt
                && System.currentTimeMillis() - snapshot.at() < LIST_CACHE_MILLIS) {
            return snapshot.entries();
        }

        List<BackupEntry> fresh = List.copyOf(scan());
        // 훑는 동안 저장소가 바뀌었으면 캐시에 넣지 않는다. 이 목록은 이미 옛것이고,
        // 캐시에 앉히면 그 옛 상태가 유효 시간 내내 정답인 척한다. 부르는 쪽에는 그대로
        // 돌려준다 - 지금 할 수 있는 최선이고, 다음 호출이 곧 다시 훑는다.
        if (generation.get() == startedAt) {
            cached = new Cached(fresh, startedAt, System.currentTimeMillis());
        }
        return fresh;
    }

    /** 저장소를 거친 변경 뒤에는 반드시 호출한다. */
    private void invalidate() {
        generation.incrementAndGet();
        cached = null;
    }

    private List<BackupEntry> scan() {
        List<BackupEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(directory)) return entries;
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(BackupEntry.ARCHIVE_SUFFIX))
                    .filter(p -> p.getFileName().toString().startsWith(BackupEntry.ARCHIVE_PREFIX))
                    .forEach(p -> read(p).ifPresent(entries::add));
        } catch (IOException e) {
            log.log(Level.WARNING, "백업 폴더를 읽지 못했습니다: " + directory, e);
        }
        entries.sort(Comparator.comparing(BackupEntry::createdAt).reversed());

        // 기준 백업이 사라진 차등 백업은 혼자서는 복원할 수 없다. 손상으로 표시해 둔다.
        Set<String> present = new HashSet<>();
        for (BackupEntry entry : entries) {
            if (entry.complete()) present.add(entry.id());
        }
        entries.replaceAll(entry -> {
            if (!entry.complete() || !entry.isDifferential()) return entry;
            return present.contains(entry.baseId()) ? entry : entry.withComplete(false);
        });
        return entries;
    }

    /** 이 백업을 기준으로 삼는 차등 백업들. 전체 백업을 지울 때 함께 정리해야 한다. */
    public List<BackupEntry> dependents(List<BackupEntry> all, String baseId) {
        List<BackupEntry> found = new ArrayList<>();
        for (BackupEntry entry : all) {
            if (baseId.equals(entry.baseId())) found.add(entry);
        }
        return found;
    }

    /** 차등 백업의 기준이 되는 전체 백업. 없으면 비어 있다. */
    public Optional<BackupEntry> base(BackupEntry entry) {
        if (!entry.isDifferential()) return Optional.empty();
        return list().stream().filter(e -> e.id().equals(entry.baseId())).findFirst();
    }

    /**
     * id, 목록 번호(1부터), 또는 "latest" 로 백업을 찾는다.
     */
    public Optional<BackupEntry> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        List<BackupEntry> entries = list();
        String trimmed = token.trim();

        if (trimmed.equalsIgnoreCase("latest") || trimmed.equalsIgnoreCase("last")) {
            return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
        }
        if (trimmed.matches("#?\\d{1,3}")) {
            int index = Integer.parseInt(trimmed.replace("#", "")) - 1;
            if (index >= 0 && index < entries.size()) return Optional.of(entries.get(index));
            return Optional.empty();
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT)
                .replace(BackupEntry.ARCHIVE_PREFIX, "")
                .replace(BackupEntry.ARCHIVE_SUFFIX, "");
        return entries.stream().filter(e -> e.id().equalsIgnoreCase(normalized)).findFirst();
    }

    /**
     * 이 시각 <b>이전</b>의 가장 최근 백업.
     *
     * <p>"이전" 이라는 점이 중요하다. 테러가 난 시각을 말했는데 그 이후 백업을 골라 주면
     * 피해가 담긴 상태로 되돌리게 된다. 요청한 시각을 넘기느니 조금 더 과거로 가는 편이 안전하다.</p>
     */
    public Optional<BackupEntry> resolveAtOrBefore(Instant target) {
        return list().stream()
                .filter(BackupEntry::complete)
                .filter(entry -> !entry.createdAt().isAfter(target))
                .findFirst(); // 최신순이라 첫 항목이 가장 가까운 과거다
    }

    public Optional<BackupEntry> read(Path archive) {
        String id = BackupEntry.idFromArchive(archive);
        if (id == null) return Optional.empty();

        Path metaFile = archive.getParent().resolve(BackupEntry.metaName(id));
        YamlConfiguration yaml = null;
        if (Files.isRegularFile(metaFile)) {
            yaml = usable(YamlConfiguration.loadConfiguration(metaFile.toFile()));
        }
        if (yaml == null) {
            // 사이드카가 없거나 쓰다 만 것이다. 같은 내용이 zip 안에 한 벌 더 들어 있다.
            yaml = readMetaFromArchive(archive).map(BackupRepository::usable).orElse(null);
            if (yaml != null) {
                // 사이드카가 사라졌다면 복구해 둔다.
                writeAtomically(metaFile, yaml.saveToString(),
                        "백업 메타데이터를 복구하지 못했습니다: " + id);
            }
        }

        long archiveBytes;
        try {
            archiveBytes = Files.size(archive);
        } catch (IOException e) {
            archiveBytes = 0L;
        }

        // 사이드카와 zip 내부 메타가 둘 다 없다 = 압축이 끝나지 않았거나 zip 이 깨졌다.
        boolean locked = Files.exists(archive.getParent().resolve(BackupEntry.lockName(id)));
        if (yaml == null) {
            Instant created = parseIdInstant(id, archive);
            return Optional.of(new BackupEntry(id, archive, created, BackupType.SCHEDULED, null,
                    archiveBytes, 0L, 0, List.of(), List.of(), List.of(), "unknown", locked, false, null, null));
        }

        Instant created = yaml.contains("created-at")
                ? Instant.ofEpochMilli(yaml.getLong("created-at"))
                : parseIdInstant(id, archive);

        return Optional.of(new BackupEntry(
                id,
                archive,
                created,
                BackupType.parse(yaml.getString("type")),
                yaml.getString("label"),
                archiveBytes,
                yaml.getLong("original-bytes", 0L),
                yaml.getInt("file-count", 0),
                List.copyOf(yaml.getStringList("roots")),
                List.copyOf(yaml.getStringList("worlds")),
                List.copyOf(yaml.getStringList("excludes")),
                yaml.getString("server-version", "unknown"),
                locked || yaml.getBoolean("locked", false),
                true,
                yaml.getString("base-id"),
                // 이 키가 없으면 기록하지 않던 시절의 백업이다. false 가 아니라 "모름" 이다.
                yaml.contains("player-data") ? yaml.getBoolean("player-data") : null
        ));
    }

    /**
     * 이 메타데이터를 믿어도 되는지.
     *
     * <p>{@link YamlConfiguration#loadConfiguration(java.io.File)} 은 파일이 깨져 있어도
     * <b>예외를 던지지 않고 빈 설정</b>을 돌려준다. 그래서 쓰다 만 사이드카를 그대로 받아들이면
     * {@code roots} 와 {@code base-id} 가 통째로 비어 있는데도 정상 백업으로 잡힌다. 차등 백업이
     * 전체 백업으로 둔갑하고, 복원은 대상 경로를 좁히지 못한 채 "덮어쓰기만" 경로로 들어가
     * 바뀐 파일 몇 개를 살아 있는 월드 위에 흩뿌린다. zip 자체는 멀쩡하므로
     * {@code verify-archive} 도 이걸 잡지 못한다.</p>
     *
     * <p>그래서 <b>맨 끝에 쓰이는</b> 키가 있는지 본다. {@link #toYamlString} 은 삽입 순서대로
     * 쓰므로, 뒤쪽 키가 있으면 그 앞의 {@code roots} 까지는 온전히 쓰인 것이다. 앞쪽 키
     * ({@code id}, {@code created-at}) 로는 이 판단을 할 수 없다 - 그 둘은 첫 두 줄이라,
     * 정작 위험한 "roots 부터 잘린" 파일이 그대로 통과한다.</p>
     *
     * <p>파수꾼은 {@code locked} 다. 값이 null 이면 키 자체가 안 쓰이므로 {@code label} ·
     * {@code base-id} · {@code player-data} 는 쓸 수 없고(전체 백업은 {@code base-id} 가 없다),
     * {@code locked} 는 boolean 이라 언제나 쓰인다. 최초 버전부터 그랬으므로 예전 백업의 멀쩡한
     * 사이드카를 잘못 물리지도 않는다.</p>
     *
     * @return 쓸 수 있으면 그대로, 아니면 null
     */
    private static YamlConfiguration usable(YamlConfiguration yaml) {
        if (yaml == null) return null;
        return yaml.contains("id") && yaml.contains("locked") ? yaml : null;
    }

    /**
     * 파일 내용을 통째로 갈아 끼운다. <b>쓰다 만 조각이 남지 않는다.</b>
     *
     * <p>{@code Files.writeString} 은 원자적이지 않아서, 서버가 하필 그 사이에 죽으면 잘린
     * 파일이 남는다. 사이드카에 그 일이 벌어지면 {@link #usable} 이 막아 주기는 하지만,
     * 애초에 그런 파일이 생기지 않게 하는 편이 낫다. {@link Archiver} 가 아카이브에 대해
     * 지키는 규칙과 같다.</p>
     */
    private void writeAtomically(Path target, String text, String failureMessage) {
        Path temp = target.resolveSibling(target.getFileName() + Archiver.TEMP_SUFFIX);
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, failureMessage, e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    private Optional<YamlConfiguration> readMetaFromArchive(Path archive) {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(BackupEntry.META_ENTRY);
            if (entry == null) return Optional.empty();
            try (InputStream in = zip.getInputStream(entry)) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(text);
                return Optional.of(yaml);
            }
        } catch (IOException | InvalidConfigurationException e) {
            return Optional.empty();
        }
    }

    private Instant parseIdInstant(String id, Path archive) {
        try {
            return Instant.from(BackupEntry.ID_FORMAT.parse(id));
        } catch (Exception e) {
            try {
                return Files.getLastModifiedTime(archive).toInstant();
            } catch (IOException io) {
                return Instant.now();
            }
        }
    }

    public String toYamlString(BackupEntry entry) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", entry.id());
        yaml.set("created-at", entry.createdAt().toEpochMilli());
        yaml.set("created-at-text", entry.displayTime());
        yaml.set("type", entry.type().name());
        yaml.set("label", entry.label());
        yaml.set("original-bytes", entry.originalBytes());
        yaml.set("file-count", entry.fileCount());
        yaml.set("roots", new ArrayList<>(entry.roots()));
        yaml.set("worlds", new ArrayList<>(entry.worlds()));
        yaml.set("excludes", new ArrayList<>(entry.excludes()));
        yaml.set("server-version", entry.serverVersion());
        yaml.set("locked", entry.locked());
        yaml.set("base-id", entry.baseId());
        yaml.set("player-data", entry.playerData());
        return yaml.saveToString();
    }

    public void writeMeta(BackupEntry entry) {
        writeAtomically(entry.metaFile(), toYamlString(entry),
                "백업 메타데이터를 저장하지 못했습니다: " + entry.id());
        invalidate();
    }

    /**
     * 보호 상태를 저장한다. 사이드카가 사라져도 남도록 빈 마커 파일을 함께 쓴다.
     *
     * @return 성공 여부
     */
    public boolean setLocked(BackupEntry entry, boolean locked) {
        try {
            if (locked) {
                Files.writeString(entry.lockFile(), entry.id(), StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(entry.lockFile());
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "백업 보호 상태를 저장하지 못했습니다: " + entry.id(), e);
            invalidate(); // 마커가 일부만 남았을 수 있으니 다시 읽게 한다
            return false;
        }

        // 손상된 백업의 메타데이터는 파일 이름에서 <b>재구성한</b> 것이라 속이 비어 있다.
        // 그걸 사이드카로 적어 버리면 다음 목록부터 "메타데이터가 정상적으로 읽힌 백업"으로
        // 잡혀 [손상] 표시가 사라진다. 복원까지 허용되는데 정작 roots 도 base-id 도 없어서,
        // 관리자가 남겨 두려고 잠근 행동이 오히려 위험한 백업을 하나 만들어 낸다.
        // 마커 파일만으로 보호 상태는 충분히 남는다.
        if (!entry.complete()) {
            invalidate();
            return true;
        }
        writeMeta(entry.withLocked(locked)); // 여기서 캐시가 버려진다
        return true;
    }

    public boolean delete(BackupEntry entry) {
        // 마지막 방어선. 정책이든 수동이든, 지금 만들어지는 차등본의 기준은 지우지 않는다.
        if (isPinned(entry)) {
            log.warning("[백업] 진행 중인 차등 백업의 기준이라 삭제하지 않았습니다: " + entry.id());
            return false;
        }
        boolean ok = true;
        try {
            Files.deleteIfExists(entry.archive());
        } catch (IOException e) {
            log.log(Level.WARNING, "백업 파일을 삭제하지 못했습니다: " + entry.archive(), e);
            ok = false;
        }
        try {
            Files.deleteIfExists(entry.metaFile());
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(entry.lockFile());
        } catch (IOException ignored) {
        }
        invalidate();
        return ok;
    }

    /**
     * 서버가 압축 도중 죽으면서 남은 임시 파일과, 짝이 되는 zip 이 사라진 메타/마커를 지운다.
     *
     * <p>실행 중인 백업이 쓰고 있는 임시 파일까지 지우지 않도록 <b>서버 시작 시에만</b> 호출한다.</p>
     */
    public int cleanupOrphans() {
        if (!Files.isDirectory(directory)) return 0;
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            log.log(Level.WARNING, "백업 폴더를 읽지 못했습니다: " + directory, e);
            return 0;
        }

        int removed = 0;
        for (Path path : files) {
            String name = path.getFileName().toString();
            if (!name.startsWith(BackupEntry.ARCHIVE_PREFIX)) continue;

            boolean orphan;
            if (name.endsWith(Archiver.TEMP_SUFFIX)) {
                orphan = true; // 완료되지 못한 압축 조각
            } else if (name.endsWith(BackupEntry.META_SUFFIX) || name.endsWith(BackupEntry.LOCK_SUFFIX)) {
                String id = name.substring(BackupEntry.ARCHIVE_PREFIX.length(), name.lastIndexOf('.'));
                orphan = !Files.exists(directory.resolve(BackupEntry.archiveName(id)));
            } else {
                continue;
            }

            if (!orphan) continue;
            try {
                Files.delete(path);
                removed++;
                log.warning("[백업] 남아 있던 불완전한 파일을 정리했습니다: " + name);
            } catch (IOException ignored) {
            }
        }
        if (removed > 0) invalidate();
        return removed;
    }

    public record PruneResult(int deleted, long freedBytes, List<String> ids) {
    }

    /** 보관 정책에 따라 오래된 백업을 정리한다. */
    public PruneResult prune(BackupSettings settings) {
        List<BackupEntry> all = list(); // 최신순
        if (all.isEmpty()) return new PruneResult(0, 0L, List.of());

        boolean tiered = !settings.tiers().isEmpty();

        Set<String> keep = new HashSet<>();
        if (tiered) {
            // 계단이 남길 것을 이미 정한다. keep-daily 는 "그 날의 최신" 을 남기는 규칙이라
            // 새벽에 사고가 나면 피해가 담긴 백업만 남겨 놓는다. 계단이 그 자리를 대신한다.
            keep.addAll(RetentionTiers.select(all, settings.tiers(), Instant.now()));
        } else if (settings.keepDaily() > 0) {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            LocalDate limit = today.minusDays(settings.keepDaily() - 1L);
            Map<LocalDate, BackupEntry> newestPerDay = new HashMap<>();
            for (BackupEntry entry : all) {
                if (!entry.complete()) continue; // 손상된 백업을 "그 날의 대표"로 남기면 안 된다
                newestPerDay.putIfAbsent(entry.localDate(), entry); // 최신순이므로 첫 항목이 그 날의 최신
            }
            for (Map.Entry<LocalDate, BackupEntry> day : newestPerDay.entrySet()) {
                if (!day.getKey().isBefore(limit)) keep.add(day.getValue().id());
            }
        }

        List<BackupEntry> deletable = new ArrayList<>();
        for (BackupEntry entry : all) {
            if (isPinned(entry)) continue; // 진행 중인 차등 백업의 기준
            if (entry.protectedFrom(settings.protectManual())) continue;
            if (keep.contains(entry.id())) continue;
            deletable.add(entry);
        }

        // 아래 선택 과정은 "이미 지우기로 했나" 를 수없이 되묻는다. 선형 탐색하는 List 대신
        // 순서를 지키는 Set 을 쓴다. 기준-차등 관계도 매번 전체를 훑는 대신 한 번만 모아 둔다.
        Set<BackupEntry> toDelete = new LinkedHashSet<>();
        Map<String, List<BackupEntry>> dependentsByBase = dependentsByBase(all);
        Map<String, BackupEntry> byId = new HashMap<>();
        for (BackupEntry entry : all) {
            byId.put(entry.id(), entry);
        }

        if (tiered) {
            // 어떤 계단에도 들지 못한 것은 전부 정리 대상이다.
            toDelete.addAll(deletable);
        } else if (settings.maxAgeDays() > 0) {
            Instant cutoff = Instant.now().minusSeconds(settings.maxAgeDays() * 86400L);
            for (BackupEntry entry : deletable) {
                if (entry.createdAt().isBefore(cutoff)) toDelete.add(entry);
            }
        }

        // 보호된 백업(수동/복원 직전)도 무한정 쌓이면 디스크가 찬다.
        // /wb lock 으로 직접 잠근 것만 상한에서 제외한다.
        if (settings.maxProtected() > 0) {
            List<BackupEntry> autoProtected = new ArrayList<>();
            for (BackupEntry entry : all) {
                if (entry.explicitlyLocked() || isPinned(entry)) continue;
                if (entry.protectedFrom(settings.protectManual())) autoProtected.add(entry);
            }
            for (int i = autoProtected.size() - 1; i >= settings.maxProtected(); i--) {
                BackupEntry entry = autoProtected.get(i);
                // 보관 정책이 남기기로 한 자리는 상한보다 우선한다. 계단(또는 keep-daily)은
                // "이 시간대에 되돌릴 지점이 하나 있다" 는 약속인데, 그 자리를 채운 백업이
                // 하필 수동 백업이면 상한이 오래된 순으로 지우면서 그 시간대를 통째로 비웠다.
                // 그런데 /wb status 는 그동안 계속 "계단식 N단계" 를 보여 주므로, 비었다는
                // 사실은 정작 되돌려야 하는 날에야 드러난다.
                //
                // 세는 것은 <b>전부</b> 센다. 여기서 빼 버리면 상한이 "계단 밖 보호 백업의
                // 개수" 라는 다른 뜻이 되어, 계단 안에 보호 백업이 있을 때 상한을 넘겨도
                // 아무것도 지우지 않게 된다. 계단 밖은 그대로 걸리므로 무한정 쌓이지 않는다.
                if (keep.contains(entry.id())) continue;
                toDelete.add(entry);
            }
        }

        // 차등 백업이 남아 있는 전체 백업은 지울 수 없다. 기준이 사라지면 그 차등 백업들이
        // 통째로 복원 불가능해지기 때문이다. 딸린 차등 백업이 모두 정리된 뒤에 함께 사라진다.
        Set<BackupEntry> heldBack = new LinkedHashSet<>();
        toDelete.removeIf(entry -> {
            if (blockingDependent(entry, dependentsByBase, toDelete::contains) == null) return false;
            heldBack.add(entry);
            return true;
        });

        // 개수 상한은 기준 백업 보호까지 반영된 <b>뒤에</b> 채운다. 예전에는 상한을 먼저 계산하고
        // 나중에 기준 백업을 목록에서 빼는 바람에, 실제 삭제 수가 계산보다 적어 max-backups 를
        // 넘긴 채로 끝났다. 한 번 채울 때마다 기준이 풀릴 수 있으므로 변화가 없을 때까지 돈다.
        if (!tiered && settings.maxBackups() > 0) {
            boolean added = true;
            while (added) {
                added = false;
                int remaining = all.size() - toDelete.size();
                // 오래된 것부터 지운다.
                for (int i = deletable.size() - 1; i >= 0 && remaining > settings.maxBackups(); i--) {
                    BackupEntry entry = deletable.get(i);
                    if (toDelete.contains(entry)) continue;
                    if (blockingDependent(entry, dependentsByBase, toDelete::contains) != null) {
                        heldBack.add(entry);
                        continue;
                    }
                    toDelete.add(entry);
                    remaining--;
                    added = true;
                }
            }
        }

        rescueToMinimum(settings, all, byId, toDelete);
        enforceTotalSize(settings, all, dependentsByBase, toDelete);

        // 최종 결과가 정해진 뒤에 한 번만 알린다. (위 루프는 같은 항목을 여러 번 검사한다)
        for (BackupEntry base : heldBack) {
            if (toDelete.contains(base)) continue;
            String holder = blockingDependent(base, dependentsByBase, toDelete::contains);
            if (holder != null) {
                log.info("[백업] 차등 백업 " + holder + " 의 기준이라 " + base.id() + " 는 남겨 둡니다.");
            }
        }

        long freed = 0L;
        List<String> ids = new ArrayList<>();
        for (BackupEntry entry : toDelete) {
            if (delete(entry)) {
                freed += entry.archiveBytes();
                ids.add(entry.id());
            }
        }
        if (!ids.isEmpty()) {
            log.info("[백업] 보관 정책에 따라 " + ids.size() + "개 백업을 삭제했습니다 ("
                    + FileUtil.humanBytes(freed) + " 확보)");
        }
        return new PruneResult(ids.size(), freed, ids);
    }

    /**
     * 삭제 목록에서 최신 백업부터 되살려 최소 보관 개수를 채운다.
     *
     * <p>모든 정책을 통과한 <b>마지막 안전망</b>이다. 접속자가 없는 서버는 백업을 건너뛰기만
     * 하는데 보관 정리는 계속 돌기 때문에, {@code max-age-days} 하나로 백업이 전멸할 수 있다.
     * {@code keep-daily} 는 "최근 N일 안에 만들어진 백업"만 지키므로 그 기간에 백업이 없으면
     * 아무도 지키지 못하고, 자동 백업은 {@code protect-manual} 대상도 아니다. 그렇게 백업이
     * 0개가 된 서버는 다음 접속자가 테러를 해도 되돌릴 곳이 없다.</p>
     *
     * <p>손상된 백업은 되살려도 복원에 못 쓰므로 세지 않는다. 차등본을 되살릴 때는 기준 백업도
     * 함께 되살린다. 그러지 않으면 되살린 차등본이 곧바로 복원 불가가 된다.</p>
     */
    private void rescueToMinimum(BackupSettings settings,
                                 List<BackupEntry> all,
                                 Map<String, BackupEntry> byId,
                                 Set<BackupEntry> toDelete) {
        if (settings.minBackups() <= 0 || toDelete.isEmpty()) return;

        int surviving = 0;
        for (BackupEntry entry : all) {
            if (entry.complete() && !toDelete.contains(entry)) surviving++;
        }

        List<String> rescued = new ArrayList<>();
        for (BackupEntry entry : all) { // 최신순 - 남길 가치가 큰 것부터
            if (surviving >= settings.minBackups()) break;
            if (!entry.complete() || !toDelete.contains(entry)) continue;

            if (entry.isDifferential()) {
                BackupEntry base = byId.get(entry.baseId());
                if (base == null || !base.complete()) continue; // 기준이 없으면 살려도 소용없다
                if (toDelete.remove(base)) {
                    surviving++;
                    rescued.add(base.id());
                }
            }
            toDelete.remove(entry);
            surviving++;
            rescued.add(entry.id());
        }

        if (!rescued.isEmpty()) {
            log.info("[백업] 최소 보관 개수(" + settings.minBackups() + "개)를 지키기 위해 "
                    + rescued.size() + "개를 남깁니다: " + String.join(", ", rescued));
        }
    }

    /**
     * 백업 폴더 전체 크기를 상한 아래로 되돌린다.
     *
     * <p>{@code min-free-disk-gb} 는 파일시스템에 남은 공간을 묻는데, 컨테이너 호스팅에서는
     * 그 값이 호스트 디스크를 가리켜 정작 걸려 있는 할당량을 못 본다. 여기서는 백업 크기를
     * 직접 재므로 그런 환경에서도 동작하고, 계단 설정을 잘못 잡아 예상보다 많이 쌓이는
     * 경우에도 마지막 방어선이 된다.</p>
     *
     * <p>다만 {@code /wb lock} 과 최소 보관 개수는 이 상한보다 <b>우선한다.</b> 용량을 맞추려다
     * 되돌릴 백업을 하나도 안 남기는 것이 더 나쁜 결과이기 때문이다. 그래서 상한을 못 맞추면
     * 조용히 넘어가지 않고 알린다.</p>
     */
    private void enforceTotalSize(BackupSettings settings,
                                  List<BackupEntry> all,
                                  Map<String, List<BackupEntry>> dependentsByBase,
                                  Set<BackupEntry> toDelete) {
        if (settings.maxTotalBytes() <= 0) return;

        List<BackupEntry> survivors = new ArrayList<>();
        long total = 0L;
        for (BackupEntry entry : all) {
            if (toDelete.contains(entry)) continue;
            survivors.add(entry); // 최신순
            total += entry.archiveBytes();
        }
        if (total <= settings.maxTotalBytes()) return;

        // 남길 것을 먼저 확정한다. 이걸 "지우다 보니 남은 것" 으로 두면, 오래된 기준 백업을
        // 건너뛰는 사이에 정작 최신 백업이 먼저 지워진다. 최소 보관 개수는 <b>최신</b> 쪽을
        // 지켜야 의미가 있다.
        Set<BackupEntry> mustKeep = new HashSet<>();
        int kept = 0;

        // 1차: 보호·고정된 것은 어차피 남는다. 최소 개수에도 <b>포함해서</b> 센다
        // (rescueToMinimum 이 그렇게 세므로 두 곳의 기준이 같아야 한다).
        // 이걸 먼저 다 세어야 한다. 한 번에 훑으면 잠근 백업이 목록 뒤쪽(오래된 쪽)에 있을 때
        // 그걸 만나기도 전에 최신 백업이 최소 개수 몫으로 확보되어, 결국 하나를 더 남기게 된다.
        for (BackupEntry entry : survivors) {
            if (isPinned(entry) || entry.protectedFrom(settings.protectManual())) {
                mustKeep.add(entry);
                if (entry.complete()) kept++;
            }
        }

        // 2차: 모자란 만큼만 최신 쪽에서 채운다.
        // 손상된 백업은 복원에 못 쓰므로 세지 않는다. 용량만 차지하니 먼저 나간다.
        for (BackupEntry entry : survivors) { // 최신순
            if (kept >= settings.minBackups()) break;
            if (mustKeep.contains(entry) || !entry.complete()) continue;
            mustKeep.add(entry);
            kept++;
        }

        long before = total;
        List<String> removed = new ArrayList<>();

        // 한 번만 훑으면 상한을 못 맞출 수 있다. 가장 오래된 것이 차등본을 거느린 기준 백업이면
        // (= 대개 가장 큰 파일이면) 그때는 못 지우고 지나가는데, 뒤이어 그 차등본들이 지워지면서
        // 비로소 지울 수 있게 된다. 변화가 없을 때까지 돌아야 "이 값을 넘지 않는다"가 보장된다.
        boolean removedAny = true;
        while (removedAny && total > settings.maxTotalBytes()) {
            removedAny = false;
            for (int i = survivors.size() - 1; i >= 0 && total > settings.maxTotalBytes(); i--) {
                BackupEntry entry = survivors.get(i); // 오래된 것부터
                if (toDelete.contains(entry) || mustKeep.contains(entry)) continue;
                if (blockingDependent(entry, dependentsByBase, toDelete::contains) != null) continue;

                toDelete.add(entry);
                total -= entry.archiveBytes();
                removed.add(entry.id());
                removedAny = true;
            }
        }

        if (!removed.isEmpty()) {
            log.warning("[백업] 총 용량 상한(" + FileUtil.humanBytes(settings.maxTotalBytes()) + ")을 넘어("
                    + FileUtil.humanBytes(before) + ") 오래된 백업 " + removed.size() + "개를 더 정리합니다: "
                    + String.join(", ", removed));
        }
        if (total > settings.maxTotalBytes()) {
            log.severe("[백업] 총 용량이 상한을 넘었지만 더 지울 수 있는 백업이 없습니다 ("
                    + FileUtil.humanBytes(total) + " > " + FileUtil.humanBytes(settings.maxTotalBytes())
                    + "). 보호된 백업을 풀거나 상한을 올리세요.");
        }
    }

    /** 기준 id → 그 기준을 삼는 차등 백업들. 매번 전체를 훑지 않도록 한 번만 모아 둔다. */
    private static Map<String, List<BackupEntry>> dependentsByBase(List<BackupEntry> all) {
        Map<String, List<BackupEntry>> byBase = new HashMap<>();
        for (BackupEntry entry : all) {
            if (entry.isDifferential()) {
                byBase.computeIfAbsent(entry.baseId(), key -> new ArrayList<>()).add(entry);
            }
        }
        return byBase;
    }

    /**
     * 이번에 함께 지워지지 않는 차등 백업이 딸려 있으면 기준 백업을 남겨야 한다.
     *
     * <p>이 판단은 {@link #prune(BackupSettings)} 과 {@link #freeUpSpace} 양쪽에서 쓰이는데,
     * 한때는 두 곳이 <b>각자 구현</b>을 갖고 있었다. 지금은 답이 같지만 이 코드가 지키려는 것이
     * "기준이 사라져 차등본이 통째로 복원 불가가 되는 것" 이므로, 한쪽만 고쳐진 채 갈라지면
     * 대가가 크다. 지우려는 후보를 어떻게 담아 두는지만 호출자마다 다르므로 그것만 받는다.</p>
     *
     * <p>선택 과정에서 여러 번 불리므로 <b>로그를 남기지 않는다.</b> 안내는 결과가 확정된 뒤
     * {@link #prune(BackupSettings)} 에서 한 번만 출력한다.</p>
     *
     * @param removed 이번 회차에 이미 지우기로 정해진 백업인지 답해 준다. 훑기 시작할 때의
     *                목록만 보면 그 판단을 못 해서 기준이 영원히 남는다.
     * @return 이 기준 백업을 붙잡고 있는 차등 백업의 id, 없으면 null
     */
    private static String blockingDependent(BackupEntry entry,
                                            Map<String, List<BackupEntry>> dependentsByBase,
                                            Predicate<BackupEntry> removed) {
        if (entry.isDifferential()) return null; // 차등본은 기준이 될 수 없다
        for (BackupEntry other : dependentsByBase.getOrDefault(entry.id(), List.of())) {
            if (!removed.test(other)) return other.id();
        }
        return null;
    }

    /**
     * 필요한 공간을 확보하기 위해 오래된(보호되지 않은) 백업부터 삭제한다.
     *
     * <p>{@code min-backups} 는 <b>여기서도</b> 지킨다. 예전에는 "최소 1개" 만 남겼는데, 그러면
     * 디스크가 빠듯한 서버에서 백업 한 번이 하한선을 1개까지 깎아 낼 수 있었다. 하필 그 상황
     * (공간 부족)이 되돌릴 백업이 가장 필요한 상황이라 실패 방향이 최악이다. 설정과 문서가
     * "어떤 정책으로도 이 개수 아래로 줄이지 않는다" 고 약속하는데 이 경로만 빠져 있었다.</p>
     *
     * <p>{@link #rescueToMinimum} 과 <b>같은 기준</b>으로 센다 - 손상된 백업은 복원에 못 쓰므로
     * 세지 않고, 용량만 차지하니 먼저 나간다. 두 곳의 기준이 어긋나면 한쪽이 지킨 개수를
     * 다른 쪽이 다시 깎는다.</p>
     *
     * @return 확보한 바이트 수
     */
    public long freeUpSpace(BackupSettings settings, long neededBytes) {
        if (neededBytes <= 0) return 0L;
        List<BackupEntry> all = list();

        int surviving = 0;
        for (BackupEntry entry : all) {
            if (entry.complete()) surviving++;
        }
        int remaining = all.size();
        int floor = Math.max(1, settings.minBackups());

        Set<String> deleted = new HashSet<>();
        Map<String, List<BackupEntry>> dependentsByBase = dependentsByBase(all);
        long freed = 0L;

        // 한 번만 훑으면 상한을 크게 못 맞춘다. 기준 백업은 대개 딸린 차등본보다 오래되어
        // 먼저 만나는데, 그때는 차등본이 아직 살아 있어 건너뛴다. 그 뒤에 차등본이 지워져도
        // 기준을 다시 볼 기회가 없다. 정작 기준이 가장 큰 파일인 경우가 많다.
        // enforceTotalSize 가 같은 이유로 그렇게 하듯, 변화가 없을 때까지 돈다.
        boolean deletedAny = true;
        while (deletedAny && freed < neededBytes) {
            deletedAny = false;
            for (int i = all.size() - 1; i >= 0 && freed < neededBytes; i--) {
                BackupEntry entry = all.get(i);
                if (deleted.contains(entry.id())) continue;
                if (isPinned(entry)) continue; // 진행 중인 차등 백업의 기준
                if (entry.protectedFrom(settings.protectManual())) continue;
                // 이번 회차에 이미 지운 것을 빼고 본다. prune 과 같은 판단을 쓴다.
                if (blockingDependent(entry, dependentsByBase, other -> deleted.contains(other.id())) != null) {
                    continue;
                }
                // 손상된 백업은 하한선에 세지 않으므로 이 뒤로도 계속 지울 수 있다. break 가 아니다.
                if (entry.complete() && surviving <= floor) continue;
                // 다만 마지막 하나는 손상 여부와 무관하게 남긴다. "손상" 판정에는 "사이드카와 zip
                // 메타를 둘 다 못 읽었다" 도 포함되어서, 일시적인 I/O 오류로 멀쩡한 백업이 그렇게
                // 잡힐 수 있다. 그 판정 하나를 믿고 백업 폴더를 비우는 것은 너무 나간 것이다.
                if (remaining <= 1) continue;
                long size = entry.archiveBytes();
                if (delete(entry)) {
                    deleted.add(entry.id());
                    freed += size;
                    remaining--;
                    if (entry.complete()) surviving--;
                    deletedAny = true;
                    log.warning("[백업] 디스크 공간 확보를 위해 오래된 백업을 삭제했습니다: " + entry.id());
                }
            }
        }
        if (freed < neededBytes && !all.isEmpty()) {
            // 원인을 하나로 단정하지 않는다. 지우지 못한 이유는 셋 다일 수 있고,
            // 틀린 진단을 로그에 남기면 관리자가 엉뚱한 곳을 뒤진다.
            log.warning("[백업] 더 지울 수 있는 백업이 없어 " + FileUtil.humanBytes(neededBytes)
                    + " 중 " + FileUtil.humanBytes(freed) + " 만 확보했습니다. (최소 보관 "
                    + floor + "개 · /wb lock 으로 잠근 백업 · 차등본이 딸린 기준 백업은 지우지 않습니다)");
        }
        return freed;
    }
}
