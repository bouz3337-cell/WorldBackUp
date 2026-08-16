package io.github.yj.worldbackup.backup;

import java.util.Locale;

public enum BackupType {

    SCHEDULED("자동", "<aqua>"),
    MANUAL("수동", "<green>"),
    STARTUP("시작", "<gray>"),
    SHUTDOWN("종료", "<gray>"),
    PRE_RESTORE("복원 직전", "<gold>");

    private final String korean;
    private final String color;

    BackupType(String korean, String color) {
        this.korean = korean;
        this.color = color;
    }

    public String korean() {
        return korean;
    }

    public String color() {
        return color;
    }

    /** 보관 정책에서 자동 삭제로부터 보호할 종류인지. */
    public boolean manualLike() {
        return this == MANUAL || this == PRE_RESTORE;
    }

    public static BackupType parse(String value) {
        if (value == null) return SCHEDULED;
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SCHEDULED;
        }
    }
}
