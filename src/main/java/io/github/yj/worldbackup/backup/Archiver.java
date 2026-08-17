package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.util.FileUtil;
import io.github.yj.worldbackup.util.GlobMatcher;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 대상 경로들을 하나의 zip 으로 묶는다. */
public final class Archiver {

    private static final int BUFFER_SIZE = 128 * 1024;
    private static final long PROGRESS_INTERVAL_MS = 5_000L;

    /**
     * 압축 중인 아카이브에 붙는 임시 확장자.
     *
     * <p>압축이 끝나야만 최종 이름으로 바뀌므로, 서버가 압축 도중 죽어도
     * 잘린 zip 이 정상 백업으로 오인되는 일이 없다.</p>
     */
    public static final String TEMP_SUFFIX = ".tmp";

    private Archiver() {
    }

    /**
     * @param originalBytes 스냅샷 전체 크기(기준 백업에서 재사용한 파일 포함)
     * @param fileCount     스냅샷 전체 파일 수
     * @param storedCount   이번 아카이브에 실제로 저장한 파일 수
     * @param storedBytes   이번 아카이브에 실제로 저장한 원본 바이트(압축 전)
     * @param plainBytes    이미 압축된 형식이라 <b>재압축하지 않은</b> 원본 바이트
     *                      ({@link #ALREADY_COMPRESSED} 참고)
     */
    public record Result(long archiveBytes,
                         long originalBytes,
                         int fileCount,
                         int skippedCount,
                         int storedCount,
                         long storedBytes,
                         long plainBytes) {
    }

    /**
     * 이미 압축되어 있어 다시 압축할 이유가 없는 확장자.
     *
     * <p>마인크래프트 월드는 용량의 대부분이 <b>이미 압축된 바이트</b>다. region 파일
     * ({@code .mca})은 청크마다 zlib 으로 눌러 담고, {@code .mcc} 는 그중 큰 청크를 따로
     * 뺀 것이다. 여기에 deflate 를 한 번 더 거는 것은 압축률 0%에 CPU 만 태우는 일이다.
     * 재 보면 이 차이가 백업 시간의 대부분을 설명한다 - 30MB 짜리 월드에서
     * region 부분만 레벨 0 대비 <b>14배</b>(47ms → 658ms) 느려지면서 크기는 그대로였다.</p>
     *
     * <p>그래서 이 확장자는 {@code compression-level} 과 무관하게 무압축으로 담고, 나머지는
     * 설정한 레벨대로 압축한다. 텍스트·JSON·YAML 은 98% 이상 줄어드는데 비용이 거의 없어서,
     * 실제로 이득이 나는 곳에만 압축을 쓰는 셈이 된다. 결과물 크기는 사실상 그대로다.</p>
     *
     * <p>{@code .dat}(gzip 된 NBT)은 <b>일부러 넣지 않았다.</b> 측정해 보면 4% 정도는 더 줄고,
     * 무엇보다 플러그인이 {@code .dat} 이라는 이름으로 평문을 쓰는 경우가 있다. 크기도
     * region 에 비하면 미미해서 얻을 것이 없다.</p>
     */
    private static final Set<String> ALREADY_COMPRESSED = Set.of(
            // 마인크래프트 청크 저장 형식 - 내부가 zlib/LZ4
            "mca", "mcc", "mcr",
            // 일반 압축 컨테이너
            "gz", "tgz", "bz2", "xz", "zst", "lz4", "zip", "jar", "7z", "rar",
            // 이미 압축된 미디어
            "png", "jpg", "jpeg", "gif", "webp", "ogg", "mp3", "mp4", "webm");

