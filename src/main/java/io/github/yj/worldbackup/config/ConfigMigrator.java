package io.github.yj.worldbackup.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 배포본에 새로 생긴 설정을 사용자 파일에 <b>주석까지 그대로</b> 끼워 넣는다.
 *
 * <p>버킷의 {@code saveDefaultConfig()} 는 파일이 없을 때만 쓴다. 그래서 플러그인을 올려도
 * 기존 서버의 {@code config.yml} 에는 새 설정이 <b>나타나지 않는다.</b> 코드 기본값으로
 * 동작은 하지만, 관리자는 그런 설정이 생겼다는 것도, 무엇을 뜻하는지도 알 수 없다 -
 * 이 플러그인에서는 주석이 곧 문서이기 때문에 특히 그렇다.</p>
 *
 * <p>{@code copyDefaults(true)} + {@code saveConfig()} 는 쓸 수 없다. 값은 채워 주지만
 * <b>주석을 전부 날리고</b> 순서도 뒤섞는다. 이 파일은 90%가 주석이라 그러면 남는 게 없다.</p>
 *
 * <p>그래서 텍스트 수준에서 합친다. 규칙은 하나다 - <b>사용자가 쓴 줄은 한 줄도 건드리지
 * 않는다.</b> 값도, 주석도, 순서도, 줄바꿈도 그대로 두고 빠진 키만 끼워 넣는다. 결과는
 * "원본 줄 + 끼워 넣은 줄" 이라, 실수로 무언가를 잃을 자리가 없다.</p>
 *
 * <p>넣는 자리도 배포본을 따른다. 배포본에서 앞에 오던 설정 바로 뒤에 넣으므로 관리자의
 * 파일은 문서와 같은 순서를 유지한다. 파일 끝에 몰아넣으면 읽히기는 해도 설명과 설정이
 * 따로 놀게 된다.</p>
 *
 * <p>복원과도 맞물린다. 옛 백업을 되돌리면 이 파일도 그 시점 것으로 돌아가 새 설정이 다시
 * 사라지는데, 다음 시작에서 여기가 다시 채워 넣는다.</p>
 */
public final class ConfigMigrator {

    /** {@code key:} 또는 {@code key: value}. 목록 항목과 주석은 걸리지 않는다. */
    private static final Pattern KEY = Pattern.compile("^( *)([A-Za-z_][A-Za-z0-9_-]*):(?:\\s.*)?$");

    /**
     * <b>업그레이드로 끼워 넣을 때만</b> 배포 기본값 대신 쓰는 값.
     *
     * <p>관리자는 jar 하나만 갈아 끼운다. 그것이 이 플러그인이 권하는 방식이기도 하다. 그렇다면
     * <b>jar 를 바꾸는 것만으로 서버 동작이 달라져서는 안 된다</b> - 특히 데이터를 지우거나
     * 디스크를 더 쓰는 쪽으로는. 새 기본값은 <b>새로 설치하는 서버</b>의 것이고, 이미 돌던
     * 서버는 관리자가 읽고 켜기 전까지 하던 대로 돈다.</p>
     *
     * <p>여기 있는 것만 그렇게 다룬다. 나머지 새 설정은 기본값이 지금 동작을 바꾸지 않거나
     * (예: {@code max-total-size-gb: 0} 은 무제한이라 없던 것과 같다), 바꾸더라도 안전한
     * 쪽이다(예: {@code min-backups} 는 지우지 않고 남기기만 한다).</p>
     *
     * <p>값과 함께 <b>왜 그렇게 넣었는지</b>를 둔다. 그대로 파일에 주석으로 들어가므로,
     * 관리자는 파일만 열어 보고도 무엇이 꺼져 있고 왜인지 알 수 있다.</p>
     */
    private static final Map<String, Guard> UPGRADE_SAFE = Map.of(
            "retention.tiers", new Guard("[]",
                    "지금까지 쓰던 보관 정책(max-backups / max-age-days / keep-daily)을 그대로 두려고",
                    "비워 두었습니다. 이 값을 채우면 그쪽이 <b>대신</b> 적용되면서, 계단에 들지 못한",
                    "백업은 다음 정리에서 지워집니다. 켜기 전에 어떤 시점이 남는지 확인하세요."));

    // ※ targets.plugins 는 <b>일부러</b> 여기 없다. 기본값 all 이 그대로 적용되어, 업데이트하면
    //    plugins/ 도 백업에 들어간다. 백업이 커지는 것은 되돌릴 수 있지만, 되돌릴 때 경제 잔고나
    //    보호구역이 백업에 없는 것은 되돌릴 수 없다. 여기서 막는 것은 "지우는" 변화뿐이다.

    /**
     * 업그레이드 때 배포 기본값 대신 넣을 값과 그 이유.
     *
     * @param value 파일에 적을 값 (키 이름과 들여쓰기는 배포본에서 그대로 가져온다)
     * @param why   파일에 주석으로 들어갈 설명
     */
    private record Guard(String value, String... why) {
    }

