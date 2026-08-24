package io.github.yj.worldbackup.update;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>플러그인이 스스로 무엇을 내려받을지</b> 정하는 판단.
 *
 * <p>여기가 틀리면 서버가 사람 확인 없이 엉뚱한 파일을 받아 {@code plugins/} 에 놓는다.
 * 그러면 다음 부팅에 서버가 아예 뜨지 않는다 - 백업 플러그인이 만들 수 있는 실패 중
 * 가장 나쁜 축이다.</p>
 *
 * <p>네트워크는 만지지 않는다. 깃허브가 돌려주는 것과 같은 모양의 문자열을 직접 주고,
 * 무엇을 고르는지만 본다. 인터넷이 없는 곳에서도, CI 에서도 같은 답이 나와야 한다.</p>
 */
class ReleaseTest {

    // ------------------------------------------------------------------
    // 버전 비교

    /**
     * {@code 1.10.0} 은 {@code 1.9.0} 보다 <b>새 것</b>이다.
     *
     * <p>글자로 견주면 반대가 된다("9" &gt; "1"). 그렇게 되면 버전을 올릴수록 업데이트가
     * 멈춘다 - 그리고 아무도 그 사실을 모른다.</p>
     */
    @Test
    void versionsAreComparedAsNumbersNotText() {
        assertTrue(Release.isNewer("1.9.0", "1.10.0"));
        assertFalse(Release.isNewer("1.10.0", "1.9.0"));
        assertTrue(Release.isNewer("1.1.1", "1.2.0"));
        assertTrue(Release.isNewer("1.2.0", "2.0.0"));
        assertFalse(Release.isNewer("2.0.0", "1.99.99"));
    }

    /** 같은 버전은 새 것이 아니다. 이게 새면 서버가 켤 때마다 같은 파일을 받는다. */
    @Test
    void theSameVersionIsNeverNewer() {
        assertFalse(Release.isNewer("1.2.0", "1.2.0"));
        assertFalse(Release.isNewer("1.2.0", "v1.2.0"), "태그의 v 는 버전이 아니다");
        assertEquals(0, Release.compare("1.2.0", "v1.2.0"));
    }

    /** 마디 수가 달라도 견줄 수 있어야 한다. */
    @Test
    void versionsWithDifferentDepthsStillCompare() {
        assertFalse(Release.isNewer("1.2.0", "1.2"));
        assertTrue(Release.isNewer("1.2", "1.2.1"));
        assertEquals(0, Release.compare("1.2", "1.2.0"));
    }

    /** 꼬리표가 붙어도 앞쪽 마디는 맞게 견준다. */
    @Test
    void aTaggedVersionStillComparesByItsNumbers() {
        assertTrue(Release.isNewer("1.2.0", "1.3.0-beta"));
        assertFalse(Release.isNewer("1.3.0", "1.2.0-rc1"));
    }

    // ------------------------------------------------------------------
    // 주소

    /**
     * 깃허브가 아닌 곳에서는 받지 않는다.
     *
     * <p>응답을 조작할 수 있는 자리에 있는 누군가가 주소만 바꿔치기하면, 플러그인이 스스로
     * 남의 코드를 받아 {@code plugins/} 에 놓게 된다.</p>
     */
    @Test
    void onlyGithubOverHttpsIsAccepted() {
        assertTrue(Release.isSafeUrl("https://github.com/a/b/releases/download/v1/x.jar"));
        assertTrue(Release.isSafeUrl("https://objects.githubusercontent.com/x"));
        assertTrue(Release.isSafeUrl("https://api.github.com/repos/a/b/releases/latest"));

        assertFalse(Release.isSafeUrl("http://github.com/a/b/x.jar"), "평문 http 는 받지 않는다");
        assertFalse(Release.isSafeUrl("https://example.com/x.jar"));
        assertFalse(Release.isSafeUrl("https://github.com.evil.example/x.jar"),
                "이름이 github.com 으로 시작한다고 깃허브가 아니다");
        assertFalse(Release.isSafeUrl(null));
    }

    // ------------------------------------------------------------------
    // 자산 고르기

