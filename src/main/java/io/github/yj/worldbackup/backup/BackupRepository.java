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

    public BackupRepository(Path directory, Logger log) {
        this.directory = directory;
        this.log = log;
    }

    public Path directory() {
        return directory;
    }

    public void ensureDirectory() throws IOException {
        Files.createDirectories(directory);
    }

    /** 최신 백업이 앞에 오도록 정렬된 목록. */
    public List<BackupEntry> list() {
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

    /** 차등 백업의 기준으로 쓸 수 있는 가장 최근 전체 백업. */
    public Optional<BackupEntry> newestFullBackup() {
        return list().stream()
                .filter(BackupEntry::complete)
                .filter(entry -> !entry.isDifferential())
                .findFirst();
    }

    public long totalBytes() {
        return list().stream().mapToLong(BackupEntry::archiveBytes).sum();
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
                    archiveBytes, 0L, 0, List.of(), List.of(), List.of(), "unknown", locked, false, null));
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
                yaml.getString("base-id")
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
        return yaml.saveToString();
    }

    public void writeMeta(BackupEntry entry) {
        try {
            Files.writeString(entry.metaFile(), toYamlString(entry), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.log(Level.WARNING, "백업 메타데이터를 저장하지 못했습니다: " + entry.id(), e);
        }
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
            return false;
        }
        writeMeta(entry.withLocked(locked));
        return true;
    }

    public boolean delete(BackupEntry entry) {
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
            if (entry.protectedFrom(settings.protectManual())) continue;
            if (keep.contains(entry.id())) continue;
            deletable.add(entry);
        }

        List<BackupEntry> toDelete = new ArrayList<>();

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
                if (entry.explicitlyLocked()) continue;
                if (entry.protectedFrom(settings.protectManual())) autoProtected.add(entry);
            }
            for (int i = autoProtected.size() - 1; i >= settings.maxProtected(); i--) {
                BackupEntry entry = autoProtected.get(i);
                if (!toDelete.contains(entry)) toDelete.add(entry);
            }
        }

        // 차등 백업이 남아 있는 전체 백업은 지울 수 없다. 기준이 사라지면 그 차등 백업들이
        // 통째로 복원 불가능해지기 때문이다. 딸린 차등 백업이 모두 정리된 뒤에 함께 사라진다.
        List<BackupEntry> heldBack = new ArrayList<>();
        toDelete.removeIf(entry -> {
            if (blockingDependent(entry, all, toDelete) == null) return false;
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
                    if (blockingDependent(entry, all, toDelete) != null) {
                        if (!heldBack.contains(entry)) heldBack.add(entry);
                        continue;
                    }
                    toDelete.add(entry);
                    remaining--;
                    added = true;
                }
            }
        }

        // 최종 결과가 정해진 뒤에 한 번만 알린다. (위 루프는 같은 항목을 여러 번 검사한다)
        for (BackupEntry base : heldBack) {
            if (toDelete.contains(base)) continue;
            String holder = blockingDependent(base, all, toDelete);
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
     * 이번에 함께 지워지지 않는 차등 백업이 딸려 있으면 기준 백업을 남겨야 한다.
     *
     * <p>선택 과정에서 여러 번 불리므로 <b>로그를 남기지 않는다.</b> 안내는 결과가 확정된 뒤
     * {@link #prune(BackupSettings)} 에서 한 번만 출력한다.</p>
     *
     * @return 이 기준 백업을 붙잡고 있는 차등 백업의 id, 없으면 null
     */
    private String blockingDependent(BackupEntry entry, List<BackupEntry> all, List<BackupEntry> toDelete) {
        if (entry.isDifferential()) return null;
        for (BackupEntry other : dependents(all, entry.id())) {
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