    /**
     * @param text    합쳐진 파일 내용
     * @param added   새로 끼워 넣은 설정의 경로. 비어 있으면 파일을 다시 쓸 필요가 없다.
     * @param guarded 그중 <b>동작을 바꾸지 않는 값으로</b> 넣은 것들. 관리자에게 따로 알려야 한다 -
     *                새 기능이 파일에는 있는데 꺼져 있다는 사실은 조용히 지나가면 안 된다.
     */
    public record Result(String text, List<String> added, List<String> guarded) {

        public boolean changed() {
            return !added.isEmpty();
        }
    }

    /**
     * 설정 하나가 차지하는 줄 범위 {@code [start, end)}.
     *
     * <p>앞의 주석·빈 줄부터 <b>딸린 하위 설정까지</b> 포함한다. 주석을 함께 옮겨야 끼워 넣은
     * 결과가 배포본과 같은 모양이 되고, 하위까지 함께 옮겨야 묶음이 통째로 빠진 경우에도
     * 한 번에 들어간다.</p>
     */
    private record Block(String path, String parent, int start, int end) {
    }

    private ConfigMigrator() {
    }

    /**
     * 배포본에는 있는데 사용자 파일에는 없는 설정을 끼워 넣는다.
     *
     * @param shipped jar 안의 {@code config.yml}
     * @param user    서버에 있는 {@code config.yml}
     */
    public static Result merge(String shipped, String user) {
        List<String> userLines = lines(user);
        List<String> shippedLines = lines(shipped);
        List<Block> shippedBlocks = parse(shippedLines);

        Map<String, Block> mine = new LinkedHashMap<>();
        for (Block block : parse(userLines)) mine.put(block.path(), block);

        Map<Integer, List<String>> insertions = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        List<String> guarded = new ArrayList<>();
        Set<String> inserted = new LinkedHashSet<>();

        for (int i = 0; i < shippedBlocks.size(); i++) {
            Block block = shippedBlocks.get(i);
            if (mine.containsKey(block.path())) continue;
            added.add(block.path());
            // 윗 설정이 통째로 들어갔다면 이것도 그 안에 이미 있다.
            if (covered(inserted, block.path())) continue;

            inserted.add(block.path());
            int at = anchor(shippedBlocks, i, mine, userLines.size());

            List<String> lines = shippedLines.subList(block.start(), block.end());
            Guard guard = UPGRADE_SAFE.get(block.path());
            if (guard != null) {
                lines = guarded(lines, block.path(), guard);
                guarded.add(block.path());
            }
            insertions.computeIfAbsent(at, key -> new ArrayList<>()).addAll(lines);
        }

        if (added.isEmpty()) return new Result(user, List.of(), List.of());

        // 관리자가 쓰던 줄끝을 유지한다. 메모장으로 연 파일이 통째로 바뀌면 곤란하다.
        String eol = user.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder out = new StringBuilder(user.length() + 4096);
        for (int i = 0; i <= userLines.size(); i++) {
            List<String> insert = insertions.get(i);
            if (insert != null) for (String line : insert) out.append(line).append(eol);
            if (i < userLines.size()) out.append(userLines.get(i)).append(eol);
        }
        return new Result(out.toString(), report(added), List.copyOf(guarded));
    }

    /**
     * 배포본의 값 부분을 <b>동작을 바꾸지 않는 값</b>으로 갈아 끼운다.
     *
     * <p>설명(주석)은 배포본 그대로 둔다 - 그게 이 설정이 무엇인지 알려 주는 문서다. 그 아래에
     * "왜 꺼 두었는지" 와 <b>배포 기본값을 주석으로</b> 붙인다. 관리자가 켜고 싶을 때 다른
     * 문서를 찾을 필요 없이 그 자리에서 주석만 풀면 되게 하려는 것이다.</p>
     */
    private static List<String> guarded(List<String> block, String path, Guard guard) {
        String key = path.substring(path.lastIndexOf('.') + 1);

        int valueAt = -1;
        String indent = "";
        for (int i = 0; i < block.size(); i++) {
            Matcher matcher = KEY.matcher(block.get(i));
            if (matcher.matches() && matcher.group(2).equals(key)) {
                valueAt = i;
                indent = matcher.group(1);
                break;
            }
        }
        if (valueAt < 0) return block; // 배포본 모양이 예상과 다르면 손대지 않는다

        List<String> out = new ArrayList<>(block.subList(0, valueAt));
        out.add(indent + "#");
        out.add(indent + "# ※ 이 설정은 플러그인을 업데이트하면서 새로 생겼습니다.");
        for (String why : guard.why()) {
            out.add(indent + "#    " + why.replace("<b>", "").replace("</b>", ""));
        }
        out.add(indent + "#");
        out.add(indent + "#    새로 설치하는 서버의 기본값은 이렇습니다 - 쓰시려면 이 아래를 그대로 옮겨 적으세요:");
        for (String line : block.subList(valueAt, block.size())) {
            if (line.isBlank()) {
                out.add(indent + "#");
                continue;
            }
            // 안쪽 들여쓰기를 그대로 살린다. 목록 항목이 키와 같은 줄에 붙어 버리면
            // 주석을 풀어 쓸 때 관리자가 다시 맞춰야 한다.
            String rest = line.startsWith(indent) ? line.substring(indent.length()) : line.strip();
            out.add(indent + "#      " + rest);
        }
        out.add(indent + key + ": " + guard.value());
        return out;
    }

