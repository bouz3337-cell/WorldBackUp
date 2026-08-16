package io.github.yj.worldbackup.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 시각 표현 해석.
 *
 * <p>사고를 발견한 관리자가 아는 것은 백업 ID 가 아니라 "새벽 3시쯤", "9시간 전쯤" 이다.
 * 여기서 잘못 해석하면 엉뚱한 시점으로 서버를 되돌리게 되므로 경계를 못 박아 둔다.</p>
 */
class TimeTokenTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 서울 기준 2026-08-16 12:00 */
    private static final Instant NOON = Instant.parse("2026-08-16T03:00:00Z");

    @Test
    void relativeTokensGoBackFromNow() {
        assertEquals(NOON.minus(Duration.ofMinutes(30)), TimeToken.parse("30m", NOON, SEOUL));
        assertEquals(NOON.minus(Duration.ofHours(9)), TimeToken.parse("9h", NOON, SEOUL));
        assertEquals(NOON.minus(Duration.ofDays(2)), TimeToken.parse("2d", NOON, SEOUL));
    }

    @Test
    void relativeTokensAreCaseInsensitive() {
        assertEquals(TimeToken.parse("9h", NOON, SEOUL), TimeToken.parse("9H", NOON, SEOUL));
    }

    /** "새벽 3시" 는 오늘 새벽 3시다. 이미 지났으므로 어제로 넘어가면 안 된다. */
    @Test
    void clockTimeResolvesToTodayWhenItHasAlreadyPassed() {
        Instant expected = Instant.parse("2026-08-15T18:00:00Z"); // 서울 8/16 03:00
        assertEquals(expected, TimeToken.parse("03:00", NOON, SEOUL));
    }

    /** 아직 오지 않은 시각을 말하면 어제를 뜻한다. 미래 시점을 돌려주면 아무 백업도 못 찾는다. */
    @Test
    void clockTimeFallsBackToYesterdayWhenItHasNotHappenedYet() {
        Instant expected = Instant.parse("2026-08-15T09:00:00Z"); // 서울 8/15 18:00
        assertEquals(expected, TimeToken.parse("18:00", NOON, SEOUL));
    }

    @Test
    void absoluteTimestampIsTakenAsIs() {
        assertEquals(Instant.parse("2026-08-14T18:30:00Z"),
                TimeToken.parse("2026-08-15 03:30", NOON, SEOUL));
    }

    /** 시각이 아닌 것은 null 이어야 한다. 그래야 호출자가 ID·번호로 해석한다. */
    @Test
    void nonTimeTokensAreRejected() {
        assertNull(TimeToken.parse("latest", NOON, SEOUL));
        assertNull(TimeToken.parse("#3", NOON, SEOUL));
        assertNull(TimeToken.parse("20260816-030000", NOON, SEOUL), "백업 ID 를 시각으로 오해하면 안 된다");
        assertNull(TimeToken.parse("", NOON, SEOUL));
        assertNull(TimeToken.parse(null, NOON, SEOUL));
        assertNull(TimeToken.parse("9x", NOON, SEOUL));
    }

    @Test
    void impossibleClockValuesAreRejected() {
        assertNull(TimeToken.parse("25:00", NOON, SEOUL));
        assertNull(TimeToken.parse("03:75", NOON, SEOUL));
        assertNull(TimeToken.parse("2026-13-40 03:00", NOON, SEOUL));
    }
}
