package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.util.FileUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * <b>이 폴더 하나만 챙기면 아무 데서나 서버를 다시 열 수 있는</b> 아카이브.
 *
 * <p>평소 백업({@link BackupService})과 목적이 다르다. 그쪽은 "이 서버를 어제로 되돌리기" 라
 * 서버 폴더가 이미 있다고 보고 월드와 설정만 담는다. 서버 폴더가 통째로 사라지는 상황 -
 * 디스크 고장, 호스팅 계정 정지, 실수로 삭제, 업체 이전 - 에는 그것만으로 부족하다.
 * 서버 jar 도 {@code eula.txt} 도 플러그인 jar 도 없기 때문이다.</p>
 *
 * <p>그래서 OneBack 은 <b>서버 폴더에 있는 것을 통째로</b> 담는다. 로그와 자기 자신만 뺀다.
 * {@code libraries/} 와 {@code versions/} 까지 담는 것은 일부러다 - 그게 있어야 인터넷 없이도
 * 그 자리에서 서버가 뜬다.</p>
 *
 * <p>폴더에는 아카이브와 함께 {@code 읽어주세요.txt} 를 남긴다. 몇 달 뒤 그 폴더만 발견한
 * 사람이 <b>zip 을 풀지 않고도</b> 무엇인지, 어떻게 다시 여는지 알 수 있어야 한다.</p>
 */
public final class OneBack {

    /** 아카이브 이름 앞부분. 사람이 폴더만 보고 무엇인지 알아볼 수 있어야 한다. */
    public static final String PREFIX = "oneback-";
    public static final String SUFFIX = ".zip";

    /** 다시 여는 방법을 적어 둔 안내문. 아카이브 밖에 둔다 - 풀기 전에 읽어야 하므로. */
    public static final String GUIDE_NAME = "읽어주세요.txt";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /**
     * 여유가 이만큼은 더 남아야 시작한다.
     *
     * <p>평소 백업({@code RESTORE_HEADROOM} 과 같은 뜻)보다 크게 잡는다. OneBack 은 서버
     * 폴더 크기만큼을 한 번에 쓰는, 이 플러그인에서 디스크를 가장 많이 먹는 작업이다.
     * 도중에 디스크가 차면 남는 것은 못 쓰는 조각뿐이 아니라 - <b>서버가 멈춘다.</b>
     * 마인크래프트는 청크를 쓰지 못하면 그 자리에서 죽는다.</p>
     */
    private static final long HEADROOM_BYTES = 256L * 1024 * 1024;

    private OneBack() {
    }

    /**
     * 시작해도 되는 공간이 있는지.
     *
     * <p>{@code public} 인 이유는 하나뿐이다 - 이 판단이 <b>잘못 참을 내면</b> 디스크를 채워
     * 서버를 멈추고, <b>잘못 거짓을 내면</b> 정작 서버를 옮기려는 순간에 막는다. 양쪽 다
     * 비싸므로 경계를 테스트로 못 박아 둔다. ({@code BackupService#hasAmpleRoom},
     * {@code RestoreApplier#hasRoomToRestore} 와 같은 규칙이다)</p>
     *
     * <p>담을 양은 <b>압축 전</b> 크기다. 서버 폴더의 큰 부분(jar·region)은 이미 압축된
     * 형식이라 그대로 담기므로, 이 어림은 실제와 크게 다르지 않다. 모자라게 잡느니 넉넉히
     * 잡는 편이 맞다 - 도중에 끊기는 것이 시작하지 않는 것보다 나쁘다.</p>
     */
    public static boolean hasRoom(long neededBytes, long freeBytes) {
        if (neededBytes <= 0) return true;
        long required = neededBytes + HEADROOM_BYTES;
        if (required < 0) return true; // 넘쳤다 - 막을 근거가 없다
        return freeBytes >= required;
    }

    /** 만들어진 결과. */
    public record Result(Path archive, long archiveBytes, long originalBytes, int fileCount, long elapsedMillis) {
    }

    public static Path archiveFor(Path directory, Instant at) {
        return directory.resolve(PREFIX + STAMP.format(at) + SUFFIX);
    }

