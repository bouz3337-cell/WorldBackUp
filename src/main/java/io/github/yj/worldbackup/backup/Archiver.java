package io.github.yj.worldbackup.backup;

import io.github.yj.worldbackup.util.FileUtil;
import io.github.yj.worldbackup.util.GlobMatcher;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
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
     * @param manifest      이번 스냅샷의 전체 파일 목록
     */
    public record Result(long archiveBytes,
                         long originalBytes,
                         int fileCount,
                         int skippedCount,
                         int storedCount,
                         long storedBytes,
                         Manifest manifest) {
    }

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

        Files.createDirectories(archive.getParent());

        Path temp = archive.resolveSibling(archive.getFileName().toString() + TEMP_SUFFIX);
        Files.deleteIfExists(temp);

        Counter counter = new Counter(expectedBytes, progress);
        byte[] buffer = new byte[BUFFER_SIZE];

        try {
            try (OutputStream fileOut = Files.newOutputStream(temp);
                 BufferedOutputStream buffered = new BufferedOutputStream(fileOut, BUFFER_SIZE);
                 ZipOutputStream zip = new ZipOutputStream(buffered, StandardCharsets.UTF_8)) {

                zip.setLevel(compressionLevel);

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
                                    base, buffer, counter, log);
                        } catch (IOException e) {
                            counter.skipped++;
                            log.log(Level.WARNING, "[백업] 파일을 읽지 못해 건너뜁니다: " + relative, e);
                        }
                    } else {
                        walkDirectory(zip, serverRoot, target, exclude, base, buffer, counter, log);
                    }
                }

                // 매니페스트와 메타데이터는 파일 수/용량이 확정된 뒤 마지막에 기록한다.
                // 매니페스트는 통째로 문자열을 만들지 않고 zip 스트림에 바로 흘려보낸다.
                zip.putNextEntry(new ZipEntry(Manifest.ENTRY));
                counter.manifest.writeTo(zip);
                zip.closeEntry();
                writeTextEntry(zip, BackupEntry.META_ENTRY,
                        metaProvider.apply(counter.files, counter.originalBytes));
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
                counter.stored, counter.storedBytes, counter.manifest);
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
                                      Logger log) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String relative = FileUtil.relativize(serverRoot, dir);
                if (relative != null && exclude.matchesDirectory(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // 비어 있는 폴더는 파일이 하나도 없어 zip 에 흔적이 남지 않는다. 엔트리를 직접 넣어 준다.
                if (relative != null && isEmptyDirectory(dir)) {
                    try {
                        zip.putNextEntry(new ZipEntry(relative + "/"));
                        zip.closeEntry();
                    } catch (IOException ignored) {
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String relative = FileUtil.relativize(serverRoot, file);
                if (relative == null || exclude.matchesFile(relative)) return FileVisitResult.CONTINUE;
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                try {
                    addFile(zip, file, relative, attrs, base, buffer, counter, log);
                } catch (IOException e) {
                    counter.skipped++;
                    log.log(Level.WARNING, "[백업] 파일을 읽지 못해 건너뜁니다: " + relative + " (" + e.getMessage() + ")");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                counter.skipped++;
                log.warning("[백업] 접근할 수 없는 파일: " + file + " (" + exc.getMessage() + ")");
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isEmptyDirectory(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 파일 하나를 스냅샷에 반영한다.
     * 기준 백업과 크기·수정 시각이 같으면 매니페스트에만 남기고 바이트는 저장하지 않는다.
     */
    private static void addFile(ZipOutputStream zip,
                                Path file,
                                String entryName,
                                BasicFileAttributes attrs,
                                Manifest base,
                                byte[] buffer,
                                Counter counter,
                                Logger log) throws IOException {
        long size = attrs.size();
        long modified = attrs.lastModifiedTime().toMillis();

        counter.manifest.put(entryName, size, modified);
        counter.files++;
        counter.originalBytes += size;

        if (base != null && base.unchanged(entryName, size, modified)) {
            counter.reused++;
            counter.report(log);
            return;
        }

        ZipEntry entry = new ZipEntry(entryName);
        entry.setLastModifiedTime(FileTime.fromMillis(modified));

        zip.putNextEntry(entry);
        long written = 0L;
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                zip.write(buffer, 0, read);
                written += read;
            }
        } finally {
            zip.closeEntry();
        }
        counter.stored++;
        counter.storedBytes += written;
        counter.report(log);
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
