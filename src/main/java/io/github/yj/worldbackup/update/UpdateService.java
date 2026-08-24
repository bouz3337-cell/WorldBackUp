package io.github.yj.worldbackup.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 새 버전이 있는지 깃허브에 물어보고, 있으면 받아서 <b>다음 재시작에 적용되도록</b> 놓는다.
 *
 * <p>돌고 있는 jar 를 그 자리에서 바꾸지 않는다. 그러면 안 된다 - 윈도우에서는 파일이 잠겨
 * 실패하고, 리눅스에서는 성공해도 이미 메모리에 올라간 클래스는 그대로라 <b>반쯤 새 버전인</b>
 * 서버가 된다. 대신 버킷이 마련해 둔 {@code plugins/update/} 에 놓는다. 서버는 다음에 켜질 때
 * 그 폴더의 jar 를 {@code plugins/} 로 옮긴 뒤 로드한다. 옛 jar 가 남는 문제도 함께 사라진다.</p>
 *
 * <p><b>받은 것을 그대로 믿지 않는다.</b> 내려받은 파일이 정말 zip 인지, 그 안에
 * {@code plugin.yml} 이 있는지, 이름과 버전이 기대한 것과 같은지 확인한 뒤에야 자리에 놓는다.
 * 그 검사를 통과하지 못하면 지우고 아무 일도 없던 것으로 둔다 - 잘못된 파일을
 * {@code plugins/} 에 놓으면 다음 부팅에 서버가 아예 뜨지 않는다.</p>
 *
 * <p>네트워크를 만지는 유일한 곳이므로 <b>반드시 비동기 스레드에서</b> 부른다.</p>
 */
public final class UpdateService {

    /** 깃허브가 응답하지 않을 때 서버를 붙잡고 있지 않는다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** 받는 도중에도 크기를 다시 본다. 서버가 알려 준 크기를 믿을 이유가 없다. */
    private static final int BUFFER = 64 * 1024;

    private final String repository;
    private final String pluginName;

    public UpdateService(String repository, String pluginName) {
        this.repository = repository;
        this.pluginName = pluginName;
    }

    /** 무엇을 했는지. 사람에게 그대로 보여 줄 수 있는 결과. */
    public sealed interface Outcome {

        /** 이미 최신이다. */
        record UpToDate(String version) implements Outcome {
        }

        /** 새 버전이 있다. 아직 받지는 않았다. */
        record Available(Release release) implements Outcome {
        }

        /** 받아서 다음 재시작에 적용되도록 놓았다. */
        record Staged(Release release, Path file) implements Outcome {
        }

        /** 확인하지 못했다. */
        record Failed(String reason) implements Outcome {
        }
    }

    /**
     * 새 버전이 있는지만 확인한다. 아무것도 내려받지 않는다.
     *
     * @param currentVersion 지금 돌고 있는 버전
     */
    public Outcome check(String currentVersion) {
        try {
            String json = fetch(apiUrl());
            Optional<Release> latest = Release.parse(json, pluginName);
            if (latest.isEmpty()) {
                return new Outcome.Failed("릴리스에서 " + pluginName + " jar 를 찾지 못했습니다.");
            }
            Release release = latest.get();
            return Release.isNewer(currentVersion, release.version())
                    ? new Outcome.Available(release)
                    : new Outcome.UpToDate(currentVersion);
        } catch (Exception e) {
            return new Outcome.Failed(String.valueOf(e.getMessage()));
        }
    }

    /**
     * 확인하고, 새 버전이면 받아서 {@code updateFolder} 에 놓는다.
     *
     * @param updateFolder  {@code Bukkit.getUpdateFolderFile()}
     * @param currentJarName <b>지금 돌고 있는 jar 의 파일 이름.</b> 반드시 이 이름으로 놓아야 한다 -
     *                       이유는 {@link #stagedName} 에 적어 두었다
     */
    public Outcome download(String currentVersion, Path updateFolder, String currentJarName) {
        Outcome checked = check(currentVersion);
        if (!(checked instanceof Outcome.Available available)) return checked;

        Release release = available.release();
        Path temp = null;
        try {
            Files.createDirectories(updateFolder);
            String name = stagedName(currentJarName, pluginName);
            // 받는 동안에는 .part 다. 도중에 서버가 죽어도 버킷이 반쪽짜리 jar 를 집어 가지
            // 않는다 - 그러면 다음 부팅에 서버가 뜨지 않는다.
            temp = updateFolder.resolve(name + ".part");
            downloadTo(release, temp);
            verify(temp, release.version());

            Path target = updateFolder.resolve(name);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return new Outcome.Staged(release, target);
        } catch (Exception e) {
            deleteQuietly(temp);
            return new Outcome.Failed(String.valueOf(e.getMessage()));
        }
    }