    /**
     * 백업 대상 파일을 여는 방법.
     *
     * <p>실서버에서 쓰이는 구현은 {@link Files#newInputStream} 하나뿐이다. 이 자리가 열려 있는
     * 이유는 테스트가 <b>"열기는 되고 읽기가 실패하는"</b> 상황을 만들어야 하기 때문이다. 그것이
     * 이 플러그인에서 가장 자주 일어나는 실패이고({@link #copyInto} 설명), 그 상황에서 무엇을
     * 매니페스트에 적는지가 백업의 정직성을 결정한다.</p>
     *
     * <p>예전에는 {@link java.nio.channels.FileLock} 으로 흉내 냈다. 그런데 그 잠금은
     * <b>윈도우에서만 강제</b>다 - 리눅스에서는 권고적이라 읽기가 그냥 성공하고, 그래서 정작
     * 이 플러그인이 대부분 돌아가는 플랫폼에서는 그 검증이 통째로 무력화됐다(CI 를 붙이자마자
     * 다섯 개가 그 이유로 깨졌다). 배포 대상 플랫폼에서 검증하지 못하는 보장은 보장이 아니다.</p>
     */
    @FunctionalInterface
    public interface FileOpener {
        InputStream open(Path file) throws IOException;
    }

    /** 실서버 구현. */
    private static final FileOpener REAL_FILES = Files::newInputStream;

    /**
     * @param base          차등 백업의 기준이 되는 매니페스트. null 이면 전체 백업.
     * @param expectedBytes 진행률 계산용 예상 크기(0 이면 진행률 대신 누적 용량만 표시)
     * @param metaProvider  (파일 수, 원본 바이트) -> zip 안에 넣을 메타데이터 YAML 문자열
     */
    public static Result create(Path archive,
                                Path serverRoot,
                                List<Path> targets,
                                int compressionLevel,
                                GlobMatcher exclude,
                                Manifest base,
                                long expectedBytes,
                                BiFunction<Integer, Long, String> metaProvider,
                                Consumer<String> progress,
                                Logger log) throws IOException {
        return create(archive, serverRoot, targets, compressionLevel, exclude, base, expectedBytes,
                metaProvider, progress, log, REAL_FILES);
    }

    /** @param opener 파일을 여는 방법. 읽기 실패를 주입하는 테스트만 이 자리를 쓴다. {@link FileOpener} */
    public static Result create(Path archive,
                                Path serverRoot,
                                List<Path> targets,
                                int compressionLevel,
                                GlobMatcher exclude,
                                Manifest base,
                                long expectedBytes,
                                BiFunction<Integer, Long, String> metaProvider,
                                Consumer<String> progress,
                                Logger log,
                                FileOpener opener) throws IOException {

        Files.createDirectories(archive.getParent());

        Path temp = archive.resolveSibling(archive.getFileName().toString() + TEMP_SUFFIX);
        Files.deleteIfExists(temp);

        Counter counter = new Counter(expectedBytes, progress);
        byte[] buffer = new byte[BUFFER_SIZE];
        long plainBytes = 0L;

        try {
            try (OutputStream fileOut = Files.newOutputStream(temp);
                 BufferedOutputStream buffered = new BufferedOutputStream(fileOut, BUFFER_SIZE);
                 ZipOutputStream zip = new ZipOutputStream(buffered, StandardCharsets.UTF_8)) {

                // 이미 압축된 파일은 재압축을 건너뛴다. 크기는 그대로인데 CPU 는 몇 배로 준다.
                Compression compression = new Compression(zip, compressionLevel);

                for (Path target : targets) {
                    if (!Files.exists(target)) continue;
                    String relative = FileUtil.relativize(serverRoot, target);
                    if (relative == null) {
                        log.warning("[백업] 서버 폴더 밖의 경로라 건너뜁니다: " + target);
                        continue;
                    }
                    if (Files.isRegularFile(target)) {
                        if (exclude.matchesFile(relative)) continue;
                        try {
                            addFile(zip, target, relative, Files.readAttributes(target, BasicFileAttributes.class),
                                    base, buffer, counter, compression, log, opener);
                        } catch (IOException e) {
                            counter.skipped++;
                            log.log(Level.WARNING, "[백업] 파일을 읽지 못해 건너뜁니다: " + relative, e);
                        }
                    } else {
                        walkDirectory(zip, serverRoot, target, exclude, base, buffer, counter, compression, log, opener);
                    }
                }

                // 매니페스트와 메타데이터는 파일 수/용량이 확정된 뒤 마지막에 기록한다.
                // 둘 다 텍스트라 압축이 잘 먹으므로 설정한 레벨로 되돌려 놓고 쓴다.
                // 매니페스트는 통째로 문자열을 만들지 않고 zip 스트림에 바로 흘려보낸다.
                compression.prepare(Manifest.ENTRY, 0L);
                zip.putNextEntry(new ZipEntry(Manifest.ENTRY));
                counter.manifest.writeTo(zip);
                zip.closeEntry();
                compression.prepare(BackupEntry.META_ENTRY, 0L);
                writeTextEntry(zip, BackupEntry.META_ENTRY,
                        metaProvider.apply(counter.files, counter.originalBytes));
                plainBytes = compression.plainBytes;
            }

            // 여기까지 왔다는 건 zip 이 정상적으로 닫혔다는 뜻이다. 이제서야 최종 이름을 준다.
            moveIntoPlace(temp, archive);

        } catch (Throwable t) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
            throw t;
        }

