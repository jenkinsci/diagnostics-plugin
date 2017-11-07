/*
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.diagnostics.diagnostics;

import org.jenkinsci.plugins.diagnostics.DiagnosticsHelper;
import org.jenkinsci.plugins.diagnostics.diagnostics.io.IOTest;
import org.jenkinsci.plugins.diagnostics.diagnostics.io.Statistics;
import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import static org.jenkinsci.plugins.diagnostics.diagnostics.io.IOTest.*;
import static java.util.logging.Level.FINE;

/**
 * <p>Diagnostic to help debug IO issues on a Jenkins instance. It tests the IO for every
 * execution.</p>
 * <p>It makes three kind of tests:</p>
 * <ul>
 * <li>writes blocks of data several times and measures the time take to do it.</li>
 * <li>writes and read blocks of data several times and measures the time take to do it.</li>
 * <li>writes small blocks of data several times and measures the time take to do it.</li>
 * </ul>
 */
public class IODiagnostic extends DefaultDiagnostic {
    private static final long serialVersionUID = 1L;

    private transient Map<String, IOTest[]> tests;

    /**
     * Creates an instance
     */
    public IODiagnostic() {
        super();
    }

    protected void initTests() throws IOException {
        tests = new HashMap<>();
        Path rootDir = getCurrentRun().getRootDir();
        tests.put("Different file sizes Test", new IOTest[]{
                new IOTest(rootDir, 1, SIZE_1M),
                new IOTest(rootDir, 1, SIZE_10M),
                new IOTest(rootDir, 1, SIZE_100M),
                new IOTest(rootDir, 100, SIZE_1M),
                new IOTest(rootDir, 10, SIZE_10M),
                new IOTest(rootDir, 10, SIZE_50M)
        });

        tests.put("Small files Test", new IOTest[]{
                new IOTest(rootDir, 100, SIZE_1K)
        });
    }

    /**
     * Creates an instance
     *
     * @param initialDelay number milliseconds to delay the first execution.
     * @param period       period to wait between successive executions in milliseconds.
     * @param runs         number of times this diagnostic should be executed on the diagnostic session.
     */
    @DataBoundConstructor
    public IODiagnostic(int initialDelay, int period, int runs) {
        super(initialDelay, period, runs, "io-summary", DefaultDiagnostic.LOG);
    }

    @Override
    public void runDiagnostic(@Nonnull PrintWriter pw, final int run) throws IOException {
        initTests();
        getDiagnosticLog().printf("%s - IO Diagnostic '%s'%n", DiagnosticsHelper.getDateFormat().format(new Date()), currentFileName);
        LOGGER.log(FINE, "IO Diagnostic run {0} file: {1}", new Object[]{run, currentFileName});
        pw.printf("=== IO Diagnostic at %s  ===%n", new Date());

        for (Entry<String, IOTest[]> entry : tests.entrySet()) {
            separator(pw);
            pw.println(entry.getKey());
            for (IOTest ioTest : entry.getValue()) {
                Statistics sr = ioTest.runWrite();
                Statistics srw = ioTest.runWriteRead();
                pw.println(" Read - " + sr.toString());
                pw.println(" Write/Read - " + srw.toString());
            }
        }
    }


    private void separator(PrintWriter output) {
        output.println("===========");
    }

    @Extension
    public static class DescriptorImpl extends DiagnosticDescriptor<IODiagnostic> {

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return "IO Diagnostic";
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getInitialDelay() {
            return 500;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getRuns() {
            return 10;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getPeriod() {
            return  60000;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isSelectedByDefault() {
            return false;
        }
    }
}
