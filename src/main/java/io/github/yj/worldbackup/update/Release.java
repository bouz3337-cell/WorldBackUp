package io.github.yj.worldbackup.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Optional;

/**
 * 깃허브가 알려 준 최신 릴리스에서 <b>우리에게 필요한 것만</b> 골라낸 것.
 *
 * <p>이 클래스는 네트워크를 만지지 않는다. 받아 온 문자열을 해석하고 "지금 것보다 새로운가"
 * 를 판단할 뿐이라 서버 없이도, 인터넷 없이도 경계를 그대로 검증할 수 있다. 실제로 받아
 * 오는 것은 {@link UpdateService} 가 한다.</p>
 *
 * <p>판단을 떼어 놓는 이유가 하나 더 있다 - 여기가 틀리면 <b>플러그인이 스스로 다른 파일을
 * 내려받는다.</b> 버전 비교가 헛돌면 멀쩡한 서버가 매번 같은 것을 받고, 자산을 잘못 고르면
 * 엉뚱한 파일이 {@code plugins/update/} 에 놓인다. 네트워크에 기대는 테스트로는 그것을
 * 확인할 수 없다.</p>
 *
 * @param version     릴리스 버전 (태그의 {@code v} 는 떼어 낸 것)
 * @param downloadUrl jar 자산의 내려받기 주소
 * @param sizeBytes   그 자산의 크기. 받기 전에 터무니없는 크기를 걸러 내는 데 쓴다
 * @param pageUrl     사람이 열어 볼 릴리스 페이지
 */
public record Release(String version, String downloadUrl, long sizeBytes, String pageUrl) {

    /** 받아도 되는 자산의 최대 크기. 이 플러그인은 200KB 대다. */
    public static final long MAX_ASSET_BYTES = 50L * 1024 * 1024;

    /**
     * 깃허브 릴리스 JSON 에서 jar 자산 하나를 골라낸다.
     *
     * <p>고르는 규칙은 좁게 둔다 - {@code .jar} 로 끝나고, 이름에 플러그인 이름이 들어 있고,
     * 크기가 말이 되는 것. 릴리스에 소스 zip 이나 다른 첨부가 함께 올라가는 일이 흔한데,
     * 그중 아무거나 집어 {@code plugins/} 에 놓으면 다음 부팅에 서버가 뜨지 않는다.</p>
     *
     * @param json       {@code /releases/latest} 응답
     * @param pluginName 자산 이름에 들어 있어야 하는 문자열
     * @return 쓸 수 있는 릴리스. 초안·미리보기이거나 jar 자산이 없으면 비어 있음
     */
    public static Optional<Release> parse(String json, String pluginName) {
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(json);
            if (element == null || !element.isJsonObject()) return Optional.empty();
            root = element.getAsJsonObject();
        } catch (RuntimeException e) {
            return Optional.empty();
        }

        // 초안과 미리보기는 "아직 내보낼 것이 아니다" 라는 뜻이다. 그것을 서버에 내려보내면
        // 만든 사람이 시험하려던 것을 남의 서버가 대신 시험하게 된다.
        if (bool(root, "draft") || bool(root, "prerelease")) return Optional.empty();

        String version = normalize(string(root, "tag_name"));
        if (version == null) return Optional.empty();

        JsonElement assets = root.get("assets");
        if (assets == null || !assets.isJsonArray()) return Optional.empty();

        for (JsonElement entry : assets.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) continue;
            JsonObject asset = entry.getAsJsonObject();
            String name = string(asset, "name");
            String url = string(asset, "browser_download_url");
            if (name == null || url == null) continue;
            if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) continue;
            if (!name.toLowerCase(java.util.Locale.ROOT).contains(pluginName.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            long size = number(asset, "size");
            if (size <= 0 || size > MAX_ASSET_BYTES) continue;
            if (!isSafeUrl(url)) continue;
            return Optional.of(new Release(version, url, size, string(root, "html_url")));
        }
        return Optional.empty();
    }

    /**
     * 내려받아도 되는 주소인지.
     *
     * <p>{@code https} 가 아니면 받지 않는다. 그리고 깃허브가 아닌 곳도 받지 않는다 -
     * 응답을 조작할 수 있는 자리에 있는 누군가가 주소만 바꿔치기하면, 플러그인이 스스로
     * 남의 코드를 받아 {@code plugins/} 에 놓게 된다.</p>
     */
    public static boolean isSafeUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        String host = url.substring("https://".length());
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        host = host.toLowerCase(java.util.Locale.ROOT);
        return host.equals("github.com")
                || host.equals("api.github.com")
                || host.endsWith(".githubusercontent.com");
    }

    /**
     * 버전 비교. {@code 1.2.0} 이 {@code 1.10.0} 보다 크다고 답하면 안 된다.
     *
     * <p>글자로 견주면 그렇게 된다("2" &gt; "1"). 마디마다 숫자로 견준다. 숫자가 아닌 것은
     * 0 으로 본다 - 태그를 {@code 1.2.0-beta} 처럼 달아도 최소한 앞쪽 마디는 맞게 견준다.</p>
     *
     * @return {@code left} 가 크면 양수, 같으면 0, 작으면 음수
     */
    public static int compare(String left, String right) {
        String[] a = normalizeOrEmpty(left).split("[.\\-+]");
        String[] b = normalizeOrEmpty(right).split("[.\\-+]");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = i < a.length ? digits(a[i]) : 0;
            int y = i < b.length ? digits(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /** {@code candidate} 가 {@code current} 보다 새로운가. */
    public static boolean isNewer(String current, String candidate) {
        return compare(candidate, current) > 0;
    }

    /** {@code v1.2.0} · {@code 1.2.0} 을 모두 {@code 1.2.0} 으로. */
    private static String normalize(String tag) {
        if (tag == null) return null;
        String text = tag.trim();
        if (text.isEmpty()) return null;
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        return text.isEmpty() ? null : text;
    }

    private static String normalizeOrEmpty(String tag) {
        String text = normalize(tag);
        return text == null ? "" : text;
    }

    private static int digits(String part) {
        int value = 0;
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < '0' || c > '9') break;
            value = value * 10 + (c - '0');
            if (value > 1_000_000) return 1_000_000; // 말도 안 되는 태그로 넘치지 않게
        }
        return value;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        return element.getAsString();
    }

    private static long number(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return 0L;
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return false;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
