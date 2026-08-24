package io.github.yj.worldbackup.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "언제로 되돌릴지"를 사람이 말하는 대로 받는다.
 *
 * <p>테러를 발견했을 때 관리자가 아는 것은 백업 ID 가 아니라 <b>시각</b>이다.
 * "새벽 3시쯤", "9시간 전쯤" 이지 {@code 20260816-030000} 이 아니다. 목록 번호({@code #12})는
 * 백업이 하나 생기거나 정리될 때마다 밀려서, 급한 상황에 엉뚱한 시점을 고르기 쉽다.</p>
 *
 * <p>지원 형식</p>
 * <ul>
 *   <li>{@code 30m}, {@code 9h}, {@code 2d} - 그만큼 전</li>
 *   <li>{@code 03:00} - 가장 가까운 과거의 그 시각 (오늘 아직 안 지났으면 어제)</li>
 *   <li>{@code 2026-08-16 03:00} - 절대 시각</li>
 * </ul>
 */
public final class TimeToken {

    private static final Pattern RELATIVE = Pattern.compile("^(\\d{1,5})([mhd])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final Pattern ABSOLUTE =
            Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{1,2}):(\\d{2})$");

    private TimeToken() {
    }

    /**
     * 토큰을 시각으로 바꾼다.
     *
     * @return 시각. 시각 표현이 아니면 {@code null} (그러면 호출자가 ID/번호로 해석한다)
     */
    public static Instant parse(String token, Instant now, ZoneId zone) {
        if (token == null || token.isBlank()) return null;
        String trimmed = token.trim().toLowerCase(Locale.ROOT);

        Matcher relative = RELATIVE.matcher(trimmed);
        if (relative.matches()) {
            long amount = Long.parseLong(relative.group(1));
            return switch (relative.group(2)) {
                case "m" -> now.minus(Duration.ofMinutes(amount));
                case "h" -> now.minus(Duration.ofHours(amount));
                default -> now.minus(Duration.ofDays(amount));
            };
        }

        Matcher clock = CLOCK.matcher(trimmed);
        if (clock.matches()) {
            int hour = Integer.parseInt(clock.group(1));
            int minute = Integer.parseInt(clock.group(2));
            if (hour > 23 || minute > 59) return null;
            LocalDate today = LocalDate.ofInstant(now, zone);
            LocalDateTime candidate = LocalDateTime.of(today, LocalTime.of(hour, minute));
            Instant instant = candidate.atZone(zone).toInstant();
            // 오늘 그 시각이 아직 오지 않았다면 어제를 말한 것이다.
            return instant.isAfter(now) ? instant.minus(Duration.ofDays(1)) : instant;
        }

        Matcher absolute = ABSOLUTE.matcher(trimmed);
        if (absolute.matches()) {
            try {
                LocalDateTime when = LocalDateTime.of(
                        Integer.parseInt(absolute.group(1)),
                        Integer.parseInt(absolute.group(2)),
                        Integer.parseInt(absolute.group(3)),
                        Integer.parseInt(absolute.group(4)),
                        Integer.parseInt(absolute.group(5)));
                return when.atZone(zone).toInstant();
            } catch (RuntimeException e) {
                return null; // 13월 40일 같은 것
            }
        }
        return null;
    }

    public static Instant parse(String token) {
        return parse(token, Instant.now(), Clock.zone());
    }
}
