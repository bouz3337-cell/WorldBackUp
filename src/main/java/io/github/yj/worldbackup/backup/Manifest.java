package io.github.yj.worldbackup.backup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /**
     * 목록에는 올랐지만 이 아카이브가 <b>바이트를 담지 못한</b> 파일의 크기 자리표.
     *
     * <p>실제 파일 크기는 음수가 될 수 없으므로 {@link #unchanged} 가 이 값과 맞아떨어지는 일은
     * 없다. 즉 다음 차등 백업은 이 파일을 반드시 새로 담는다. 이 목록을 읽는 옛 버전도 같은
     * 이유로 안전하게 동작한다.</p>
     */
    private static final long NOT_STORED = -1L;

    private final Map<String, Stamp> files;

    public Manifest(Map<String, Stamp> files) {
        this.files = files;
    }

    public static Manifest empty() {
        return new Manifest(new LinkedHashMap<>());
    }

    /** 백업 시점에 <b>존재했던</b> 모든 파일. 담지 못한 파일도 들어 있다. */
    public Set<String> paths() {
        return Collections.unmodifiableSet(files.keySet());
    }

    /**
     * 이 아카이브에서 <b>내용을 꺼낼 수 있는</b> 파일들.
     *
     * <p>차등 백업이면 여기에는 "기준에서 재사용한 파일" 도 들어 있다. 이 아카이브가 직접
     * 갖고 있다는 뜻이 아니라, 기준까지 함께 풀면 온전한 내용을 얻는다는 뜻이다.</p>
     */
    public Set<String> storedPaths() {
        Set<String> stored = new LinkedHashSet<>();
        for (Map.Entry<String, Stamp> entry : files.entrySet()) {
            if (entry.getValue().size() != NOT_STORED) stored.add(entry.getKey());
        }
        return stored;
    }

    public boolean contains(String path) {
        return files.containsKey(path);
    }

    /** 이 파일의 온전한 내용을 이 백업으로 되돌릴 수 있는지. */
    public boolean stored(String path) {
        Stamp stamp = files.get(path);
        return stamp != null && stamp.size() != NOT_STORED;
    }

    public void put(String path, long size, long modified) {
        files.put(path, new Stamp(size, modified));
    }

    /**
     * 스냅샷에는 있었지만 담지 못한 파일로 올린다.
     *
     * <p>목록에서 아예 빼면 안 된다. 차등 복원이 이 파일을 "기준 이후 삭제됐다" 로 보고
     * 지나쳐서, 기준 백업에 남아 있는 <b>예전 판까지</b> 잃기 때문이다. 여기 올려 두면
     * 그 예전 판으로 되돌아간다 - 깨진 파일이나 없는 파일보다 낫다.</p>
     */
    public void putUnstored(String path) {
        files.put(path, new Stamp(NOT_STORED, 0L));
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

    /**
     * 한 줄을 해석해 넣는다. 형식이 깨진 줄은 조용히 건너뛴다.
     * 그 파일은 "바뀐 것"으로 취급되어 다시 저장될 뿐이라 안전한 쪽으로 기운다.
     */
    private static void parseLine(String line, Map<String, Stamp> into) {
        if (line.isBlank()) return;
        int first = line.indexOf(' ');
        if (first <= 0) return;
        int second = line.indexOf(' ', first + 1);
        if (second <= first) return;
        try {
            long size = Long.parseLong(line, 0, first, 10);
            long modified = Long.parseLong(line, first + 1, second, 10);
            into.put(line.substring(second + 1), new Stamp(size, modified));
        } catch (NumberFormatException ignored) {
        }
    }

    /**
     * 아카이브 안에 들어 있는 매니페스트를 읽는다. 없으면(옛 버전 백업) 빈 결과.
     *
     * <p>{@link #writeTo(OutputStream)} 과 마찬가지로 한 줄씩 흘려 읽는다. 통째로 문자열에
     * 담으면 파일이 수십만 개인 월드에서 수십 MB 짜리 문자열과 그것을 쪼갠 배열이 동시에
     * 잡히는데, 차등 백업은 <b>매 주기</b> 이 메서드를 부른다.</p>
     */
    public static Optional<Manifest> readFrom(Path archive) {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(ENTRY);
            if (entry == null) return Optional.empty();
            Map<String, Stamp> parsed = new LinkedHashMap<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8), CHUNK_CHARS)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseLine(line, parsed);
                }
            }
            return Optional.of(new Manifest(parsed));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
