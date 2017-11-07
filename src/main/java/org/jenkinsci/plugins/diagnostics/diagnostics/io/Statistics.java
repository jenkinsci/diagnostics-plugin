package org.jenkinsci.plugins.diagnostics.diagnostics.io;

import java.util.Formatter;

import static org.jenkinsci.plugins.diagnostics.diagnostics.io.IOTest.K;
import static org.jenkinsci.plugins.diagnostics.diagnostics.io.IOTest.M;

public class Statistics {
        private long blockLength;
        private long dataLength;
        private long time;

        /**
         * Store the result statistics of a test.
         *
         * @param blockLength size of each block writen in bytes.
         * @param dataLength  total data size in bytes.
         * @param time        time spent to read/write operations.
         */
        public Statistics(long blockLength, long dataLength, long time) {
            this.blockLength = blockLength;
            this.dataLength = dataLength;
            this.time = time;
        }

        public long getBlockLength() {
            return blockLength;
        }

        public long getDataLength() {
            return dataLength;
        }

        public long getTime() {
            return time;
        }

        /**
         * Calculate and write the stats on the output.
         */
        @Override
        public String toString() {
            String scale = "MB";
            float dataLengthS = (float) dataLength / (float) M;
            float blockLengthS = (float) blockLength / (float) M;
            if (dataLength < M) {
                scale = "KB";
                dataLengthS = (float) dataLength / (float) K;
                blockLengthS = (float) blockLength / (float) K;
            }
            float av = dataLengthS / ((float) time / 1000f);
            Formatter formatter = new Formatter();
            formatter.format(java.util.Locale.getDefault(),
                    "%.2f %s (Block size %.2f %s) Time: %d ms Average: %.2f %s/s",
                    dataLengthS, scale, blockLengthS, scale, time, av, scale);
            return formatter.toString();
        }
    }
