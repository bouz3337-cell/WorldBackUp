package io.github.yj.worldbackup.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 백업 중 파일을 읽지 못하는 상황을 만든다. <b>플랫폼과 무관하게</b> 같은 결과를 낸다.
 *
 * <p>실서버에서 가장 자주 일어나는 백업 실패가 이것이다 - 서버가 region 파일에 쓰고 있으면
 * 파일은 열리는데 읽기가 실패한다. 그때 아카이브와 매니페스트에 무엇이 남는지가
 * 이 플러그인의 정직성을 결정한다.</p>
 *
 * <p>예전에는 {@link java.nio.channels.FileLock} 으로 흉내 냈다. 그 잠금은 <b>윈도우에서만
 * 강제</b>라서, 리눅스에서는 읽기가 그냥 성공하고 검증이 조용히 통과했다. 정작 이 플러그인이
 * 대부분 돌아가는 플랫폼에서 가장 중요한 보장이 검증되지 않았던 것이다.
 * ({@link Archiver.FileOpener} 가 열려 있는 이유)</p>
 */
public final class UnreadableFile {

    /** {@link Archiver} 의 읽기 버퍼 크기. 이 경계에서 끊기게 만든다. */
    public static final int BUFFER_BYTES = 128 * 1024;

    private UnreadableFile() {
    }

    /**
     * 이 파일만 <b>열리지 않는다.</b> 나머지는 평소대로 읽는다.
     *
     * <p>서버가 쓰고 있는 파일의 실제 모양이다. 엔트리가 만들어지기 전에 실패하므로
     * zip 에는 아무것도 남지 않아야 한다.</p>
     */
    public static Archiver.FileOpener refusingToOpen(Path unreadable) {
        Path target = unreadable.toAbsolutePath().normalize();
        return file -> {
            if (file.toAbsolutePath().normalize().equals(target)) {
                throw new IOException("다른 프로세스가 이 파일을 쓰고 있습니다(테스트): " + file.getFileName());
            }
            return Files.newInputStream(file);
        };
    }

    /**
     * 이 파일은 첫 조각까지만 읽히고 <b>그 뒤부터 끊긴다.</b>
     *
     * <p>실제 I/O 오류의 모양이다. zip 엔트리는 이미 열렸으므로 되돌릴 수 없어 잘린 엔트리가
     * 남는다. 그래도 매니페스트에는 "담아냈다" 고 적히지 않아야 한다.</p>
     */
    public static Archiver.FileOpener failingAfterFirstChunk(Path flaky) {
        Path target = flaky.toAbsolutePath().normalize();
        return file -> {
            InputStream real = Files.newInputStream(file);
            if (!file.toAbsolutePath().normalize().equals(target)) return real;
            return new InputStream() {

                private int served;

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int read = read(one, 0, 1);
                    return read < 0 ? -1 : one[0] & 0xFF;
                }

                /**
                 * 정확히 {@link #BUFFER_BYTES} 만큼 내주고 그다음 호출에서 던진다.
                 *
                 * <p>스트림이 한 번에 몇 바이트를 주는지는 보장되지 않으므로, 끊기는 지점을
                 * <b>내준 양</b>으로 센다. 그래야 어느 플랫폼에서도 잘린 엔트리 크기가 같다.</p>
                 */
                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    if (served >= BUFFER_BYTES) {
                        throw new IOException("장치에 오류가 발생했습니다(테스트)");
                    }
                    int read = real.read(buffer, off, Math.min(len, BUFFER_BYTES - served));
                    if (read > 0) served += read;
                    return read;
                }

                @Override
                public void close() throws IOException {
                    real.close();
                }
            };
        };
    }
}