        long archiveBytes = Files.size(archive);
        return new Result(archiveBytes, counter.originalBytes, counter.files, counter.skipped,
                counter.stored, counter.storedBytes, plainBytes);
    }

    private static void writeTextEntry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** 같은 폴더 안 이동이라 대부분 원자적이다. 지원하지 않는 파일 시스템이면 일반 이동으로 되돌린다. */
    private static void moveIntoPlace(Path temp, Path archive) throws IOException {
        try {
            Files.move(temp, archive, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, archive, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void walkDirectory(ZipOutputStream zip,
                                      Path serverRoot,
                                      Path root,
                                      GlobMatcher exclude,
                                      Manifest base,
                                      byte[] buffer,
                                      Counter counter,
                                      Compression compression,
                                      Logger log,
                                      FileOpener opener) throws IOException {
        // 파일마다 Path 를 정규화하는 대신 뿌리 기준으로 이어 붙인다. 같은 트리를 용량 측정에서
        // 한 번 더 훑으므로 이 비용은 파일 수 × 2 로 들어온다.
        FileUtil.RelativePaths relatives = FileUtil.RelativePaths.under(serverRoot, root);

        Files.walkFileTree(root, FileUtil.walkOptions(root), Integer.MAX_VALUE, new SimpleFileVisitor<>() {

            /**
             * 폴더별로 "이 아래에 zip 엔트리가 하나라도 쓰였는지" 를 센다.
             *
             * <p>폴더가 zip 에 남는 유일한 경로는 그 안의 파일 엔트리다. 그래서 아무것도 쓰이지
             * 않은 폴더는 복원 때 되살아나지 못한다. 예전에는 {@code preVisitDirectory} 에서
             * 폴더를 직접 열어 비어 있는지만 확인했는데, 그러면 <b>제외 패턴에 전부 걸린 폴더</b>
             * (예: {@code *.lock} 만 들어 있는 폴더)를 "비어 있지 않다" 고 보고 그냥 지나쳐
             * 복원 후 폴더가 사라졌다. 다 훑고 난 뒤에 판단하면 두 경우가 한 번에 해결되고,
             * 폴더마다 따로 열던 비용도 없어진다.</p>
             */
            private final Deque<int[]> written = new ArrayDeque<>();

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String relative = relative(dir);
                if (relative != null && exclude.matchesDirectory(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                written.push(new int[]{0});
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String relative = relative(file);
                if (relative == null || exclude.matchesFile(relative)) return FileVisitResult.CONTINUE;
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                try {
                    addFile(zip, file, relative, attrs, base, buffer, counter, compression, log, opener);
                    markWritten();
                } catch (IOException e) {
                    counter.skipped++;
                    log.log(Level.WARNING, "[백업] 파일을 읽지 못해 건너뜁니다: " + relative + " (" + e.getMessage() + ")");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                boolean hasContent = written.pop()[0] > 0;
                if (!hasContent) {
                    String relative = relative(dir);
                    if (relative != null) {
                        try {
                            zip.putNextEntry(new ZipEntry(relative + "/"));
                            zip.closeEntry();
                            hasContent = true;
                        } catch (IOException e) {
                            log.warning("[백업] 빈 폴더를 기록하지 못했습니다: " + relative
                                    + " (" + e.getMessage() + ")");
                        }
                    }
                }
                // 하위에 뭔가 남겼다면 상위 폴더는 그 엔트리 덕에 복원되므로 따로 적을 필요가 없다.
                if (hasContent) markWritten();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                counter.skipped++;
                log.warning("[백업] 접근할 수 없는 파일: " + file + " (" + exc.getMessage() + ")");
                return FileVisitResult.CONTINUE;
            }

            private void markWritten() {
                int[] parent = written.peek();
                if (parent != null) parent[0]++;
            }

            private String relative(Path target) {
                return relatives != null ? relatives.of(target) : FileUtil.relativize(serverRoot, target);
            }
        });
    }

    /**
     * 파일 하나를 스냅샷에 반영한다.
     * 기준 백업과 크기·수정 시각이 같으면 매니페스트에만 남기고 바이트는 저장하지 않는다.
     *
     * <p>스냅샷에 <b>기록하는 것은 담은 뒤</b>다. 순서를 뒤집으면 읽지 못한 파일이 담긴 것으로
     * 남는데, 그것이 이 플러그인에서 가장 위험한 거짓말이다. {@link #copyInto} 설명 참고.</p>
     */
    private static void addFile(ZipOutputStream zip,
                                Path file,
                                String entryName,
                                BasicFileAttributes attrs,
                                Manifest base,
                                byte[] buffer,
                                Counter counter,
                                Compression compression,
                                Logger log,
                                FileOpener opener) throws IOException {
        long size = attrs.size();
        long modified = attrs.lastModifiedTime().toMillis();

        if (base != null && base.unchanged(entryName, size, modified)) {
            counter.snapshot(entryName, size, modified);
            counter.reused++;
            counter.report(log);
            return;
        }

        long written;
        try {
            written = copyInto(zip, file, entryName, modified, buffer, compression, size, opener);
        } catch (IOException e) {
            // 담지는 못했지만 스냅샷에는 <b>있던</b> 파일이다. 그 사실만 목록에 남긴다.
            // 목록에서 아예 빼면 차등 복원이 "기준 이후 삭제된 파일" 로 보고 지나쳐서,
            // 기준 백업에 남아 있는 예전 판까지 함께 잃는다.
            counter.manifest.putUnstored(entryName);
            throw e;
        }
        counter.snapshot(entryName, size, modified);
        counter.stored++;
        counter.storedBytes += written;
        counter.report(log);
    }

    /**
     * 파일 내용을 zip 엔트리로 흘려 넣고, 쓴 바이트 수를 돌려준다.
     *
     * <p>첫 조각을 <b>엔트리를 만들기 전에</b> 읽는다. 서버가 쓰고 있는 region 파일이나
     * {@code session.lock} 은 <b>열기는 되고 읽기에서 실패한다</b> - 실서버에서 파일을 못 담는
     * 거의 모든 경우가 이 모양이다. 엔트리를 먼저 열어 두면 그때 0바이트 엔트리가 zip 에 남고,
     * 호출자는 매니페스트에 "담았다" 고 적는다. 그 뒤에 벌어지는 일이 나쁜 쪽으로만 이어진다.</p>
     * <ul>
     *   <li>복원하면 멀쩡한 region 파일이 <b>빈 파일로 덮어써진다.</b></li>
     *   <li>다음 차등 백업은 기준 매니페스트를 믿고 이 파일을 저장하지 않는다.
     *       그래서 그 파일은 기준에도 차등본에도 없다.</li>
     *   <li>그 차등본을 복원하려 하면 검사가 "어디에도 없는 파일" 이라며 <b>복원 자체를 막는다.</b>
     *       백업이 여러 벌 있는데 그중 어느 것으로도 되돌릴 수 없게 되는 것이다.</li>
     * </ul>
     *
     * <p>첫 조각을 읽은 뒤에 끊기는 경우(실제 I/O 오류)에는 잘린 엔트리가 남는 것을 막을 수 없다.
     * 다만 예외를 그대로 올려 호출자가 매니페스트에 적지 않게 하므로, 적어도 다음 차등 백업은
     * 이 파일을 새로 담는다.</p>
     */
    private static long copyInto(ZipOutputStream zip,
                                 Path file,
                                 String entryName,
                                 long modified,
                                 byte[] buffer,
                                 Compression compression,
                                 long size,
                                 FileOpener opener) throws IOException {
        try (InputStream in = opener.open(file)) {
            int read = in.read(buffer);

            ZipEntry entry = new ZipEntry(entryName);
            entry.setLastModifiedTime(FileTime.fromMillis(modified));
            compression.prepare(entryName, size);
            zip.putNextEntry(entry);
            long written = 0L;
            try {
                while (read > 0) {
                    zip.write(buffer, 0, read);
                    written += read;
                    read = in.read(buffer);
                }
            } finally {
                zip.closeEntry();
            }
            return written;
        }
    }

    /**
     * 엔트리마다 압축 레벨을 맞춰 준다.
     *
     * <p>{@link ZipOutputStream#setLevel(int)} 은 <b>다음 엔트리부터</b> 적용되므로
     * {@code putNextEntry} 직전에 부른다. 값이 바뀔 때만 부르므로 종류가 섞인 폴더에서도
     * 부담이 없다.</p>
     */
    private static final class Compression {

        private final ZipOutputStream zip;
        private final int configured;
        private int current;

        /** 재압축을 건너뛴 원본 바이트. 관리자에게 얼마나 아꼈는지 알려 주는 데 쓴다. */
        long plainBytes;

        Compression(ZipOutputStream zip, int configured) {
            this.zip = zip;
            this.configured = configured;
            this.current = configured;
            zip.setLevel(configured);
        }

        /** 이 엔트리에 쓸 레벨로 맞춘다. */
        void prepare(String entryName, long size) {
            int wanted = configured > 0 && alreadyCompressed(entryName) ? 0 : configured;
            if (wanted != current) {
                zip.setLevel(wanted);
                current = wanted;
            }
            if (wanted != configured) plainBytes += size;
        }

        private static boolean alreadyCompressed(String entryName) {
            int dot = entryName.lastIndexOf('.');
            if (dot < 0 || dot == entryName.length() - 1) return false;
            if (entryName.lastIndexOf('/') > dot) return false; // 폴더 이름에 있던 점이다
            return ALREADY_COMPRESSED.contains(entryName.substring(dot + 1).toLowerCase(Locale.ROOT));
        }
    }

    /** 진행 상황 카운터. */
    private static final class Counter {
        final long expectedBytes;
        final Consumer<String> progress;
        final Manifest manifest = Manifest.empty();
        long originalBytes;
        long storedBytes;
        int files;
        int stored;
        int reused;
        int skipped;
        long lastReport = System.currentTimeMillis();

        Counter(long expectedBytes, Consumer<String> progress) {
            this.expectedBytes = expectedBytes;
            this.progress = progress;
        }

        /**
         * 이 파일이 스냅샷에 들어갔다고 기록한다.
         *
         * <p>담는 데 성공한 뒤에만 부른다. 매니페스트와 {@code files}/{@code originalBytes} 는
         * "이 백업으로 되돌릴 수 있는 것" 을 뜻하므로, 못 담은 파일을 여기 세면 그 수치가
         * 거짓이 된다.</p>
         */
        void snapshot(String entryName, long size, long modified) {
            manifest.put(entryName, size, modified);
            files++;
            originalBytes += size;
        }

        void report(Logger log) {
            if (progress == null) return;
            long now = System.currentTimeMillis();
            if (now - lastReport < PROGRESS_INTERVAL_MS) return;
            lastReport = now;
            String reusedText = reused > 0 ? ", 재사용 " + reused + "개" : "";
            String text;
            if (expectedBytes > 0) {
                int percent = (int) Math.min(99, (originalBytes * 100L) / expectedBytes);
                text = percent + "% (" + FileUtil.humanBytes(originalBytes) + " / "
                        + FileUtil.humanBytes(expectedBytes) + ", " + files + "개 파일" + reusedText + ")";
            } else {
                text = FileUtil.humanBytes(originalBytes) + ", " + files + "개 파일" + reusedText;
            }
            progress.accept(text);
        }
    }
}
