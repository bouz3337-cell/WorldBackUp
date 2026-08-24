package io.github.yj.worldbackup.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이 플러그인이 시각을 적을 때 쓰는 시계.
 *
 * <p>호스팅 컨테이너는 대개 UTC 로 돈다. 한국에서 보면 9시간 뒤로 적혀서, 저녁에 만든
 * 백업이 <b>어제 날짜</b>로 보인다. 사고 직후 "몇 시 시점으로 되돌릴지" 고르는 도구에서
 * 그 어긋남은 그냥 넘길 일이 아니다 - 엉뚱한 시점을 고르게 된다.</p>
 */
class ClockTest {

    /** 2026-08-24 20:22:50 UTC = 2026-08-25 05:22:50 (한국). 실제로 겪은 그 시각이다. */
    private static final Instant MOMENT = Instant.parse("2026-08-24T20:22:50Z");

    @AfterEach
    void reset() {
        Clock.use(""); // 다른 테스트가 서버 시계를 전제한다
    }

    /**
     * 같은 순간이 시계에 따라 <b>날짜까지</b> 달라진다.
     *
     * <p>이것이 "하루 밀렸다" 로 보이는 이유다. 9시간 차이지만 자정을 넘으면 날짜가 바뀐다.</p>
     */
    @Test
    void theSameMomentGetsADifferentDateInADifferentZone() {
        Clock.use("UTC");
        assertEquals("20260824-202250", Clock.id(MOMENT));
        assertEquals("2026-08-24 20:22:50", Clock.display(MOMENT));

        Clock.use("Asia/Seoul");
        assertEquals("20260825-052250", Clock.id(MOMENT), "한국에서는 이미 다음 날 새벽이다");
        assertEquals("2026-08-25 05:22:50", Clock.display(MOMENT));
    }

    /** {@code keep-daily} 와 날짜별 목록이 이 경계를 쓴다. 하루가 어디서 갈리는지가 달라진다. */
    @Test
    void theDayBoundaryMovesWithTheZone() {
        Clock.use("UTC");
        assertEquals("2026-08-24", Clock.date(MOMENT).toString());

        Clock.use("Asia/Seoul");
        assertEquals("2026-08-25", Clock.date(MOMENT).toString());
    }

    /** 비워 두면 서버 시계. 설정을 건드리지 않은 서버는 지금까지와 똑같이 돈다. */
    @Test
    void anEmptySettingMeansTheServerClock() {
        assertEquals(ZoneId.systemDefault(), Clock.use(""));
        assertEquals(ZoneId.systemDefault(), Clock.use(null));
        assertEquals(ZoneId.systemDefault(), Clock.use("   "));
    }

    /**
     * 오타 하나로 서버가 안 뜨면 안 된다.
     *
     * <p>시간대 이름은 손으로 적는 값이라 틀리기 쉽다({@code Asia/Seoul} 을 {@code Asia/seoul}
     * 로). 그때 서버 시계로 도는 것이 시작을 막는 것보다 낫다 - 시각이 어긋나는 것은 고칠 수
     * 있지만 서버가 안 뜨면 백업도 못 한다.</p>
     */
    @Test
    void aTypoFallsBackToTheServerClockInsteadOfBreaking() {
        assertEquals(ZoneId.systemDefault(), Clock.use("Asia/서울"));
        assertEquals(ZoneId.systemDefault(), Clock.use("아무거나"));
        assertEquals(ZoneId.systemDefault(), Clock.use("Asia/Seoul/Extra"));
    }

    /**
     * 지역 이름을 모르면 <b>시차로도</b> 적을 수 있다.
     *
     * <p>{@code Asia/Seoul} 을 외우고 있는 관리자는 많지 않다. {@code UTC+9} 처럼 적어도
     * 통하는 편이 낫다 - 한국은 서머타임이 없어서 결과가 같다.</p>
     */
    @Test
    void anOffsetLikeUtcPlusNineAlsoWorks() {
        assertTrue(Clock.known("UTC+9"));
        Clock.use("UTC+9");
        assertEquals("20260825-052250", Clock.id(MOMENT), "Asia/Seoul 과 같은 결과가 나온다");
    }

    /** 그래도 오타라는 사실은 알려 줘야 한다. 조용히 서버 시계로 돌면 원인을 못 찾는다. */
    @Test
    void aTypoIsStillReportedAsUnknown() {
        assertTrue(Clock.known("Asia/Seoul"));
        assertTrue(Clock.known("UTC"));
        assertTrue(Clock.known(""), "비워 두는 것은 오타가 아니라 '서버 시계' 라는 뜻이다");
        assertTrue(Clock.known(null));

        assertFalse(Clock.known("Asia/서울"));
        assertFalse(Clock.known("아무거나"));
    }

    /**
     * 백업 ID 를 다시 시각으로 읽는 것도 <b>같은 시계</b>여야 한다.
     *
     * <p>메타데이터를 읽지 못한 손상된 백업에서만 쓰이는 경로지만, 여기가 어긋나면 그 백업이
     * 목록에서 엉뚱한 자리에 앉는다.</p>
     */
    @Test
    void anIdRoundTripsThroughTheSameZone() {
        for (String zone : new String[]{"UTC", "Asia/Seoul", "America/New_York"}) {
            Clock.use(zone);
            assertEquals(MOMENT, Clock.parseId(Clock.id(MOMENT)), zone + " 에서 왕복이 어긋난다");
        }
    }
}
