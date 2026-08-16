package io.github.yj.worldbackup.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * '/' 를 구분자로 쓰는 상대 경로 전용 glob 매처.
 *
 * <p>OS 별로 동작이 달라지는 {@link java.nio.file.FileSystem#getPathMatcher(String)} 대신
 * 직접 정규식으로 변환해 윈도우/리눅스에서 동일하게 동작하도록 한다.
 * 지원 문법: {@code **}, {@code *}, {@code ?}, {@code {a,b}}</p>
 */
public final class GlobMatcher {

    private final List<Pattern> patterns = new ArrayList<>();

    public GlobMatcher(Collection<String> globs) {
        if (globs == null) return;
        for (String glob : globs) {
            if (glob == null || glob.isBlank()) continue;
            patterns.add(Pattern.compile(toRegex(glob.trim()), Pattern.CASE_INSENSITIVE));
        }
    }

    public static GlobMatcher empty() {
        return new GlobMatcher(List.of());
    }

    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /** 파일의 상대 경로가 패턴 중 하나에 매칭되는지 검사한다. */
    public boolean matchesFile(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        for (Pattern pattern : patterns) {
            if (pattern.matcher(normalized).matches()) return true;
        }
        return false;
    }

    /**
     * 디렉터리가 통째로 제외 대상인지 검사한다.
     * {@code **}/{@code logs}/{@code **} 같은 패턴이 "world/logs" 자체에도 걸리도록
     * 뒤에 '/' 를 붙여 한 번 더 확인한다.
     */
    public boolean matchesDirectory(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        return matchesFile(normalized) || matchesFile(normalized + "/");
    }

    static String toRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int braceDepth = 0;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                    if (doubleStar) {
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            i++;
                            sb.append("(?:.*/)?"); // "**/" 는 0개 이상의 디렉터리와 매칭
                        } else {
                            sb.append(".*");
                        }
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '{' -> {
                    braceDepth++;
                    sb.append("(?:");
                }
                case '}' -> {
                    if (braceDepth > 0) {
                        braceDepth--;
                        sb.append(')');
                    } else {
                        sb.append("\\}");
                    }
                }
                case ',' -> sb.append(braceDepth > 0 ? "|" : ",");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.append('$').toString();
    }
}
