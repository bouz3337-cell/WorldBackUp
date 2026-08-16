package io.github.yj.worldbackup.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 백업 시점에 존재하던 <b>모든</b> 파일의 목록. 차등 백업의 기준이 된다.
 *
 * <p>차등 백업의 zip 에는 바뀐 파일만 들어가지만, 매니페스트에는 그 시점 스냅샷의 전체 파일이
 * 담긴다. 복원할 때 이 목록이 "최종 상태의 정답"이 되어,</p>
 * <ul>
 *   <li>차등 zip 에 있는 파일 → 차등 zip 에서 꺼내고,</li>
 *   <li>없는 파일 → 기준(전체) 백업에서 꺼내고,</li>
 *   <li>목록에 없는 파일 → 그 사이 삭제된 파일이므로 복원하지 않는다.</li>
 * </ul>
 *
 * <p>형식은 한 줄에 파일 하나인 텍스트다. 경로에 공백이 들어갈 수 있으므로 맨 뒤에 둔다.
 * {@code <크기> <수정시각millis> <상대경로>}</p>
 */
public final class Manifest {

    /** zip 내부에 함께 저장되는 매니페스트 엔트리 이름. */
    public static final String ENTRY = "worldbackup-files.txt";

    /** {@link #writeTo(OutputStream)} 이 한 번에 흘려보내는 문자 수. */
    private static final int CHUNK_CHARS = 8 * 1024;

    /** 파일이 바뀌었는지 판단하는 기준. 크기와 수정 시각이 모두 같으면 같은 파일로 본다. */
    public record Stamp(long size, long modified) {
    }

    private final Map<String, Stamp> files;

    public Manifest(Map<String, Stamp> files) {
        this.files = files;
    }

    public static Manifest empty() {
        return new Manifest(new LinkedHashMap<>());
    }

    public int size() {
        return files.size();
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    public Set<String> paths() {
        return Collections.unmodifiableSet(files.keySet());
    }

    public boolean contains(String path) {
        return files.containsKey(path);
    }

    public void put(String path, long size, long modified) {
        files.put(path, new Stamp(size, modified));
    }

    /** 기준 백업 이후 이 파일이 그대로인지. 모르는 경로면 "바뀐 것"으로 본다. */
    public boolean unchanged(String path, long size, long modified) {
        Stamp stamp = files.get(path);
        return stamp != null && stamp.size() == size && stamp.modified() == modified;
    }

    /**
     * 매니페스트를 스트림에 직접 쓴다.
     *
     * <p>전체를 String 하나로 만들면 파일이 수십만 개인 월드에서 수십 MB 짜리 문자열이
     * 순간적으로 잡힌다. 8KB 씩 흘려보내 그 봉우리를 없앤다. 스트림은 닫지 않는다.</p>
     */
    public void writeTo(OutputStream out) throws IOException {
        StringBuilder chunk = new StringBuilder(CHUNK_CHARS + 256);
        for (Map.Entry<String, Stamp> entry : files.entrySet()) {
            chunk.append(entry.getValue().size()).append(' ')
                    .append(entry.getValue().modified()).append(' ')
                    .append(entry.getKey()).append('\n');
            if (chunk.length() >= CHUNK_CHARS) {
                out.write(chunk.toString().getBytes(StandardCharsets.UTF_8));
                chunk.setLength(0);
            }
        }
        if (chunk.length() > 0) {
            out.write(chunk.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static Manifest parse(String text) {
        Map<String, Stamp> parsed = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            int first = line.indexOf(' ');
            if (first <= 0) continue;
            int second = line.indexOf(' ', first + 1);
            if (second <= first) continue;
            try {
                long size = Long.parseLong(line, 0, first, 10);
                long modified = Long.parseLong(line, first + 1, second, 10);
                parsed.put(line.substring(second + 1), new Stamp(size, modified));
            } catch (NumberFormatException ignored) {
                // 깨진 줄은 건너뛴다. 그 파일은 "바뀐 것"으로 취급되어 다시 저장될 뿐이다.
            }
        }
        return new Manifest(parsed);
    }

    /** 아카이브 안에 들어 있는 매니페스트를 읽는다. 없으면(옛 버전 백업) 빈 결과. */
    public static Optional<Manifest> readFrom(Path archive) {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(ENTRY);
            if (entry == null) return Optional.empty();
            try (InputStream in = zip.getInputStream(entry)) {
                return Optional.of(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
