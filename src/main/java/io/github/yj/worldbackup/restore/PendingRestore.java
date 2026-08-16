package io.github.yj.worldbackup.restore;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 다음 서버 시작 시 처리할 복원 예약 정보.
 * 서버 실행 중에는 월드 파일이 잠겨 있으므로, 예약 → 재시작 → 월드 로드 전 적용 순서로 동작한다.
 */
/**
 * @param baseArchive 차등 백업이면 기준이 되는 전체 백업의 경로, 전체 백업이면 null.
 */
public record PendingRestore(
        String id,
        Path archive,
        Path baseArchive,
        String requestedBy,
        long requestedAt,
        boolean keepReplaced,
        boolean verifyArchive,
        List<String> preserve,
        List<String> roots
) {

    public static final String FILE_NAME = "pending-restore.yml";
    public static final String PROCESSING_NAME = "pending-restore.processing.yml";
    public static final String REPORT_NAME = "last-restore.yml";

    public static Path file(Path dataFolder) {
        return dataFolder.resolve(FILE_NAME);
    }

    public static Path processingFile(Path dataFolder) {
        return dataFolder.resolve(PROCESSING_NAME);
    }

    public static boolean exists(Path dataFolder) {
        return Files.isRegularFile(file(dataFolder));
    }

    public void write(Path dataFolder) throws IOException {
        Files.createDirectories(dataFolder);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", id);
        yaml.set("archive", archive.toAbsolutePath().toString());
        yaml.set("base-archive", baseArchive == null ? null : baseArchive.toAbsolutePath().toString());
        yaml.set("requested-by", requestedBy);
        yaml.set("requested-at", requestedAt);
        yaml.set("keep-replaced", keepReplaced);
        yaml.set("verify-archive", verifyArchive);
        yaml.set("preserve", new ArrayList<>(preserve));
        yaml.set("roots", new ArrayList<>(roots));
        Files.writeString(file(dataFolder), yaml.saveToString(), StandardCharsets.UTF_8);
    }

    public static Optional<PendingRestore> read(Path path) {
        if (!Files.isRegularFile(path)) return Optional.empty();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
        String id = yaml.getString("id");
        String archive = yaml.getString("archive");
        if (id == null || archive == null) return Optional.empty();
        String baseArchive = yaml.getString("base-archive");
        return Optional.of(new PendingRestore(
                id,
                Paths.get(archive),
                baseArchive == null || baseArchive.isBlank() ? null : Paths.get(baseArchive),
                yaml.getString("requested-by", "unknown"),
                yaml.getLong("requested-at", 0L),
                yaml.getBoolean("keep-replaced", true),
                yaml.getBoolean("verify-archive", true),
                List.copyOf(yaml.getStringList("preserve")),
                List.copyOf(yaml.getStringList("roots"))
        ));
    }

    public static void clear(Path dataFolder) {
        try {
            Files.deleteIfExists(file(dataFolder));
        } catch (IOException ignored) {
        }
    }
}