    @Test
    void thePluginJarIsPickedOutOfTheRelease() {
        Optional<Release> release = Release.parse(json("v1.3.0", false, false, """
                {"name": "WorldBackUp-1.3.0.jar", "size": 210000,
                 "browser_download_url": "https://github.com/o/r/releases/download/v1.3.0/WorldBackUp-1.3.0.jar"}
                """), "WorldBackUp");

        assertTrue(release.isPresent());
        assertEquals("1.3.0", release.get().version(), "태그의 v 는 떼어 낸다");
        assertEquals(210000, release.get().sizeBytes());
    }

    /**
     * 릴리스에 딸려 오는 다른 첨부를 집으면 안 된다.
     *
     * <p>깃허브는 소스 zip 을 자동으로 붙이고, 관리자가 문서나 설정 예시를 함께 올리기도
     * 한다. 그중 아무거나 {@code plugins/} 에 놓으면 다음 부팅에 서버가 뜨지 않는다.</p>
     */
    @Test
    void otherAttachmentsAreNeverMistakenForThePlugin() {
        Optional<Release> release = Release.parse(json("v1.3.0", false, false, """
                {"name": "Source code.zip", "size": 500000,
                 "browser_download_url": "https://github.com/o/r/archive/v1.3.0.zip"},
                {"name": "config-example.yml", "size": 4000,
                 "browser_download_url": "https://github.com/o/r/releases/download/v1.3.0/config-example.yml"},
                {"name": "SomeOtherPlugin.jar", "size": 90000,
                 "browser_download_url": "https://github.com/o/r/releases/download/v1.3.0/SomeOtherPlugin.jar"},
                {"name": "WorldBackUp-1.3.0.jar", "size": 210000,
                 "browser_download_url": "https://github.com/o/r/releases/download/v1.3.0/WorldBackUp-1.3.0.jar"}
                """), "WorldBackUp");

        assertTrue(release.isPresent());
        assertTrue(release.get().downloadUrl().endsWith("WorldBackUp-1.3.0.jar"));
    }

    /** 초안과 미리보기는 "아직 내보낼 것이 아니다" 라는 뜻이다. */
    @Test
    void draftsAndPrereleasesAreLeftAlone() {
        String asset = """
                {"name": "WorldBackUp-9.9.9.jar", "size": 210000,
                 "browser_download_url": "https://github.com/o/r/releases/download/v9.9.9/WorldBackUp-9.9.9.jar"}
                """;
        assertTrue(Release.parse(json("v9.9.9", true, false, asset), "WorldBackUp").isEmpty(),
                "초안을 남의 서버가 대신 시험하게 두면 안 된다");
        assertTrue(Release.parse(json("v9.9.9", false, true, asset), "WorldBackUp").isEmpty());
    }

    /** 터무니없는 크기는 받지 않는다. 이 플러그인은 200KB 대다. */
    @Test
    void anAbsurdlyLargeAssetIsRefused() {
        Optional<Release> release = Release.parse(json("v1.3.0", false, false, """
                {"name": "WorldBackUp-1.3.0.jar", "size": 999999999999,
                 "browser_download_url": "https://github.com/o/r/releases/download/v1.3.0/WorldBackUp-1.3.0.jar"}
                """), "WorldBackUp");

        assertTrue(release.isEmpty());
    }

    /** 자산 주소가 깃허브 밖이면 그 자산은 없는 것으로 본다. */
    @Test
    void anAssetHostedElsewhereIsRefused() {
        Optional<Release> release = Release.parse(json("v1.3.0", false, false, """
                {"name": "WorldBackUp-1.3.0.jar", "size": 210000,
                 "browser_download_url": "https://cdn.example.com/WorldBackUp-1.3.0.jar"}
                """), "WorldBackUp");

        assertTrue(release.isEmpty());
    }

    /**
     * 깨진 응답에 터지지 않는다.
     *
     * <p>여기서 예외가 나가면 서버 시작 로그에 스택 트레이스가 찍힌다. 업데이트 확인은
     * 부수적인 일이라 그것 때문에 관리자를 놀라게 할 이유가 없다.</p>
     */
    @Test
    void aBrokenResponseIsJustNoUpdate() {
        assertTrue(Release.parse("", "WorldBackUp").isEmpty());
        assertTrue(Release.parse("<html>rate limited</html>", "WorldBackUp").isEmpty());
        assertTrue(Release.parse("{}", "WorldBackUp").isEmpty());
        assertTrue(Release.parse("[1,2,3]", "WorldBackUp").isEmpty());
        assertTrue(Release.parse("{\"tag_name\": \"v1.3.0\"}", "WorldBackUp").isEmpty());
    }

