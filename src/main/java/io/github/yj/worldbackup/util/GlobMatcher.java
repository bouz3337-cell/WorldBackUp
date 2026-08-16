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

    /**
     * 패턴 전부를 하나로 합친 정규식. 없으면 null.
     *
     * <p>패턴마다 {@link Pattern} 을 따로 두고 순회하면 파일 하나를 검사할 때마다 패턴 수만큼
     * {@code Matcher} 가 생긴다. 기본 설정만 해도 10개고, 이 검사는 백업 대상 <b>모든 파일</b>에
     * 대해 용량 측정과 압축에서 각각 한 번씩 돈다. 교대(alternation) 하나로 합치면 결과는 그대로
     * 두면서 그 반복을 한 번으로 줄인다.</p>
     */
    private final Pattern combined;

    public GlobMatcher(Collection<String> globs) {
        List<String> bodies = new ArrayList<>();
        if (globs != null) {
            for (String glob : globs) {
                if (glob == null || glob.isBlank()) continue;
                bodies.add("(?:" + toRegexBody(glob.trim()) + ")");
            }
        }
        this.combined = bodies.isEmpty() ? null
                : Pattern.compile("^(?:" + String.join("|", bodies) + ")$", Pattern.CASE_INSENSITIVE);
    }

    public static GlobMatcher empty() {
        return new GlobMatcher(List.of());
    }

    public boolean isEmpty() {
        return combined == null;
    }

    /** 파일의 상대 경로가 패턴 중 하나에 매칭되는지 검사한다. */
    public boolean matchesFile(String relativePath) {
        if (combined == null) return false;
        return combined.matcher(relativePath.replace('\\', '/')).matches();
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

    /** glob 하나를 <b>앵커 없는</b> 정규식 조각으로 바꾼다. 앵커는 합칠 때 한 번만 붙인다. */
    static String toRegexBody(String glob) {
        StringBuilder sb = new StringBuilder();
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
        // 닫히지 않은 '{' 는 config.yml 오타에서 나온다. 그대로 두면 정규식이 깨져
        // 설정 로딩 자체가 예외로 죽으므로, 여기서 닫아 평범한 교대로 취급한다.
        while (braceDepth-- > 0) sb.append(')');
        return sb.toString();
    }
}
