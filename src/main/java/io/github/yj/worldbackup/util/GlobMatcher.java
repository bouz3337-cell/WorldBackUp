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
     * 경로 <b>전체</b>를 보고 판단해야 하는 패턴을 하나로 합친 정규식. 없으면 null.
     *
     * <p>패턴마다 {@link Pattern} 을 따로 두고 순회하면 파일 하나를 검사할 때마다 패턴 수만큼
     * {@code Matcher} 가 생긴다. 기본 설정만 해도 10개고, 이 검사는 백업 대상 <b>모든 파일</b>에
     * 대해 용량 측정과 압축에서 각각 한 번씩 돈다. 교대(alternation) 하나로 합치면 결과는 그대로
     * 두면서 그 반복을 한 번으로 줄인다.</p>
     */
    private final Pattern full;

    /**
     * <b>마지막 경로 조각</b>만 보면 되는 패턴들.
     *
     * <p>{@code **}{@code /X} 꼴에서 {@code X} 가 경로 구분자를 넘을 수 없다면, 그 패턴은
     * 마지막 조각과만 맞을 수 있다. 그러면 짧은 문자열 하나만 검사하면 된다.</p>
     *
     * <p>이게 왜 중요한가: {@code (?:.*&#47;)?} 로 시작하는 교대는 맞지 않는 경로에 대해
     * 정규식 엔진이 <b>모든 {@code /} 위치를 되짚어 보게</b> 만든다. 그런 교대가 여럿이면
     * 그 되짚기가 곱해진다. 재 보면 기본 제외 패턴 12개로 검사 한 번에 2.7μs 였고, 파일이
     * 2만 개인 월드에서는 (두 번 훑으므로) 그것만으로 100ms 가 넘었다. 앞의 {@code **}{@code /}
     * 를 떼고 짧은 조각에만 견주면 되짚을 자리가 없어진다.</p>
     */
    private final Pattern segment;

    /**
     * "이 이름의 폴더 아래 전부" 를 뜻하는 패턴들의 폴더 이름.
     *
     * <p>{@code **}{@code /logs/**} 같은 꼴이다. 기본 제외 패턴에서 가장 비싼 쪽이 이것들이었다 -
     * 앞의 {@code (?:.*&#47;)?} 때문에 정규식 엔진이 {@code /} 마다 되짚어 보는데, 뒤에 {@code /**}
     * 가 붙어 있어 {@link #lastSegmentOnly} 지름길도 못 탄다. 그런데 이 꼴은 정규식이 필요 없다 -
     * "경로 조각 중에 이 이름이 있고, 그 뒤에 뭔가 더 있는가" 를 묻는 것과 정확히 같다.
     * 조각을 한 번 훑으면 되므로 문자열을 새로 만들 일도 없다.</p>
     */
    private final String[] enclosingDirectories;

    public GlobMatcher(Collection<String> globs) {
        List<String> fullBodies = new ArrayList<>();
        List<String> segmentBodies = new ArrayList<>();
        List<String> directories = new ArrayList<>();
        if (globs != null) {
            for (String glob : globs) {
                if (glob == null || glob.isBlank()) continue;
                // 패턴도 경로와 <b>같은 구분자</b>로 맞춘다. {@link #matchesFile} 은 검사할 경로를
                // '/' 로 바꾸는데 패턴은 그대로 두고 있어서, 윈도우 관리자가 자연스럽게 적은
                // "world\region\**" 이 아무것도 걸러 내지 못했다. 그런데 조용히 통과한다 -
                // 50GB 폴더를 뺐다고 믿는 쪽이 실제로는 그대로 담고 있는 것이다.
                // (경로 쪽이 이미 정규화되므로, 정규화하지 않는 것이 오히려 어긋난 처리였다)
                String trimmed = glob.trim().replace('\\', '/');

                String directory = enclosingDirectory(trimmed);
                if (directory != null) {
                    directories.add(directory);
                    continue;
                }
                String tail = lastSegmentOnly(trimmed);
                if (tail != null) {
                    segmentBodies.add("(?:" + toRegexBody(tail) + ")");
                } else {
                    fullBodies.add("(?:" + toRegexBody(trimmed) + ")");
                }
            }
        }
        this.full = compile(fullBodies);
        this.segment = compile(segmentBodies);
        this.enclosingDirectories = directories.toArray(new String[0]);
    }

    private static Pattern compile(List<String> bodies) {
        return bodies.isEmpty() ? null
                : Pattern.compile("^(?:" + String.join("|", bodies) + ")$", Pattern.CASE_INSENSITIVE);
    }

    /**
     * 이 패턴이 마지막 경로 조각만 보고 판단할 수 있는지.
     *
     * <p>{@code **}{@code /X} 이고 {@code X} 안에 {@code /} 도 {@code **} 도 없어야 한다.
     * 그 조건에서 {@code X} 의 조각들({@code *} → {@code [^/]*}, {@code ?} → {@code [^/]},
     * 리터럴, {@code {a,b}})은 하나도 {@code /} 를 넘지 못하므로, {@code X} 는 마지막
     * {@code /} 뒤쪽과 <b>정확히</b> 맞아야만 한다. 즉 두 검사가 같은 답을 낸다.</p>
     *
     * <p>{@code **} 를 허용하면 안 된다 - {@code .*} 로 풀려서 {@code /} 를 넘어 버리기 때문에
     * ({@code **}{@code /a**b} 는 {@code x/adir/subb} 에 맞는다) 같은 답이 나오지 않는다.
     * {@code **}{@code /} 로 시작하지 않는 패턴({@code *.log} 는 최상위만, {@code plugins/*.jar})도
     * 위치를 못 박는 뜻이므로 그대로 경로 전체에 견준다.</p>
     *
     * @return 마지막 조각에 견줄 glob, 해당되지 않으면 null
     */
    private static String lastSegmentOnly(String glob) {
        if (!glob.startsWith("**/")) return null;
        String tail = glob.substring(3);
        if (tail.isEmpty() || tail.indexOf('/') >= 0 || tail.contains("**")) return null;
        return tail;
    }

    /**
     * 이 패턴이 "어디에 있든 이 이름의 폴더 아래 전부" 를 뜻하는지.
     *
     * <p>{@code **}{@code /D/**} 이고 {@code D} 가 와일드카드 없는 <b>리터럴</b>이어야 한다.
     * 그 정규식은 {@code (?:.*&#47;)?D/.*} 인데, 이는 "경로가 {@code D/} 로 시작하거나
     * {@code /D/} 를 담고 있다" 와 같다. {@code D} 뒤의 {@code .*} 는 비어도 되므로
     * {@code D/} 로 끝나는 경로도 포함된다 - {@link #matchesDirectory} 가 뒤에 {@code /} 를
     * 붙여 한 번 더 묻는 것이 그 경우다.</p>
     *
     * <p>와일드카드가 하나라도 있으면 이 지름길을 쓸 수 없으므로 정규식으로 넘긴다.
     * 판단이 애매한 글자({@code ,} 처럼 리터럴일 수도 있는 것)도 넘긴다 - 놓쳐서 느린 것은
     * 괜찮지만 잘못 맞히면 백업 대상이 달라진다.</p>
     *
     * @return 폴더 이름, 해당되지 않으면 null
     */
    private static String enclosingDirectory(String glob) {
        if (!glob.startsWith("**/") || !glob.endsWith("/**") || glob.length() <= 6) return null;
        String middle = glob.substring(3, glob.length() - 3);
        if (middle.isEmpty() || middle.indexOf('/') >= 0) return null;
        for (int i = 0; i < middle.length(); i++) {
            char c = middle.charAt(i);
            if (c == '*' || c == '?' || c == '{' || c == '}' || c == ',' || c == '\\' || c == '[') {
                return null;
            }
        }
        return middle;
    }

    /**
     * 경로 조각 중에 이 이름이 있고, 그 뒤에 무언가 더 있는지.
     *
     * <p>대소문자를 가리지 않는다({@link #full} 이 {@link Pattern#CASE_INSENSITIVE} 로
     * 컴파일되는 것과 같은 규칙). 소문자로 바꾼 사본을 만들지 않으려고 조각마다
     * {@link String#regionMatches(boolean, int, String, int, int)} 로 견준다.</p>
     */
    private static boolean underDirectory(String path, String name) {
        int length = name.length();
        int from = 0;
        while (true) {
            int slash = path.indexOf('/', from);
            if (slash < 0) return false; // 마지막 조각 뒤에는 아무것도 없다
            if (slash - from == length && path.regionMatches(true, from, name, 0, length)) {
                return true;
            }
            from = slash + 1;
        }
    }

    public static GlobMatcher empty() {
        return new GlobMatcher(List.of());
    }

    public boolean isEmpty() {
        return full == null && segment == null && enclosingDirectories.length == 0;
    }

    /**
     * 파일의 상대 경로가 패턴 중 하나에 매칭되는지 검사한다.
     *
     * <p>싼 검사부터 한다. 이 메서드는 백업 대상 <b>모든 파일</b>에 대해 용량 측정과 압축에서
     * 각각 한 번씩 불리므로, 순서 하나가 파일 수만큼 곱해진다.</p>
     */
    public boolean matchesFile(String relativePath) {
        if (isEmpty()) return false;
        String path = relativePath.replace('\\', '/');

        // 1) 조각 훑기 - 문자열을 새로 만들지 않는다
        for (String directory : enclosingDirectories) {
            if (underDirectory(path, directory)) return true;
        }
        // 2) 마지막 조각만 떼어 검사한다. substring 을 만들지 않고 구간만 지정한다.
        if (segment != null) {
            int from = path.lastIndexOf('/') + 1;
            if (segment.matcher(path).region(from, path.length()).matches()) return true;
        }
        // 3) 경로 전체를 봐야 하는 것들
        return full != null && full.matcher(path).matches();
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