    /**
     * {@code plugins/update/} 에 놓을 파일 이름.
     *
     * <p><b>지금 돌고 있는 jar 와 이름이 같아야 한다.</b> 버킷은 시작할 때
     * {@code plugins/} 의 jar 마다 <b>같은 이름</b>의 파일이 업데이트 폴더에 있는지 보고,
     * 있으면 그것으로 덮어쓴다. 이름이 다르면 아무 일도 일어나지 않는다 - 받아 둔 jar 가
     * 업데이트 폴더에 <b>조용히 눌러앉고</b>, 관리자는 업데이트했다고 믿은 채 옛 버전을
     * 계속 돌리게 된다. 파일 이름에 버전이 들어 있는 이 플러그인에서는 그것이 기본값이다
     * ({@code WorldBackUp-1.1.2.jar} 를 쓰는 서버에 {@code WorldBackUp.jar} 를 놓는 셈).</p>
     *
     * <p>그래서 파일 이름은 바뀌지 않는다. {@code WorldBackUp-1.1.2.jar} 라는 이름 안에
     * 1.3.0 이 들어 있게 된다. 헷갈리지만 이것이 버킷이 정한 방식이고, 이름을 바꾸는 것보다
     * 낫다 - 갈아 끼워지지 않는 것보다는 이름이 낡은 편이 낫다.</p>
     *
     * @param currentJarName 지금 돌고 있는 jar 의 파일 이름
     * @param fallback       그 이름을 알 수 없을 때 쓸 이름
     */
    static String stagedName(String currentJarName, String fallback) {
        if (currentJarName == null) return fallback + ".jar";
        String name = currentJarName.trim();
        // 경로가 섞여 들어오면 업데이트 폴더 밖에 쓰게 된다.
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.isEmpty() || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            return fallback + ".jar";
        }
        return name;
    }

    // ------------------------------------------------------------------

    String apiUrl() {
        return "https://api.github.com/repos/" + repository + "/releases/latest";
    }

    private String fetch(String url) throws IOException, InterruptedException {
        if (!Release.isSafeUrl(url)) throw new IOException("주소가 깃허브가 아닙니다: " + url);
        try (HttpClient client = newClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", pluginName)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new IOException("릴리스가 아직 없습니다. (" + repository + ")");
            }
            if (response.statusCode() != 200) {
                throw new IOException("깃허브가 " + response.statusCode() + " 로 답했습니다.");
            }
            return response.body();
        }
    }

    private void downloadTo(Release release, Path target) throws IOException, InterruptedException {
        if (!Release.isSafeUrl(release.downloadUrl())) {
            throw new IOException("내려받을 주소가 깃허브가 아닙니다.");
        }
        try (HttpClient client = newClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(release.downloadUrl()))
                    .header("User-Agent", pluginName)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("내려받지 못했습니다. (" + response.statusCode() + ")");
            }
            long written = 0L;
            try (InputStream in = response.body();
                 var out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    written += read;
                    // 알려 준 크기를 넘어서면 그 자리에서 끊는다. 디스크를 채우게 두지 않는다.
                    if (written > Release.MAX_ASSET_BYTES) {
                        throw new IOException("받는 파일이 너무 큽니다.");
                    }
                    out.write(buffer, 0, read);
                }
            }
            if (written == 0) throw new IOException("받은 내용이 비어 있습니다.");
        }
    }

    /**
     * 받은 파일이 정말 <b>이 플러그인의</b> jar 인지 확인한다.
     *
     * <p>여기를 건너뛰면 무엇이 되었든 {@code plugins/} 로 옮겨진다. HTML 오류 페이지가
     * {@code .jar} 이름으로 저장되는 것만으로도 다음 부팅에 서버가 뜨지 않는다.</p>
     */
    static void verify(Path jar, String expectedVersion) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry("plugin.yml");
            if (entry == null) throw new IOException("plugin.yml 이 없습니다. 플러그인 jar 가 아닙니다.");
            String manifest;
            try (InputStream in = zip.getInputStream(entry)) {
                manifest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!manifestSays(manifest, "name", "WorldBackUp")) {
                throw new IOException("다른 플러그인의 jar 입니다.");
            }
            if (!manifestSays(manifest, "version", expectedVersion)) {
                throw new IOException("릴리스가 알려 준 버전(" + expectedVersion
                        + ")과 jar 안의 버전이 다릅니다.");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("받은 파일이 zip 이 아닙니다.", e);
        }
    }

    /** {@code plugin.yml} 한 줄을 본다. YAML 전체를 읽지 않아도 이 둘은 확인할 수 있다. */
    static boolean manifestSays(String manifest, String key, String expected) {
        for (String line : manifest.split("\\R")) {
            String text = line.trim();
            if (!text.startsWith(key + ":")) continue;
            String value = text.substring(key.length() + 1).trim();
            if (value.startsWith("'") || value.startsWith("\"")) {
                value = value.substring(1, Math.max(1, value.length() - 1));
            }
            return value.equals(expected);
        }
        return false;
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                // https -> http 로 내려가는 이동은 따라가지 않는다.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build();
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