    // ------------------------------------------------------------------
    // 받은 파일 확인

    /** {@code plugin.yml} 한 줄을 보는 방식이 따옴표 있고 없고에 흔들리면 안 된다. */
    @Test
    void theManifestCheckReadsQuotedAndBareValues() {
        assertTrue(UpdateService.manifestSays("name: WorldBackUp\nversion: '1.3.0'\n", "name", "WorldBackUp"));
        assertTrue(UpdateService.manifestSays("name: WorldBackUp\nversion: '1.3.0'\n", "version", "1.3.0"));
        assertTrue(UpdateService.manifestSays("version: \"1.3.0\"\n", "version", "1.3.0"));
        assertTrue(UpdateService.manifestSays("version: 1.3.0\n", "version", "1.3.0"));

        assertFalse(UpdateService.manifestSays("name: SomethingElse\n", "name", "WorldBackUp"));
        assertFalse(UpdateService.manifestSays("name: WorldBackUp\n", "version", "1.3.0"),
                "없는 항목을 있다고 하면 안 된다");
    }

    // ------------------------------------------------------------------
    // 어디에 놓을 것인가

    /**
     * 받은 jar 는 <b>지금 돌고 있는 jar 와 같은 이름</b>으로 놓아야 한다.
     *
     * <p>버킷은 시작할 때 {@code plugins/} 의 jar 마다 <b>같은 이름</b>의 파일이 업데이트
     * 폴더에 있는지 보고, 있으면 그것으로 덮어쓴다. 이름이 다르면 아무 일도 일어나지 않는다 -
     * 받아 둔 jar 가 업데이트 폴더에 조용히 눌러앉고, 관리자는 업데이트했다고 믿은 채
     * <b>옛 버전을 계속 돌린다.</b></p>
     *
     * <p>이 플러그인은 파일 이름에 버전이 들어가므로({@code WorldBackUp-1.1.2.jar}) 그것이
     * 기본값이 된다. 실제로 이 테스트를 쓰기 전까지 {@code WorldBackUp.jar} 로 놓고 있었다.</p>
     */
    @Test
    void theDownloadKeepsTheNameOfTheJarItReplaces() {
        assertEquals("WorldBackUp-1.1.2.jar",
                UpdateService.stagedName("WorldBackUp-1.1.2.jar", "WorldBackUp"),
                "이름이 다르면 버킷이 갈아 끼우지 않는다");
        assertEquals("WorldBackUp.jar", UpdateService.stagedName("WorldBackUp.jar", "WorldBackUp"));
        assertEquals("wb-plugin.jar", UpdateService.stagedName("wb-plugin.jar", "WorldBackUp"),
                "관리자가 이름을 바꿔 두었어도 그 이름을 따른다");
    }

    /** 이름을 알 수 없거나 이상하면 안전한 기본값으로. 업데이트 폴더 밖에 쓰지 않는다. */
    @Test
    void aStrangeJarNameFallsBackInsteadOfEscaping() {
        assertEquals("WorldBackUp.jar", UpdateService.stagedName(null, "WorldBackUp"));
        assertEquals("WorldBackUp.jar", UpdateService.stagedName("", "WorldBackUp"));
        assertEquals("WorldBackUp.jar", UpdateService.stagedName("plugins", "WorldBackUp"),
                "jar 가 아니면 그 이름을 쓰지 않는다");
        assertEquals("x.jar", UpdateService.stagedName("../../x.jar", "WorldBackUp"),
                "경로가 섞여 들어오면 이름만 남긴다");
        assertEquals("x.jar", UpdateService.stagedName("C:\\evil\\x.jar", "WorldBackUp"));
    }

    // ------------------------------------------------------------------

    private static String json(String tag, boolean draft, boolean prerelease, String assets) {
        return """
                {
                  "tag_name": "%s",
                  "draft": %s,
                  "prerelease": %s,
                  "html_url": "https://github.com/o/r/releases/tag/%s",
                  "assets": [%s]
                }
                """.formatted(tag, draft, prerelease, tag, assets);
    }
}
