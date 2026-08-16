package io.github.yj.worldbackup.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FileUtil {

    private FileUtil() {
    }

    /** 폴더 전체 용량(바이트). 접근 불가 파일은 무시한다. */
    public static long directorySize(Path path) {
        return directorySize(path, null, null);
    }

    /**
     * 폴더 전체 용량(바이트). 제외 패턴에 걸리는 파일/폴더는 세지 않는다.
     *
     * <p>실제 압축과 같은 기준으로 세야 진행률과 디스크 예상치가 맞는다.</p>
     */
    public static long directorySize(Path path, Path serverRoot, GlobMatcher exclude) {
        if (path == null || !Files.exists(path)) return 0L;
        boolean filtered = serverRoot != null && exclude != null && !exclude.isEmpty();

        if (Files.isRegularFile(path)) {
            if (filtered) {
                String relative = relativize(serverRoot, path);
                if (relative == null || exclude.matchesFile(relative)) return 0L;
            }
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0L;
            }
        }
        final long[] total = {0L};
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!filtered) return FileVisitResult.CONTINUE;
                    String relative = relativize(serverRoot, dir);
                    if (relative != null && exclude.matchesDirectory(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (filtered) {
                        String relative = relativize(serverRoot, file);
                        if (relative == null || exclude.matchesFile(relative)) return FileVisitResult.CONTINUE;
                    }
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 부분 결과라도 반환한다.
        }
        return total[0];
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
