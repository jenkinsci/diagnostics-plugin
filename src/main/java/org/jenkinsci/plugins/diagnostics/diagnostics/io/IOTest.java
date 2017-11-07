package org.jenkinsci.plugins.diagnostics.diagnostics.io;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.time.StopWatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;

public class IOTest {
    public static final int K = 1024;
    public static final int M = K * 1024;
    public static final int SIZE_1K = 1 * K;
    public static final int SIZE_1M = 1 * M;
    public static final int SIZE_10M = 10 * M;
    public static final int SIZE_50M = 50 * M;
    public static final int SIZE_100M = 100 * M;
    private long times;
    private byte[] data;
    private Path tempFile;

    /**
     * Define a new IO test.
     *
     * @param times      times to write and read the data block.
     * @param dataLength number of bytes to Read/Write on the test.
     */
    public IOTest(Path rootDir, long times, int dataLength) {
        this.times = times;
        SecureRandom random = new SecureRandom();
        this.data = new byte[dataLength];
        random.nextBytes(data);
        this.tempFile = rootDir.resolve(RandomStringUtils.randomAlphanumeric(20).toUpperCase());
    }

    public long getTimes() {
        return times;
    }

    public byte[] getData() {
        return Arrays.copyOf(data,data.length);
    }

    /**
     * Writes an array of bytes to a predefined named file in the workspace of the diagnostic,
     * it does it the value of "times" times, when it finished writes some stats to the log file.
     *
     * @return a Statistics object with the results of the test.
     * @throws IOException on case of error.
     */
    public Statistics runWrite() throws IOException {
        StopWatch time = new StopWatch();
        time.start();
        for (int i = 0; i < times; i++) {
            Files.write(tempFile, data);
        }
        time.stop();
        Files.deleteIfExists(tempFile);

        return new Statistics(data.length, (long) data.length * times, time.getTime());
    }

    /**
     * Writes and Reads an array of bytes to a predefined named file in the workspace of the diagnostic,
     * it does it the value of "times" times, when it finished writes some stats to the log file.
     *
     * @return a Statistics object with the results of the test.
     * @throws IOException on case of error.
     */
    public Statistics runWriteRead() throws IOException {
        StopWatch time = new StopWatch();
        time.start();
        for (int i = 0; i < times; i++) {
            Files.write(tempFile, data);
            data = Files.readAllBytes(tempFile);
        }
        time.stop();
        Files.deleteIfExists(tempFile);

        return new Statistics(data.length, (long) data.length * times * 2, time.getTime());
    }
}
