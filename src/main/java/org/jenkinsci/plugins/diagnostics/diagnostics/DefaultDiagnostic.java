/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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

import org.jenkinsci.plugins.diagnostics.DiagnosticsContainer;
import org.jenkinsci.plugins.diagnostics.DiagnosticsHelper;
import com.cloudbees.jenkins.support.api.FileContent;
import org.apache.commons.io.IOUtils;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

//TODO - implement an OffProcess diagnostic - that would fork a new process to run something
//TODO - see how to run diagnoses on slaves and remote nodes.

/**
 * Default implementation of a {@link Diagnostic} which stores one file per run.
 */
public abstract class DefaultDiagnostic extends Diagnostic {
    private static final long serialVersionUID = 1L;
    protected static final Logger LOGGER = Logger.getLogger(DefaultDiagnostic.class.getName());
    protected int actualRuns;
    protected transient String currentFileName;
    private static final int BUFFER_SIZE = 16384;
    protected static final String LOG = ".log";
    protected static final String ZIP = ".zip";
    protected static final String TXT = ".txt";
    private String baseName = getClass().getSimpleName();
    private transient PrintWriter diagnosticLogPW;
    /**
     * file to save the summary of the execution.
     */
    private File diagnosticLog;
    /**
     * extension of the output file.
     */
    private String outputExtension;

    /**
     * Class to store the current Run data.
     */
    public static class CurrentRun {
        private final Path rootDir;
        private final Path outputFile;
        private final DiagnosticsContainer result;
        private int run = -1;

        /**
         * Information about the current run.
         *
         * @param result     the {@link DiagnosticsContainer} to store the result to.
         * @param run        execution number of this call.
         * @param rootDir    folder where the diagnostic files are created.
         * @param outputFile filename to write diagnostic results to.
         */
        public CurrentRun(@Nonnull DiagnosticsContainer result, int run, @Nonnull Path rootDir, @Nonnull Path outputFile) {
            this.run = run;
            this.result = result;
            this.rootDir = rootDir;
            this.outputFile = outputFile;
        }

        /**
         * @return folder where the diagnostic files are created.
         */
        public Path getRootDir() {
            return rootDir;
        }

        /**
         * @return filename to write diagnostic results to.
         */
        public Path getOutputFile() {
            return outputFile;
        }

        /**
         * @return the {@link DiagnosticsContainer} to store the result to.
         */
        public DiagnosticsContainer getResult() {
            return result;
        }

        /**
         * @return execution number of this call.
         */
        public int getRun() {
            return run;
        }
    }

    /**
     * field to store the status info of the current build.
     */
    @CheckForNull
    private transient CurrentRun currentRun;


    /**
     * Creates a {@link DefaultDiagnostic}
     */
    public DefaultDiagnostic() {
        super();
    }

    /**
     * Creates an instance
     *
     * @param initialDelay number milliseconds to delay the first execution
     * @param period       period to wait between successive executions in milliseconds
     * @param runs         number of times this diagnostic should be executed on the diagnostic session
     */
    public DefaultDiagnostic(int initialDelay, int period, int runs) {
        super(initialDelay, period, runs);
        this.baseName = getClass().getSimpleName();
        this.outputExtension = TXT;
    }

    /**
     * Creates an instance
     *
     * @param initialDelay    number milliseconds to delay the first execution.
     * @param period          period to wait between successive executions in milliseconds.
     * @param runs            number of times this diagnostic should be executed on the diagnostic session.
     * @param baseName        base name for the output files.
     * @param outputExtension extension of the main output file.
     */
    public DefaultDiagnostic(int initialDelay, int period, int runs, @Nonnull String baseName, @Nonnull String outputExtension) {
        super(initialDelay, period, runs);
        this.baseName = baseName;
        this.outputExtension = outputExtension;
    }

    /**
     * Executes the diagnostic implemented by this {@link DefaultDiagnostic}. Convenient method that receives the
     * {@link PrintWriter} to write to. If more flexibility is needed to generate content, override
     * {@link #runDiagnostic(DiagnosticsContainer result, int run) } instead.
     *
     * @param out {@link OutputStream} where the diagnostic should write the results
     * @param run execution number of this call
     * @throws IOException thrown when there is a problem executing the diagnostic
     */
    public void runDiagnostic(@Nonnull PrintWriter out, int run) throws IOException {
        //default empty implementation to allow the implementer to only override #runDiagnostic(DiagnosticsContainer result, int run)
    }

