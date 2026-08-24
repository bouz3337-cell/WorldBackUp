package io.github.yj.worldbackup.util;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FileUtil {

    private FileUtil() {
    }

    /**
     * 백업 대상을 한 번 훑어 얻은 두 가지 크기.
     *
     * @param totalBytes   스냅샷 전체 크기. 진행률의 분모가 된다.
     * @param changedBytes 이번 아카이브에 <b>실제로 저장될</b> 크기. 디스크 여유 판단의 기준이 된다.
     *                     전체 백업이면 {@code totalBytes} 와 같고, 차등 백업이면 바뀐 파일만 더한 값이다.
     */
    public record Sizes(long totalBytes, long changedBytes) {

        public static final Sizes ZERO = new Sizes(0L, 0L);

        public Sizes plus(Sizes other) {
            return new Sizes(totalBytes + other.totalBytes, changedBytes + other.changedBytes);
        }
    }

    /**
     * 이 파일을 이번 아카이브에 새로 저장해야 하는지 판단한다.
     *
     * <p>차등 백업의 기준 매니페스트와 비교하는 용도다. {@code null} 을 넘기면 전부 저장 대상으로 본다.</p>
     */
    @FunctionalInterface
    public interface ChangeFilter {
        boolean stores(String relativePath, long size, long lastModifiedMillis);
    }

    /**
     * 이 뿌리를 훑을 때 쓸 순회 옵션.
     *
     * <p>뿌리가 <b>심볼릭 링크면 링크를 따라간다.</b> {@link Files#walkFileTree(Path, FileVisitor)}
     * 는 기본적으로 링크를 따르지 않는데, 그러면 시작 경로가 링크일 때 디렉터리로 취급되지 않고
     * <b>파일 하나로 방문</b>되어 그 아래가 통째로 빠진다. 백업은 성공한 것처럼 끝나고, 복원할
     * 때가 되어서야 월드가 없다는 것을 알게 된다.</p>
     *
     * <p>월드를 다른 디스크에 두고 서버 폴더에서 링크로 걸어 두는 것(월드는 SSD, 서버는 HDD)은
     * 리눅스 서버에서 흔한 구성이다. 게다가 이 경우 {@code world/players} 처럼 링크를 <b>지나서</b>
     * 찾은 경로는 정상적으로 잡히는데 상위 링크에 가려 중복으로 걸러지기까지 해서, 지형도
     * 플레이어 데이터도 없는 백업이 만들어졌다.</p>
     *
     * <p>뿌리가 링크가 아니면 예전 그대로 링크를 따르지 않는다 - 월드 안의 링크까지 따라가면
     * 같은 내용을 두 번 담거나 엉뚱한 곳으로 새어 나갈 수 있다. 뿌리를 일부러 링크로 걸어 둔
     * 관리자에게만 그 판단을 적용한다. (순환은 {@code walkFileTree} 가 {@code visitFileFailed}
     * 로 알려 주므로 순회가 터지지 않는다)</p>
     */
    public static Set<FileVisitOption> walkOptions(Path root) {
        return Files.isSymbolicLink(root) ? EnumSet.of(FileVisitOption.FOLLOW_LINKS) : Set.of();
    }

    /**
     * 백업 대상의 용량을 잰다. 제외 패턴에 걸리는 파일/폴더는 세지 않는다.
     *
     * <p>실제 압축과 <b>같은 기준</b>으로 세야 진행률과 디스크 예상치가 실제와 맞는다.
     * 특히 차등 백업은 스냅샷 전체가 아니라 바뀐 파일만 쓰므로, 두 값을 갈라서 돌려준다.
     * 예전에는 전체 크기 하나만 재서, 200MB 를 쓸 차등 백업이 10GB 의 여유를 요구하며
     * 멀쩡한 백업을 지우고도 실패했다.</p>
     */
    public static Sizes measure(Path path, Path serverRoot, GlobMatcher exclude, ChangeFilter filter) {
        if (path == null || !Files.exists(path)) return Sizes.ZERO;
        boolean filtered = serverRoot != null && exclude != null && !exclude.isEmpty();

        if (Files.isRegularFile(path)) {
            String relative = serverRoot == null ? null : relativize(serverRoot, path);
            if (filtered && (relative == null || exclude.matchesFile(relative))) return Sizes.ZERO;
            try {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                return sizeOf(relative, attrs, filter);
            } catch (IOException e) {
                return Sizes.ZERO;
            }
        }

        final long[] total = {0L, 0L};
        // 파일마다 Path 를 정규화하는 대신 뿌리 기준으로 이어 붙인다.
        RelativePaths relatives = serverRoot == null ? null : RelativePaths.under(serverRoot, path);
        try {
            // 압축과 <b>같은 기준</b>으로 훑어야 진행률과 디스크 예상치가 실제와 맞는다.
            Files.walkFileTree(path, walkOptions(path), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!filtered) return FileVisitResult.CONTINUE;
                    String relative = relative(dir);
                    if (relative != null && exclude.matchesDirectory(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relative = serverRoot == null ? null : relative(file);
                    if (filtered && (relative == null || exclude.matchesFile(relative))) {
                        return FileVisitResult.CONTINUE;
                    }
                    Sizes sizes = sizeOf(relative, attrs, filter);
                    total[0] += sizes.totalBytes();
                    total[1] += sizes.changedBytes();
                    return FileVisitResult.CONTINUE;
                }

                private String relative(Path target) {
                    return relatives != null ? relatives.of(target) : relativize(serverRoot, target);
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 부분 결과라도 반환한다.
        }
        return new Sizes(total[0], total[1]);
    }

    /** 상대 경로를 구하지 못했으면(= 대상 밖) 안전하게 "바뀐 파일"로 본다. */
    private static Sizes sizeOf(String relative, BasicFileAttributes attrs, ChangeFilter filter) {
        long size = attrs.size();
        boolean stored = filter == null || relative == null
                || filter.stores(relative, size, attrs.lastModifiedTime().toMillis());
        return new Sizes(size, stored ? size : 0L);
    }

    /**
     * 겹치는 백업 대상을 정리한다. 상위 경로만 남기고 그 안에 들어 있는 대상은 버린다.
     *
     * <p>같은 파일이 두 대상에 걸쳐 있으면 zip 에 같은 엔트리가 두 번 들어가고,
     * {@code ZipOutputStream} 은 이때 {@code ZipException} 을 던져 <b>백업 전체가 실패</b>한다.
     * {@code extra-paths} 에 {@code "world/playerdata"} 처럼 월드 하위 경로를 적는 것은
     * 충분히 자연스러운 설정이므로, 조용히 실패하지 않도록 여기서 걸러 낸다.</p>
     *
     * <p>입력 순서는 유지된다. 반환된 경로는 모두 절대·정규화된 형태다.</p>
     */
    public static List<Path> dedupeTargets(List<Path> targets) {
        List<Path> normalized = new ArrayList<>();
        for (Path target : targets) {
            Path path = target.toAbsolutePath().normalize();
            if (!normalized.contains(path)) normalized.add(path);
        }
        List<Path> result = new ArrayList<>();
        for (Path candidate : normalized) {
            boolean covered = false;
            for (Path other : normalized) {
                // 서로 같은 경로는 위에서 이미 걸렀으므로 양쪽이 함께 사라지는 일은 없다.
                if (!other.equals(candidate) && candidate.startsWith(other)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) result.add(candidate);
        }
        return result;
    }

    /** 폴더/파일을 재귀적으로 삭제한다. 삭제하지 못한 경로 목록을 반환한다. */
    public static List<Path> deleteRecursively(Path path) {
        List<Path> failed = new ArrayList<>();
        if (path == null || !Files.exists(path)) return failed;
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        failed.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    failed.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try {
                        Files.deleteIfExists(dir);
                    } catch (IOException e) {
                        failed.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            failed.add(path);
        }
        return failed;
    }

    /**
     * 트리를 훑는 동안 상대 경로를 값싸게 만들어 준다.
     *
     * <p>{@link #relativize(Path, Path)} 는 파일마다 Path 를 양쪽 다 정규화하고 새 Path 와
     * 문자열을 만든다. 백업은 같은 트리를 <b>두 번</b> 훑으면서(용량 측정 + 압축) 파일마다
     * 이걸 부르는데, 재 보면 한 번에 1μs 여서 파일이 2만 개인 월드에서는 그것만으로
     * 40ms 다 - 차등 백업 시간의 큰 몫이었다.</p>
     *
     * <p>순회는 뿌리에서 <b>아래로만</b> 내려가므로, 뿌리의 상대 경로를 한 번 구해 두면
     * 나머지는 문자열을 이어 붙이는 것으로 끝난다. 전제가 어긋나는 경로를 만나면
     * (접두사가 다르거나 {@code ..} 가 섞여 있으면) 조용히 원래 방식으로 되돌린다.</p>
     */
    public static final class RelativePaths {

        private final Path serverRoot;
        private final String rootText;
        private final String rootRelative;
        private final int cut;

        private RelativePaths(Path serverRoot, String rootText, String rootRelative, int cut) {
            this.serverRoot = serverRoot;
            this.rootText = rootText;
            this.rootRelative = rootRelative;
            this.cut = cut;
        }

        /**
         * @param root 앞으로 훑을 뿌리. {@code walkFileTree} 에 넘기는 것과 <b>같은</b> 객체여야 한다.
         * @return 뿌리가 서버 폴더 밖이면 null
         */
        public static RelativePaths under(Path serverRoot, Path root) {
            String rootRelative = relativize(serverRoot, root);
            if (rootRelative == null) return null;
            String rootText = root.toString();
            int cut = rootText.endsWith(java.io.File.separator) ? rootText.length() : rootText.length() + 1;
            return new RelativePaths(serverRoot, rootText, rootRelative, cut);
        }

        /** @return 서버 루트 기준 상대 경로, 루트 밖이면 null */
        public String of(Path path) {
            String text = path.toString();
            if (text.startsWith(rootText)) {
                if (text.length() <= cut) return rootRelative;
                // 접두사가 같다고 <b>그 폴더 안</b>인 것은 아니다. 뿌리가 "server/plugins" 일 때
                // "server/plugins-old/x" 도 문자열로는 접두사가 맞아서, 잘라 붙이면
                // "plugins/old/x" 라는 있지도 않은 경로가 나온다. 그 이름으로 zip 에 담기면
                // 복원이 엉뚱한 자리에 쓴다.
                //
                // 지금은 walkFileTree 가 내놓는 뿌리 아래 경로만 들어오므로 그 상황이 오지
                // 않는다. 그래도 막아 두는 이유는 실패가 조용하기 때문이다 - 예외도 경고도
                // 없이 이름만 틀린 채로 백업이 성공한다.
                if (!isSeparator(text.charAt(cut - 1))) return relativize(serverRoot, path);
                String tail = text.substring(cut);
                // ".." 가 섞인 경로는 이어 붙이기로 정리할 수 없다. 정규화하는 쪽에 맡긴다.
                if (tail.indexOf("..") < 0) {
                    return rootRelative + "/" + tail.replace('\\', '/');
                }
            }
            return relativize(serverRoot, path);
        }

        /** 윈도우는 두 구분자를 모두 받아들이므로 둘 다 본다. */
        private static boolean isSeparator(char c) {
            return c == '/' || c == '\\';
        }
    }

    /** 서버 루트 기준 상대 경로를 '/' 구분자 문자열로 만든다. 루트 밖이면 null. */
    public static String relativize(Path root, Path target) {
        try {
            Path relative = root.toAbsolutePath().normalize().relativize(target.toAbsolutePath().normalize());
            String text = relative.toString().replace('\\', '/');
            if (text.isEmpty() || text.startsWith("..")) return null;
            return text;
        } catch (IllegalArgumentException e) {
            return null; // 다른 드라이브 등
        }
    }

    public static long usableSpace(Path path) {
        Path probe = path;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) return Long.MAX_VALUE;
        try {
            return Files.getFileStore(probe).getUsableSpace();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int index = -1;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[index]);
    }

    public static String humanDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (days > 0) return days + "일 " + hours + "시간";
        if (hours > 0) return hours + "시간 " + minutes + "분";
        if (minutes > 0) return minutes + "분 " + secs + "초";
        return secs + "초";
    }

    public static String humanMillis(long millis) {
        if (millis < 1000) return millis + "ms";
        return String.format(Locale.ROOT, "%.1f초", millis / 1000.0);
    }
}