    /**
     * 새 설정을 넣을 줄 번호를 고른다.
     *
     * <p>배포본에서 <b>바로 앞에 오던 형제</b>가 사용자 파일에도 있으면 그 뒤에 넣는다. 없으면
     * 뒤에 오던 형제 앞에 넣고, 그것도 없으면 윗 설정의 끝에 붙인다.</p>
     */
    private static int anchor(List<Block> shipped, int index, Map<String, Block> mine, int endOfFile) {
        String parent = shipped.get(index).parent();

        for (int j = index - 1; j >= 0; j--) {
            Block here = sibling(shipped.get(j), parent, mine);
            if (here != null) return here.end();
        }
        for (int j = index + 1; j < shipped.size(); j++) {
            Block here = sibling(shipped.get(j), parent, mine);
            if (here != null) return here.start();
        }

        Block above = mine.get(parent);
        return above != null ? above.end() : endOfFile;
    }

    /** {@code other} 가 같은 윗 설정에 딸린 형제이고 사용자 파일에도 있으면 그 덩어리. */
    private static Block sibling(Block other, String parent, Map<String, Block> mine) {
        return other.parent().equals(parent) ? mine.get(other.path()) : null;
    }

    /** 이미 끼워 넣은 설정 안에 딸려 들어갔는지. */
    private static boolean covered(Set<String> inserted, String path) {
        for (int dot = path.lastIndexOf('.'); dot > 0; dot = path.lastIndexOf('.', dot - 1)) {
            if (inserted.contains(path.substring(0, dot))) return true;
        }
        return false;
    }

    /**
     * 로그에는 <b>실제 설정</b>만 남긴다.
     *
     * <p>묶음이 통째로 새로 생기면 묶음 이름과 그 아래 설정이 모두 잡히는데, 관리자에게
     * "restore, restore.countdown-seconds" 라고 두 번 말할 이유가 없다.</p>
     */
    private static List<String> report(List<String> added) {
        List<String> out = new ArrayList<>();
        for (String path : added) {
            boolean hasChild = false;
            for (String other : added) {
                if (other.startsWith(path + ".")) {
                    hasChild = true;
                    break;
                }
            }
            if (!hasChild) out.add(path);
        }
        return List.copyOf(out);
    }

    /**
     * 파일을 설정 단위로 자른다.
     *
     * <p>깊이는 가리지 않는다. 지금 이 파일은 두 단이지만, 언젠가 한 단 더 깊은 설정이 생겼을 때
     * 조용히 빠뜨리는 것보다는 처음부터 일반적으로 다루는 편이 낫다.</p>
     */
    private static List<Block> parse(List<String> lines) {
        record Key(int line, int indent, String path, String parent) {
        }

        List<Key> keys = new ArrayList<>();
        List<Key> open = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = KEY.matcher(lines.get(i));
            if (!matcher.matches()) continue;

            int indent = matcher.group(1).length();
            while (!open.isEmpty() && open.get(open.size() - 1).indent() >= indent) {
                open.remove(open.size() - 1);
            }
            String parent = open.isEmpty() ? "" : open.get(open.size() - 1).path();
            String path = parent.isEmpty() ? matcher.group(2) : parent + "." + matcher.group(2);

            Key key = new Key(i, indent, path, parent);
            open.add(key);
            keys.add(key);
        }

        List<Block> blocks = new ArrayList<>();
        for (int k = 0; k < keys.size(); k++) {
            Key key = keys.get(k);

            int start = key.line();
            while (start > 0 && isLead(lines.get(start - 1))) start--;
            // 앞 설정이 이미 가져간 줄은 넘겨받지 않는다.
            if (k > 0) start = Math.max(start, keys.get(k - 1).line() + 1);

            // 같은 깊이 이상의 다음 설정이 나오기 전까지가 이 설정의 몫이다.
            int end = lines.size();
            for (int j = k + 1; j < keys.size(); j++) {
                if (keys.get(j).indent() <= key.indent()) {
                    end = keys.get(j).line();
                    break;
                }
            }
            while (end > key.line() + 1 && isLead(lines.get(end - 1))) end--;

            blocks.add(new Block(key.path(), key.parent(), start, end));
        }
        return blocks;
    }

    /** 다음 설정에 딸려갈 수 있는 줄 - 주석이거나 빈 줄. */
    private static boolean isLead(String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            out.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        // split 이 마지막 개행 뒤에 남기는 빈 조각은 줄이 아니다.
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }
}
