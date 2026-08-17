package io.github.yj.worldbackup.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GB 로 적는 설정값의 해석.
 *
 * <p>이 두 값은 <b>디스크가 가득 차기 전에 멈추는 브레이크</b>다. 조용히 0 이 되면 브레이크가
 * 없는 것과 같은데, 하필 공간이 빠듯한 서버에서만 그렇게 되고 아무 경고도 없다. 백업이 디스크를
 * 끝까지 채운 뒤에야 드러난다.</p>
 *
 * <p>{@code min-free-disk-gb} 가 실제로 그랬다 - {@code getLong} 으로 읽어서 {@code 0.5} 를
 * 적으면 0 이 됐다. 같은 파일에서 {@code max-total-size-gb} 는 소수점을 받는다고 문서에 적혀
 * 있었으니, 둘을 같은 규칙으로 묶고 경계를 못 박는다.</p>
 */
class SizeSettingsTest {

    @TempDir
    Path tmp;

    private static final long GB = 1024L * 1024L * 1024L;

    /** 512MB 를 적었으면 512MB 여야 한다. 0 이 되면 브레이크가 사라진다. */
    @Test
    void halfAGigabyteIsNotRoundedDownToNothing() {
        BackupSettings settings = settings(cfg -> {
            cfg.set("retention.min-free-disk-gb", 0.5);
            cfg.set("retention.max-total-size-gb", 0.5);
        });

        assertEquals(GB / 2, settings.minFreeDiskBytes());
        assertEquals(GB / 2, settings.maxTotalBytes());
    }

    /** 정수로 적은 기존 설정은 그대로 동작해야 한다. */
    @Test
    void wholeGigabytesStillWork() {
        BackupSettings settings = settings(cfg -> {
            cfg.set("retention.min-free-disk-gb", 5);
            cfg.set("retention.max-total-size-gb", 40);
        });

        assertEquals(5 * GB, settings.minFreeDiskBytes());
        assertEquals(40 * GB, settings.maxTotalBytes());
    }

    /** 기본값: 여유 5GB 를 요구하고, 총 용량 상한은 없다. */
    @Test
    void defaultsAreUnchanged() {
        BackupSettings settings = settings(cfg -> {
        });

        assertEquals(5 * GB, settings.minFreeDiskBytes());
        assertEquals(0L, settings.maxTotalBytes(), "0 = 무제한");
    }

    /** 음수나 이상한 값으로 브레이크가 거꾸로 걸리지 않게 한다. */
    @Test
    void nonsenseValuesMeanNoLimitInsteadOfANegativeOne() {
        assertEquals(0L, BackupSettings.gigabytesToBytes(-1));
        assertEquals(0L, BackupSettings.gigabytesToBytes(0));
        assertEquals(0L, BackupSettings.gigabytesToBytes(Double.NaN));

        BackupSettings settings = settings(cfg -> cfg.set("retention.min-free-disk-gb", -3));
        assertEquals(0L, settings.minFreeDiskBytes());
    }

    /** 아주 작은 값도 0 으로 뭉개지 않는다. (1MB) */
    @Test
    void aTinyValueIsStillHonoured() {
        assertTrue(BackupSettings.gigabytesToBytes(1.0 / 1024) > 0);
        assertEquals(1024L * 1024L, BackupSettings.gigabytesToBytes(1.0 / 1024));
    }

    private BackupSettings settings(Consumer<YamlConfiguration> tweak) {
        YamlConfiguration cfg = new YamlConfiguration();
        tweak.accept(cfg);
        return BackupSettings.load(cfg,
                tmp.resolve("server/plugins/WorldBackUp"), tmp.resolve("server"));
    }
}
