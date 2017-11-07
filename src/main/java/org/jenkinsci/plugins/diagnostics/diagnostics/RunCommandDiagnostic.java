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
import hudson.Extension;
import org.apache.commons.io.IOUtils;
import org.apache.tools.ant.types.Commandline;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Date;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;

/**
 * Diagnostic to help debug micro-hangs on a Jenkins instance. It runs a command and save the output for every
 * execution.
 */
public class RunCommandDiagnostic extends DefaultDiagnostic {
    private static final long serialVersionUID = 1L;
    private String command;

    /**
     * Creates an instance
     */
    public RunCommandDiagnostic() {
        super();
    }

    /**
     * Creates an instance
     *
     * @param initialDelay number of milliseconds to delay the first execution.
     * @param period       period to wait between successive executions in milliseconds.
     * @param runs         number of times this diagnostic should be executed on the diagnostic session.
     * @param command      command line to execute.
     */
    @DataBoundConstructor
    public RunCommandDiagnostic(int initialDelay, int period, int runs, @Nonnull String command) {
        super(initialDelay, period, runs, "command-summary", DefaultDiagnostic.LOG);
        this.command = command;
    }

    @Override
    public void runDiagnostic(@Nonnull PrintWriter pw, final int run) throws IOException {
        getDiagnosticLog().printf("%s - Run command '%s' - '%s'%n", DiagnosticsHelper.getDateFormat().format(new Date()), currentFileName, command);
        String cmd = command.replace("{PID}", DiagnosticsHelper.getProcessId()).replace("{WS}", getCurrentRun().getRootDir().toString());
        LOGGER.log(FINE, "RunCommandDiagnostic run {0} file: {1}", new Object[]{run, currentFileName});
        pw.println("=== Run command at " + new java.util.Date() + " ===");
        pw.println("CMD : " + cmd);
        runCommand(cmd, pw);
    }

    /**
     * run a command and write the stdout and stderr to a file.
     *
     * @param cmd    command line to execute.
     * @param output PrintWriter to write the output of the command.
     * @throws IOException in case of error.
     */
    private void runCommand(String cmd, PrintWriter output) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(Commandline.translateCommandline(cmd)).directory(getCurrentRun().getRootDir().toFile());
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        try (InputStream input = process.getInputStream()) {
            IOUtils.copy(input, output);
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Was not possible to read the command output", e);
            throw e;
        }
    }

    @Extension
    public static class DescriptorImpl extends DiagnosticDescriptor<RunCommandDiagnostic> {

        @Override
        public String getDisplayName() {
            return "Run Command";
        }

        @Override
        public int getInitialDelay() {
            return 500;
        }

        @Override
        public int getRuns() {
            return 10;
        }

        @Override
        public int getPeriod() {
            return 10000;
        }

        @Override
        public boolean isSelectedByDefault() {
            return false;
        }

        @SuppressWarnings("unused") // called from jelly
        public String getCommand() {
            return "/bin/sh -c 'echo {PID} {WS} otherArgs'";
        }
    }
}
