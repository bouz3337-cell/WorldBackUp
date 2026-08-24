package io.github.yj.worldbackup.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 이 플러그인이 시각을 적을 때 쓰는 <b>단 하나의 시계</b>.
 *
 * <p>백업 ID, 목록에 보이는 시각, {@code replaced/} 폴더 이름, OneBack 파일 이름이 모두
 * 여기서 나온다. 한 곳에 모아 두지 않으면 어떤 것은 서버 시계로, 어떤 것은 설정한 시계로
 * 적히면서 <b>같은 백업이 두 시각을 갖게</b> 된다.</p>
 *
 * <p>기본값은 서버가 쓰는 시계다. 그런데 호스팅 컨테이너는 대개 UTC 로 돌아서, 한국에서
 * 보면 시각이 9시간 뒤로 적힌다 - 저녁에 만든 백업이 <b>어제 날짜</b>로 보인다. 사고 직후에
 * "몇 시 시점으로 되돌릴지" 고르는 도구에서 그 어긋남은 그냥 넘길 일이 아니다.</p>
 *
 * <p>가장 좋은 해결은 서버 자체의 시간대를 맞추는 것이다({@code -Duser.timezone=Asia/Seoul}).
 * 그러면 마인크래프트 로그 시각까지 함께 맞는다. 그럴 수 없는 환경을 위해 이 플러그인만이라도
 * 맞출 수 있게 열어 둔다.</p>
 *
 * <p><b>정적 상태인 이유</b> - 시각을 적는 자리가 아홉 군데인데 그중 여럿이 static 이고,
 * 복원({@code onLoad})은 설정이 로드되기도 전에 폴더 이름을 짓는다. 설정 객체를 그 모든
 * 곳에 실어 나르는 것보다, 표시용 시계 하나를 여기 두는 편이 실수할 자리가 적다.</p>
 */
public final class Clock {

    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    /** 메인 스레드({@code /wb reload})가 갈아 끼우고 백업 스레드가 읽는다. */
    private static volatile ZoneId zone = ZoneId.systemDefault();

    private Clock() {
    }

    /**
     * 이 플러그인이 쓸 시계를 정한다.
     *
     * @param id {@code Asia/Seoul} 같은 시간대 이름. 비어 있거나 알아볼 수 없으면 서버 시계를 쓴다
     * @return 실제로 적용된 시간대
     */
    public static ZoneId use(String id) {
        if (id == null || id.isBlank()) {
            zone = ZoneId.systemDefault();
            return zone;
        }
        try {
            zone = ZoneId.of(id.trim());
        } catch (Exception e) {
            // 오타 하나로 시작을 막지 않는다. 서버 시계로 도는 편이 안 뜨는 것보다 낫다.
            zone = ZoneId.systemDefault();
        }
        return zone;
    }

    /** 알아볼 수 있는 시간대 이름인지. 설정을 읽을 때 경고를 띄우는 데 쓴다. */
    public static boolean known(String id) {
        if (id == null || id.isBlank()) return true; // 비워 두는 것은 "서버 시계" 라는 뜻이다
        try {
            ZoneId.of(id.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static ZoneId zone() {
        return zone;
    }

    /** 백업 ID·폴더 이름에 쓰는 {@code yyyyMMdd-HHmmss}. */
    public static String id(Instant at) {
        return ID.withZone(zone).format(at);
    }

    /** 사람에게 보여 주는 {@code yyyy-MM-dd HH:mm:ss}. */
    public static String display(Instant at) {
        return DISPLAY.withZone(zone).format(at);
    }

    /** {@code HH:mm}. */
    public static String hourMinute(Instant at) {
        return HOUR_MINUTE.withZone(zone).format(at);
    }

    /**
     * 백업 ID 를 다시 시각으로 읽는다. 메타데이터를 읽지 못했을 때의 마지막 수단이다.
     *
     * <p>ID 에는 시간대가 적혀 있지 않으므로 <b>지금 설정된 시계</b>로 읽는다. 시간대를
     * 바꾼 뒤라면 그 전에 만든 백업의 시각이 그만큼 어긋나 보인다. 그래도 이 경로는
     * 메타데이터와 사이드카를 둘 다 읽지 못한 <b>손상된 백업</b>에서만 쓰이므로, 몇 시간
     * 어긋난 시각이라도 있는 편이 없는 것보다 낫다.</p>
     *
     * @throws java.time.format.DateTimeParseException 형식이 다르면
     */
    public static Instant parseId(String id) {
        return ID.withZone(zone).parse(id, Instant::from);
    }

    /** 이 시각이 속한 날짜. {@code keep-daily} 와 날짜별 목록이 이 경계를 쓴다. */
    public static LocalDate date(Instant at) {
        return LocalDate.ofInstant(at, zone);
    }

    /** 오늘. */
    public static LocalDate today() {
        return LocalDate.now(zone);
    }
}