    /**
     * Executes the diagnostic implemented by this {@link DefaultDiagnostic}
     *
     * @param result {@link DiagnosticsContainer} where the diagnostic should write the results
     * @param run    execution number of this call
     * @throws IOException thrown when there is a problem executing the diagnostic
     */
    public void runDiagnostic(@Nonnull DiagnosticsContainer result, final int run) throws IOException {
        actualRuns++;
        currentFileName = buildResultFileName(run);

        beforeRunStart(result, run);

        try (PrintWriter pw = getCurrentPrintWriter()) {
            runDiagnostic(pw, run);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error generating diagnostic report", e);
        }

        afterRunFinished(result);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void beforeExecutionStart(DiagnosticsContainer result) throws IOException {
        super.beforeExecutionStart(result);

        // Prepare the file for the GC logs and create the PrintStream to write to it
        String gcEventLogFilename = getFileName() + "-Logs-" + DiagnosticsHelper.getProcessId() + LOG;

        Path rootDir = createRootDir(result);
        Path logfile = rootDir.resolve(gcEventLogFilename);
        diagnosticLog = Files.createFile(logfile).toFile();

        diagnosticLogPW = new PrintWriter(diagnosticLog, StandardCharsets.UTF_8.name());
        getDiagnosticLog().println("=== This file contains all the " + getDescriptor().getDisplayName() + " in a time line fashion ===");

        // Register the file for later archiving
        result.add(new FileContent(gcEventLogFilename, diagnosticLog));
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void afterExecutionFinished(@Nonnull DiagnosticsContainer result) throws IOException {
        super.afterExecutionFinished(result);

        StringBuilder buffer = new StringBuilder();
        buffer.append("[runs:").append(actualRuns).append("/").append(getRuns()).append(", initialDelay:").append(getInitialDelay()).append(", period").append(getPeriod()).append("]");
        result.setManifestDetails(buffer.toString());
        IOUtils.closeQuietly(diagnosticLogPW);
    }

    /**
     * Creates the full filename to write diagnostic results to
     *
     * @param run - number of execution within this diagnostic session
     * @return the full file name
     */
    protected String buildResultFileName(int run) {
        return getFileName() + "-" + DiagnosticsHelper.getProcessId() + "-" + run + "-" + DiagnosticsHelper.getDateFormat().format(new Date()) + outputExtension;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public String getFileName() {
        return baseName;
    }


    /**
     * This method should be executed before each run to prepare the working variables.
     *
     * @param result {@link DiagnosticsContainer} where the diagnostic should write the results.
     * @param run    execution number of this call.
     * @throws IOException in case of error.
     */
    protected void beforeRunStart(@Nonnull DiagnosticsContainer result, final int run) throws IOException {
        currentRun = new CurrentRun(result, run, createRootDir(result), createCurrentFile(result, run));
        Path fileName = getCurrentRun().getOutputFile().getFileName();
        if(fileName == null){
            throw new IOException("The filename is not initialized");
        }
        currentFileName = fileName.toString();

        result.add(new FileContent(currentFileName, getCurrentRun().getOutputFile().toFile()));
    }

    /**
     * This method should be execute after each run to clean the working variables.
     *
     * @param result {@link DiagnosticsContainer} where the diagnostic should write the results.
     */
    protected void afterRunFinished(@Nonnull DiagnosticsContainer result) {
        currentRun = null;
    }


    /**
     * Init the root dir for the result ensure that exists and is accessible.
     *
     * @param result container for the diagnostic results.
     * @return the root directory object.
     * @throws IOException if it is not possible to access to the folder.
     */
    protected Path createRootDir(@Nonnull DiagnosticsContainer result) throws IOException {
        Path rootDir = Paths.get(result.getFolderPath());
        if (Files.exists(rootDir) && !Files.isDirectory(rootDir)) {
            throw new java.io.IOException("Exists a file with the name " + rootDir.toString());
        } else if (Files.notExists(rootDir)){
            Files.createDirectory(rootDir);
        }
        return rootDir;
    }

    /**
     * Init the current file for output.
     *
     * @param result container for the diagnostic results.
     * @param run    execution number of this call.
     * @return Return the file for the output.
     * @throws IOException in case of error.
     */
    protected Path createCurrentFile(@Nonnull DiagnosticsContainer result, final int run) throws IOException {
        String fileName = buildResultFileName(run);
        Path file = createRootDir(result).resolve(fileName);
        if(file == null){
            throw new IOException("The current output file is not initialized");
        }
        return file;
    }

    /**
     * Create a Printwriter for the current file. Note that a PrintWriter is for text output is not valid for binary output.
     *
     * @return a PrintWriter to write the output that you want.
     * @throws IOException in case of error.
     */
    protected PrintWriter getCurrentPrintWriter() throws IOException {
        OutputStreamWriter osw = new OutputStreamWriter(getCurrentOutputStream(), StandardCharsets.UTF_8);
        return new PrintWriter(osw);
    }

    /**
     * Create a BufferedOutputStream for the current file.
     *
     * @return a BufferedOutputStream to write the output that you want.
     * @throws IOException in case of error.
     */
    protected BufferedOutputStream getCurrentOutputStream() throws IOException {
        OutputStream fo = Files.newOutputStream(getCurrentRun().getOutputFile(), CREATE, APPEND);
        return new BufferedOutputStream(fo, BUFFER_SIZE);
    }

    /**
     * @return Return the PrintWriter to write in the overall Diagnostic log.
     * @throws IOException if the Diagnostic is not initialized.
     */
    public PrintWriter getDiagnosticLog() throws IOException {
        if (diagnosticLogPW == null) {
            throw new IOException("The diagnostic log it is not initialized");
        }
        return diagnosticLogPW;
    }

    /**
     * @return Return the details of the current run {@link CurrentRun}.
     * @throws IOException if the Current Run is not initialized.
     */
    protected CurrentRun getCurrentRun() throws IOException {
        if (currentRun == null) {
            throw new IOException("The run was not initialized");
        }
        return currentRun;
    }
}