    /** 이 폴더에 있는 OneBack 아카이브들. 최신순. */
    public static List<Path> list(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
                    })
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 파일 이름에 박아 둔 만든 시각.
     *
     * <p>파일 수정 시각을 쓰지 않는 이유는, 이 파일은 <b>옮겨 다니라고</b> 만든 것이기
     * 때문이다. 다른 디스크나 USB 로 복사하면 수정 시각은 복사한 때가 되어 "언제 시점의
     * 서버인가" 를 잃는다. 이름은 어디로 가든 그대로다.</p>
     *
     * @return 알아볼 수 없는 이름이면 비어 있음
     */
    public static Optional<String> displayTime(Path archive) {
        String name = archive.getFileName().toString();
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return Optional.empty();
        String stamp = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        // 같은 초에 두 번 만들면 뒤에 "-2" 가 붙는다. 시각 부분만 본다.
        if (stamp.length() < 15) return Optional.empty();
        String date = stamp.substring(0, 8);
        String time = stamp.substring(9, 15);
        if (!date.matches("\\d{8}") || !time.matches("\\d{6}")) return Optional.empty();
        return Optional.of(date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8)
                + " " + time.substring(0, 2) + ":" + time.substring(2, 4) + ":" + time.substring(4, 6));
    }

    /**
     * 중간에 끊긴 아카이브 조각을 치운다.
     *
     * <p>압축 중에 서버가 죽으면 {@code .zip.tmp} 가 남는다. 이 플러그인이 만드는 것 중
     * <b>가장 큰 파일</b>이다 - 서버 폴더 크기만 하다. 게다가 완성된 아카이브가 아니라
     * 쓸모도 없다. 평소 백업의 조각 정리({@code BackupRepository#cleanupOrphans})는 백업
     * 폴더만 보므로 이쪽은 아무도 치우지 않아, 그대로 두면 디스크에 서버 한 벌이 영영
     * 눌러앉는다.</p>
     *
     * @return 지운 파일 수
     */
    public static int cleanupTemp(Path directory, Logger log) {
        if (!Files.isDirectory(directory)) return 0;
        int removed = 0;
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX + Archiver.TEMP_SUFFIX)) continue;
                try {
                    long size = Files.size(path);
                    Files.delete(path);
                    removed++;
                    log.info("[OneBack] 중간에 끊긴 조각을 치웠습니다: " + name
                            + " (" + FileUtil.humanBytes(size) + ")");
                } catch (IOException e) {
                    log.warning("[OneBack] 끊긴 조각을 지우지 못했습니다: " + name);
                }
            }
        } catch (IOException e) {
            return removed;
        }
        return removed;
    }

    /** 이 폴더가 들고 있는 OneBack 아카이브의 총 크기. */
    public static long totalBytes(Path directory) {
        long total = 0L;
        for (Path archive : list(directory)) {
            try {
                total += Files.size(archive);
            } catch (IOException ignored) {
            }
        }
        return total;
    }

    /**
     * 최근 {@code keep} 개만 남긴다.
     *
     * <p>하나가 서버 폴더 크기만큼이라, 이 정리가 없으면 디스크를 가장 빨리 채우는 것이 된다.</p>
     *
     * @return 지운 파일 수
     */
    public static int prune(Path directory, int keep, Logger log) {
        List<Path> all = list(directory);
        if (keep <= 0 || all.size() <= keep) return 0;

        int deleted = 0;
        for (Path old : all.subList(keep, all.size())) {
            try {
                long size = Files.size(old);
                Files.delete(old);
                deleted++;
                log.info("[OneBack] 오래된 아카이브를 정리했습니다: " + old.getFileName()
                        + " (" + FileUtil.humanBytes(size) + ")");
            } catch (IOException e) {
                log.warning("[OneBack] 오래된 아카이브를 지우지 못했습니다: " + old.getFileName());
            }
        }
        return deleted;
    }

    /**
     * 서버 폴더를 통째로 담는다. <b>비동기 스레드에서 부른다.</b>
     *
     * <p>월드를 얼리고 청크를 내려쓰는 것은 호출자({@link BackupService})가 이미 해 두었다는
     * 전제다. 그래야 평소 백업과 <b>같은 방식으로</b> 일관된 스냅샷이 된다.</p>
     */
    static Result create(Path archive,
                         Path serverRoot,
                         BackupSettings settings,
                         String requestedBy,
                         java.util.function.Consumer<String> progress,
                         Logger log) throws IOException {
        long startedAt = System.currentTimeMillis();
        Files.createDirectories(archive.getParent());

        List<Path> targets = targets(serverRoot, settings, log);
        if (targets.isEmpty()) {
            throw new IOException("서버 폴더에 담을 것이 없습니다: " + serverRoot);
        }

        // 진행률 분모. 서버 폴더를 한 번 훑는 비용이 있지만, OneBack 은 자주 도는 작업이
        // 아니고 (기본값은 수동), 이게 없으면 몇 GB 짜리 작업이 아무 표시 없이 도는 것이 된다.
        FileUtil.Sizes sizes = FileUtil.Sizes.ZERO;
        for (Path target : targets) {
            sizes = sizes.plus(FileUtil.measure(target, serverRoot, settings.oneBackExclude(), null));
        }
        long expected = sizes.totalBytes();
        long free = FileUtil.usableSpace(archive.getParent());
        log.info("[OneBack] 담을 양 " + FileUtil.humanBytes(expected)
                + " · 남은 공간 " + FileUtil.humanBytes(free));
        if (!hasRoom(expected, free)) {
            throw new IOException("디스크 여유 공간이 부족해 OneBack 을 만들지 않았습니다. 필요: "
                    + FileUtil.humanBytes(expected + HEADROOM_BYTES)
                    + ", 남음: " + FileUtil.humanBytes(free)
                    + " - 다른 디스크를 쓰시려면 oneback.directory 를 절대 경로로 지정하거나,"
                    + " oneback.keep 을 줄여 옛 아카이브를 정리하세요.");
        }

        Instant created = Instant.now();
        Archiver.Result result = Archiver.create(
                archive,
                serverRoot,
                targets,
                settings.compressionLevel(),
                settings.oneBackExclude(),
                null, // 차등이 아니다. OneBack 은 언제나 한 벌로 완결된다.
                expected,
                (fileCount, originalBytes) -> meta(created, requestedBy, fileCount, originalBytes),
                progress,
                log);

        return new Result(archive, result.archiveBytes(), result.originalBytes(), result.fileCount(),
                System.currentTimeMillis() - startedAt);
    }

    /**
     * 담을 대상 - 서버 폴더의 <b>바로 아래 항목들</b>.
     *
     * <p>서버 폴더 자체를 대상으로 넘기면 안 된다. 아카이브 안의 이름은 서버 폴더 기준
     * 상대 경로인데, 서버 폴더 자신의 상대 경로는 빈 문자열이라 "서버 폴더 밖" 과 구분되지
     * 않는다. 실제로 그렇게 넘겨 보면 <b>아무것도 담기지 않은 채</b> 아카이브가 성공으로
     * 끝난다 - 백업에서 가장 나쁜 실패다. 한 단 내려서 넘기면 그 자리가 사라진다.</p>
     *
     * <p>여기서 제외 패턴을 한 번 걸러 두는 것은 비용 때문이다. 안 걸러도 결과는 같지만,
     * {@code backups/} 처럼 큰 폴더를 파일 하나하나 훑어 보고 버리게 된다.</p>
     */
    private static List<Path> targets(Path serverRoot, BackupSettings settings, Logger log) throws IOException {
        List<Path> targets = new ArrayList<>();
        try (Stream<Path> children = Files.list(serverRoot)) {
            for (Path child : children.sorted().toList()) {
                String relative = FileUtil.relativize(serverRoot, child);
                if (relative == null) continue;
                boolean directory = Files.isDirectory(child);
                if (directory ? settings.oneBackExclude().matchesDirectory(relative)
                        : settings.oneBackExclude().matchesFile(relative)) {
                    log.info("[OneBack] 제외: " + relative);
                    continue;
                }
                targets.add(child);
            }
        }
        return targets;
    }

    /** 아카이브 안에 남기는 메타데이터. {@code /wb oneback} 목록과 사람 눈 모두를 위한 것이다. */
    private static String meta(Instant created, String requestedBy, int fileCount, long originalBytes) {
        return "kind: oneback\n"
                + "created-at: " + created.toEpochMilli() + "\n"
                + "created-at-text: " + BackupEntry.DISPLAY_FORMAT.format(created) + "\n"
                + "requested-by: " + requestedBy + "\n"
                + "file-count: " + fileCount + "\n"
                + "original-bytes: " + originalBytes + "\n";
    }

    /** 압축 풀기 도우미 파일 이름. FTP·호스팅 패널이 이름을 망가뜨리지 않도록 ASCII 로 둔다. */
    public static final String UNPACK_BAT = "unpack.bat";
    public static final String UNPACK_SH = "unpack.sh";

    /**
     * 압축을 대신 풀어 주는 스크립트를 폴더에 남긴다.
     *
     * <p>서버가 날아간 뒤 이 폴더를 여는 사람에게 "zip 을 빈 폴더에 푸세요" 는 생각보다 큰
     * 벽이다. 윈도우 탐색기에서 zip 을 더블클릭하면 <b>푼 것처럼 보이지만 실제로는 안을
     * 들여다본 것뿐</b>이고, 그 상태로 파일을 끌어다 놓으면 몇 GB 를 옮기다 중간에 멈추기도
     * 한다. 서버가 뜨지 않는 이유가 그것인지 알아채기도 어렵다.</p>
     *
     * <p>그래서 더블클릭 한 번으로 <b>새 폴더에</b> 제대로 풀어 준다. 이미 있는 폴더에는
     * 절대 풀지 않는다 - 돌아가고 있는 서버 위에 덮어쓰는 것이 이 도우미가 만들 수 있는
     * 가장 나쁜 결과다.</p>
     */
    static void writeUnpackScripts(Path directory, Logger log) {
        write(directory.resolve(UNPACK_BAT), BAT, log);
        Path sh = directory.resolve(UNPACK_SH);
        write(sh, SH, log);
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(sh);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(sh, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 윈도우에는 실행 권한이라는 개념이 없다. sh 로 실행하면 되므로 문제가 아니다.
        }
    }

    private static void write(Path file, String body, Logger log) {
        try {
            Files.writeString(file, body.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("[OneBack] " + file.getFileName() + " 을 남기지 못했습니다: " + e.getMessage());
        }
    }

    /**
     * 윈도우용.
     *
     * <p>{@code chcp 65001} 을 먼저 세우지 않으면 아래 한글이 깨져 나온다. 그리고 압축 해제는
     * 파워셸에 맡긴다 - 윈도우에 기본으로 있고, 외부 프로그램을 받게 하지 않아도 된다.</p>
     */
    private static final String BAT = """
            @echo off
            chcp 65001 >nul
            setlocal enabledelayedexpansion
            cd /d "%~dp0"

            echo ================================================
            echo  OneBack - 서버 압축 풀기
            echo ================================================
            echo.

            set "ZIP="
            for /f "delims=" %%f in ('dir /b /o-n oneback-*.zip 2^>nul') do (
                set "ZIP=%%f"
                goto :found
            )
            :found
            if "!ZIP!"=="" (
                echo [!] 이 폴더에 oneback-*.zip 파일이 없습니다.
                echo     이 파일을 zip 과 같은 폴더에 두고 다시 실행하세요.
                pause
                exit /b 1
            )

            for /f "tokens=1-3 delims=-." %%a in ("!ZIP!") do set "STAMP=%%b-%%c"
            set "DEST=%~dp0server-!STAMP!"

            if exist "!DEST!" (
                echo [!] 이미 폴더가 있습니다: !DEST!
                echo     덮어쓰지 않습니다. 그 폴더를 옮기거나 이름을 바꾼 뒤 다시 실행하세요.
                pause
                exit /b 1
            )

            echo  푸는 파일 : !ZIP!
            echo  푸는 위치 : !DEST!
            echo.
            echo  용량이 크면 몇 분 걸립니다. 창을 닫지 마세요...
            echo.

            powershell -NoProfile -ExecutionPolicy Bypass -Command ^
                "Expand-Archive -LiteralPath '%~dp0!ZIP!' -DestinationPath '!DEST!' -Force"
            if errorlevel 1 (
                echo.
                echo [!] 압축을 푸는 데 실패했습니다.
                pause
                exit /b 1
            )

            echo.
            echo ================================================
            echo  다 됐습니다.
            echo ================================================
            echo.
            echo  1) !DEST! 폴더로 들어가세요.
            echo  2) server.properties 와 서버 jar 파일이 보이면 제대로 풀린 것입니다.
            echo  3) 그 폴더에서 아래처럼 서버를 켜세요.
            echo.
            echo       java -Xms4G -Xmx4G -jar 서버jar이름.jar nogui
            echo.
            echo  ( -Xmx4G 는 서버에 줄 메모리입니다. 형편에 맞게 고치세요 )
            echo.
            pause
            """;

    /** 리눅스·맥용. {@code unzip} 이 없는 환경이 흔해서 python3 도 함께 본다. */
    private static final String SH = """
            #!/bin/sh
            # OneBack - 서버 압축 풀기
            set -e
            cd "$(dirname "$0")"

            echo "================================================"
            echo " OneBack - 서버 압축 풀기"
            echo "================================================"
            echo

            ZIP=$(ls -1 oneback-*.zip 2>/dev/null | sort | tail -n 1 || true)
            if [ -z "$ZIP" ]; then
                echo "[!] 이 폴더에 oneback-*.zip 파일이 없습니다."
                echo "    이 파일을 zip 과 같은 폴더에 두고 다시 실행하세요."
                exit 1
            fi

            STAMP=$(echo "$ZIP" | sed -e 's/^oneback-//' -e 's/\\.zip$//')
            DEST="server-$STAMP"

            if [ -e "$DEST" ]; then
                echo "[!] 이미 폴더가 있습니다: $DEST"
                echo "    덮어쓰지 않습니다. 그 폴더를 옮기거나 이름을 바꾼 뒤 다시 실행하세요."
                exit 1
            fi

            echo " 푸는 파일 : $ZIP"
            echo " 푸는 위치 : $(pwd)/$DEST"
            echo
            echo " 용량이 크면 몇 분 걸립니다..."
            echo

            mkdir "$DEST"
            if command -v unzip >/dev/null 2>&1; then
                unzip -q "$ZIP" -d "$DEST"
            elif command -v python3 >/dev/null 2>&1; then
                python3 -c "import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$ZIP" "$DEST"
            else
                echo "[!] unzip 도 python3 도 없습니다. 둘 중 하나를 설치한 뒤 다시 실행하세요."
                echo "    (예: sudo apt install unzip)"
                rmdir "$DEST"
                exit 1
            fi

            echo
            echo "================================================"
            echo " 다 됐습니다."
            echo "================================================"
            echo
            echo " 1) $DEST 폴더로 들어가세요."
            echo " 2) server.properties 와 서버 jar 파일이 보이면 제대로 풀린 것입니다."
            echo " 3) 그 폴더에서 아래처럼 서버를 켜세요."
            echo
            echo "      java -Xms4G -Xmx4G -jar 서버jar이름.jar nogui"
            echo
            echo " ( -Xmx4G 는 서버에 줄 메모리입니다. 형편에 맞게 고치세요 )"
            echo
            """;

    /**
     * 폴더에 안내문을 남긴다.
     *
     * <p>이 파일이 이 기능의 절반이다. 서버가 날아간 뒤 이 폴더를 여는 사람은 대개 당황해
     * 있고, 어쩌면 만든 사람이 아닐 수도 있다. 그때 필요한 것은 zip 안이 아니라
     * <b>zip 옆에</b> 있어야 한다.</p>
     */
    static void writeGuide(Path directory, Path archive, String serverVersion, Logger log) {
        List<String> lines = new ArrayList<>();
        lines.add("이 폴더는 무엇인가요?");
        lines.add("===================================================================");
        lines.add("");
        lines.add("마인크래프트 서버 한 벌이 통째로 들어 있습니다.");
        lines.add("이 폴더의 zip 파일만 있으면 다른 컴퓨터에서도 서버를 그대로 다시 열 수 있습니다.");
        lines.add("(월드, 플러그인, 설정, 서버 jar 까지 전부 들어 있습니다)");
        lines.add("");
        lines.add("가장 최근 파일: " + archive.getFileName());
        lines.add("만든 서버 버전: " + serverVersion);
        lines.add("");
        lines.add("다시 여는 방법");
        lines.add("===================================================================");
        lines.add("");
        lines.add("■ 쉬운 방법 - 이 폴더의 도우미를 쓰세요");
        lines.add("");
        lines.add("   윈도우 : " + UNPACK_BAT + " 을 더블클릭");
        lines.add("   리눅스 : sh " + UNPACK_SH);
        lines.add("");
        lines.add("   알아서 새 폴더를 만들어 풀어 줍니다. 이미 있는 폴더에는 절대 풀지 않으니");
        lines.add("   돌아가는 서버를 덮어쓸 걱정은 없습니다.");
        lines.add("");
        lines.add("■ 직접 하는 방법");
        lines.add("");
        lines.add("1. 빈 폴더를 하나 만듭니다.");
        lines.add("2. 위 zip 파일을 그 폴더에 <풀어 놓습니다>.");
        lines.add("   (zip 파일을 그냥 옮기는 것이 아니라 압축을 푸는 것입니다.");
        lines.add("    윈도우 탐색기에서 zip 을 더블클릭하면 푼 것이 아니라 안을 들여다본 것뿐입니다)");
        lines.add("3. 푼 폴더 안에 server.properties 와 서버 jar 파일이 보이면 제대로 푼 것입니다.");
        lines.add("4. 그 폴더에서 서버를 실행합니다.");
        lines.add("");
        lines.add("   윈도우  : start.bat 을 만들어 아래 한 줄을 넣고 실행");
        lines.add("             java -Xms4G -Xmx4G -jar <서버jar이름>.jar nogui");
        lines.add("   리눅스  : java -Xms4G -Xmx4G -jar <서버jar이름>.jar nogui");
        lines.add("");
        lines.add("   -Xmx4G 는 서버에 줄 메모리입니다. 형편에 맞게 고치세요.");
        lines.add("");
        lines.add("알아 두실 점");
        lines.add("===================================================================");
        lines.add("");
        lines.add("- 접속 주소(IP)는 따라오지 않습니다. 새 환경의 주소로 접속해야 합니다.");
        lines.add("- server.properties 의 server-port 가 예전 그대로이니, 쓰던 포트가 막혀 있으면");
        lines.add("  그 값을 고치세요.");
        lines.add("- 로그와 크래시 기록은 일부러 담지 않았습니다. 서버를 여는 데 필요 없습니다.");
        lines.add("- 풀고 나면 worldbackup-meta.yml 과 worldbackup-files.txt 가 함께 보입니다.");
        lines.add("  이 아카이브에 무엇이 들었는지 적어 둔 목록입니다. 지우셔도 서버는 정상입니다.");
        lines.add("- 이 폴더 안의 zip 이 여러 개면 파일 이름의 날짜가 가장 최근인 것을 쓰세요.");
        lines.add("  (oneback-20260825-143000.zip 이면 2026년 8월 25일 14시 30분)");
        lines.add("");
        lines.add("이 파일은 OneBack 을 만들 때마다 새로 쓰입니다. 지우셔도 서버에는 지장이 없습니다.");

        try {
            Files.writeString(directory.resolve(GUIDE_NAME),
                    String.join(System.lineSeparator(), lines), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("[OneBack] 안내문을 남기지 못했습니다: " + e.getMessage());
        }
    }
}
