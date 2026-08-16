package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.util.FileUtil;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 백업 폴더를 읽고 쓰는 저장소. 메타데이터는 zip 옆의 .yml 파일에 둔다. */
public final class BackupRepository {

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

    private volatile List<BackupEntry> cachedList;
    private volatile long cachedAt;

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
        List<BackupEntry> cached = cachedList;
        if (cached != null && System.currentTimeMillis() - cachedAt < LIST_CACHE_MILLIS) {
            return cached;
        }
        List<BackupEntry> fresh = List.copyOf(scan());
        cachedList = fresh;
        cachedAt = System.currentTimeMillis();
        return fresh;
    }

    /** 저장소를 거친 변경 뒤에는 반드시 호출한다. */
    private void invalidate() {
        cachedList = null;
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

    public Optional<BackupEntry> read(Path archive) {
        String id = BackupEntry.idFromArchive(archive);
        if (id == null) return Optional.empty();

        Path metaFile = archive.getParent().resolve(BackupEntry.metaName(id));
        YamlConfiguration yaml = null;
        if (Files.isRegularFile(metaFile)) {
            yaml = YamlConfiguration.loadConfiguration(metaFile.toFile());
        } else {
            yaml = readMetaFromArchive(archive).orElse(null);
            if (yaml != null) {
                // 사이드카가 사라졌다면 복구해 둔다.
                try {
                    yaml.save(metaFile.toFile());
                } catch (IOException ignored) {
                }
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
        try {
            Files.writeString(entry.metaFile(), toYamlString(entry), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.log(Level.WARNING, "백업 메타데이터를 저장하지 못했습니다: " + entry.id(), e);
        }
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

        Set<String> keep = new HashSet<>();
        if (settings.keepDaily() > 0) {
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
        Map<String, List<BackupEntry>> dependentsByBase = new HashMap<>();
        Map<String, BackupEntry> byId = new HashMap<>();
        for (BackupEntry entry : all) {
            byId.put(entry.id(), entry);
            if (entry.isDifferential()) {
                dependentsByBase.computeIfAbsent(entry.baseId(), key -> new ArrayList<>()).add(entry);
            }
        }

        if (settings.maxAgeDays() > 0) {
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
                toDelete.add(autoProtected.get(i));
            }
        }

        // 차등 백업이 남아 있는 전체 백업은 지울 수 없다. 기준이 사라지면 그 차등 백업들이
        // 통째로 복원 불가능해지기 때문이다. 딸린 차등 백업이 모두 정리된 뒤에 함께 사라진다.
        Set<BackupEntry> heldBack = new LinkedHashSet<>();
        toDelete.removeIf(entry -> {
            if (blockingDependent(entry, dependentsByBase, toDelete) == null) return false;
            heldBack.add(entry);
            return true;
        });

        // 개수 상한은 기준 백업 보호까지 반영된 <b>뒤에</b> 채운다. 예전에는 상한을 먼저 계산하고
        // 나중에 기준 백업을 목록에서 빼는 바람에, 실제 삭제 수가 계산보다 적어 max-backups 를
        // 넘긴 채로 끝났다. 한 번 채울 때마다 기준이 풀릴 수 있으므로 변화가 없을 때까지 돈다.
        if (settings.maxBackups() > 0) {
            boolean added = true;
            while (added) {
                added = false;
                int remaining = all.size() - toDelete.size();
                // 오래된 것부터 지운다.
                for (int i = deletable.size() - 1; i >= 0 && remaining > settings.maxBackups(); i--) {
                    BackupEntry entry = deletable.get(i);
                    if (toDelete.contains(entry)) continue;
                    if (blockingDependent(entry, dependentsByBase, toDelete) != null) {
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

        // 최종 결과가 정해진 뒤에 한 번만 알린다. (위 루프는 같은 항목을 여러 번 검사한다)
        for (BackupEntry base : heldBack) {
            if (toDelete.contains(base)) continue;
            String holder = blockingDependent(base, dependentsByBase, toDelete);
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
     * 이번에 함께 지워지지 않는 차등 백업이 딸려 있으면 기준 백업을 남겨야 한다.
     *
     * <p>선택 과정에서 여러 번 불리므로 <b>로그를 남기지 않는다.</b> 안내는 결과가 확정된 뒤
     * {@link #prune(BackupSettings)} 에서 한 번만 출력한다.</p>
     *
     * @return 이 기준 백업을 붙잡고 있는 차등 백업의 id, 없으면 null
     */
    private String blockingDependent(BackupEntry entry,
                                     Map<String, List<BackupEntry>> dependentsByBase,
                                     Set<BackupEntry> toDelete) {
        if (entry.isDifferential()) return null;
        for (BackupEntry other : dependentsByBase.getOrDefault(entry.id(), List.of())) {
            if (!toDelete.contains(other)) return other.id();
        }
        return null;
    }

    /**
     * 필요한 공간을 확보하기 위해 오래된(보호되지 않은) 백업부터 삭제한다.
     *
     * @return 확보한 바이트 수
     */
    public long freeUpSpace(BackupSettings settings, long neededBytes) {
        if (neededBytes <= 0) return 0L;
        List<BackupEntry> all = list();
        long freed = 0L;
        for (int i = all.size() - 1; i >= 0 && freed < neededBytes; i--) {
            BackupEntry entry = all.get(i);
            if (isPinned(entry)) continue; // 진행 중인 차등 백업의 기준
            if (entry.protectedFrom(settings.protectManual())) continue;
            if (!entry.isDifferential() && !dependents(all, entry.id()).isEmpty()) continue;
            if (i == 0) break; // 최소 1개는 남긴다.
            long size = entry.archiveBytes();
            if (delete(entry)) {
                freed += size;
                log.warning("[백업] 디스크 공간 확보를 위해 오래된 백업을 삭제했습니다: " + entry.id());
            }
        }
        return freed;
    }
}
