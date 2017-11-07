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

package org.jenkinsci.plugins.diagnostics;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.Date;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.tools.zip.ZipEntry;
import org.apache.tools.zip.ZipOutputStream;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import org.jenkinsci.plugins.diagnostics.DiagnosticRunner.DiagnosticListener;
import org.jenkinsci.plugins.diagnostics.diagnostics.Diagnostic;
import com.cloudbees.jenkins.support.api.Container;
import com.cloudbees.jenkins.support.api.Content;

import hudson.Util;
import hudson.model.User;
import jenkins.model.Jenkins;

/**
 * Represent a diagnostic session in which you run several selected diagnostics for a period of time (runs + period).
 * All the diagnostic reports are written to a session folder during the session and zipped after the session is over.
 */
@ThreadSafe
public class DiagnosticsSession implements DiagnosticListener, Serializable {
    private static final long serialVersionUID = 1L;
    static final Logger LOGGER = Logger.getLogger(DiagnosticsSession.class.getName());

    /**
     * Indicates the status of the session
     */
    public enum State {
        /**
         * The session is currently running.
         */
        RUNNING,
        /**
         * The session has finished successfully and the result file has been created
         */
        SUCCEEDED,
        /**
         * The session failed due to an error. The result file may not haven been created
         */
        FAILED,
        /**
         * The session has been canceled. Either by the user or by a Jenkins restart. The result file has been created
         * with the partial results, if any.
         */
        CANCELLED
    };

    /**
     * The unique session id
     */
    private final String id;

    /**
     * The root directory to store diagnostic reports of this session
     */
    private final String rootDiagnosesDirectory;

    /**
     * Session description
     */
    private final String description;

    /**
     * A listener that will be notified at certain events.
     */
    private final transient DiagnosticsSessionListener sessionListener;

    /**
     * Current session status.
     */
    @GuardedBy("this")
    private State status;

    /**
     * Session creation date
     */
    @GuardedBy("this")
    private final Date creationDate;

    /**
     * Session start date
     */
    @GuardedBy("this")
    private Date startDate;

    /**
     * Session end date
     */
    @GuardedBy("this")
    private Date endDate;

    /**
     * User that launched the session
     */
    @GuardedBy("this")
    private String userName;
    /**
     * An automatically generated session name
     */
    @GuardedBy("this")
    final private String name;

    /**
     * Included {@link Diagnostic}s in this session
     */
    @GuardedBy("this")
    private Hashtable<Diagnostic, DiagnosticRunner> diagnostics = new Hashtable<Diagnostic, DiagnosticRunner>();

    /**
     * The {@link DiagnosticsContainer} to generate the session report
     */
    @GuardedBy("this")
    protected DiagnosticsContainer container;

    private transient SessionChecker checker;

    /**
     * Creates a {@link DiagnosticsSession}
     * 
     * @param rootDiagnosesDirectory - root directory to store diagnostics information, were an specific directory to
     * this session will be created.
     * @param description - a user description for this session
     * @param sessionListener - the listener to be notified on session changes
     */
    DiagnosticsSession(@Nonnull String rootDiagnosesDirectory, @Nonnull String description, @CheckForNull DiagnosticsSessionListener sessionListener) {
        this.rootDiagnosesDirectory = rootDiagnosesDirectory;
        this.description = description;
        this.sessionListener = sessionListener;
        this.creationDate = new Date();

        id = UUID.randomUUID().toString();

        name = "diagnosticsSession-" + DiagnosticsHelper.getProcessId() + "-" + DiagnosticsHelper.getDateFormat().format(creationDate) + "_" + RandomStringUtils.randomAlphanumeric(4);

        User currentUser = User.current();
        userName = currentUser == null ? User.getUnknown().getId() : currentUser.getId();
    }

    /**
     * Gets the selected {@link Diagnostic}s for this session
     * 
     * @return the selected {@link Diagnostic}s for this session
     */
    @Nonnull
    @Restricted(NoExternalUse.class) //Stapler
    public Set<Diagnostic> getDiagnosticList() {
        return diagnostics.keySet();
    }

    /**
     * Gets the generated session name
     * 
     * @return the generated session name
     */
    @Nonnull
    @Restricted(NoExternalUse.class) //Stapler
    public String getName() {
        return name;
    }

    /**
     * Gets the root work directory for the session
     * 
     * @return root work directory for the session
     */
    @Nonnull
    String getSessionDirectory() {
        return rootDiagnosesDirectory + "/" + name;
    }

    /**
     * Gets the session report result file
     * 
     * @return report file path
     */
    @Nonnull
    String getSessionResultFile() {
        return rootDiagnosesDirectory + "/" + name + ".zip";
    }

    /**
     * @return current session status {@link State}
     */
    @Restricted(NoExternalUse.class) //Stapler
    public State getStatus() {
        return status;
    }

    /**
     * Runs the diagnostics selected for this session
     * 
     * @param diagnosticsToRun list of {@link Diagnostic}s to run
     * @return the {@link DiagnosticsSession.State#RUNNING} {@link DiagnosticsSession}
     * @throws IllegalStateException can be thrown if session is already running
     */
    @Nonnull
    synchronized DiagnosticsSession runDiagnostics(@Nonnull List<Diagnostic> diagnosticsToRun) throws IllegalStateException {
        if (status != null) {
            throw new IllegalStateException("Session already running or ran");
        }
        startDate = new Date();
        status = State.RUNNING;
        container = new DiagnosticsContainer(name, getSessionDirectory());
        LOGGER.log(Level.INFO, "Running diagnostics session {0} - {1}", new Object[] { name, this });

        File sessionDirectory = new File(container.getFolderPath());
        if (!sessionDirectory.exists() && !sessionDirectory.mkdirs()) {
            LOGGER.log(Level.WARNING, "Could not create work folder: ''{0}'' for session ''{1}'' will retry when writing diagnostic results", new Object[] { sessionDirectory, this });
        }

        if (diagnosticsToRun.size() > 0) {
            ScheduledExecutorService schedulerService = DiagnosticsExecutor.get();
            for (Diagnostic d : diagnosticsToRun) {
                try {
                    // Run the diagnostic
                    DiagnosticsContainer diagContainer = new DiagnosticsContainer(d.getDescriptor().getDisplayName(), container, d.getFileName());
                    container.add(diagContainer);

                    DiagnosticRunner diagnosticRunner = new DiagnosticRunner(d, diagContainer, this);
                    diagnosticRunner.schedule(schedulerService);
                    diagnostics.put(d, diagnosticRunner);
                } catch (Exception e) {
                    LogRecord logRecord = new LogRecord(Level.WARNING, "Error starting diagnostic ''{0}''");
                    logRecord.setThrown(e);
                    logRecord.setParameters(new Object[] { this });
                    LOGGER.log(logRecord);
                    // TODO - write errors to result content
                }
            }

            //Start the session checker with a 500ms interval
            SessionChecker checker = new SessionChecker(this);
            schedulerService.scheduleAtFixedRate(checker, 500, 500, TimeUnit.MILLISECONDS);
        } else {
            doFinishSession(false);
        }
        return this;
    }

    @ThreadSafe
    static private class SessionChecker implements Runnable, Serializable {
        private static final long serialVersionUID = 1L;

        @GuardedBy("this")
        private transient ScheduledFuture<?> schedule;
        @GuardedBy("this")
        private DiagnosticsSession session;

        SessionChecker(DiagnosticsSession session) {
            this.session = session;
        }

        @Override
        synchronized public void run() {
            if (!session.doCheckShouldBeFinished()) {
                if (session.isRunning()) {
                    LOGGER.log(Level.WARNING, "Session {0} didn''t finish gracefully some diagnostic may have finished abruptly.", session.getName());
                    session.doFinishSession(false);
                }
                schedule.cancel(true);
            }
        }
    }

    /**
     * Checks if the session should still be running or if all the Diagnostics have finished and we didn't receive the
     * notifications. This check will prevent rogue session from running indefinitely.
     */
    private boolean doCheckShouldBeFinished() {
        if (!isRunning()) {
            return false;
        }

        Collection<DiagnosticRunner> runners = diagnostics.values();
        for (DiagnosticRunner runner : runners) {
            if (runner.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cancels the current {@link DiagnosticsSession.State#RUNNING} session
     */
    void cancel() {
        if (status == State.RUNNING) {
            doFinishSession(true);
        }
    }

    /**
     * Finishes a {@link DiagnosticsSession.State#RUNNING} session with a final result of
     * {@link DiagnosticsSession.State#SUCCEEDED} or {@link State.CANCELLED} depending on the parameted
     * 
     * @param canceled true if the session was canceled
     */
    private void doFinishSession(boolean canceled) {
        synchronized (this) {
            if (status != State.RUNNING) {
                return;
            }
            if (canceled) {
                LOGGER.log(Level.INFO, "Canceling diagnostics session {0}", this);
                status = State.CANCELLED;
                for (DiagnosticRunner d : diagnostics.values()) {
                    d.cancel(true);
                }
            } else {
                LOGGER.log(Level.INFO, "Finishing diagnostics session {0}", this);
                status = State.SUCCEEDED;
            }

            endDate = new Date();
            try {
                writeResults(false);
                LOGGER.log(Level.INFO, "Diagnostic session {0} finished", this);
            } catch (IOException e) {
                LogRecord logRecord = new LogRecord(Level.WARNING, "Error creating archive for diagnostic session ''{0}''");
                logRecord.setThrown(e);
                logRecord.setParameters(new Object[] { this });
                LOGGER.log(logRecord);
                status = State.FAILED;
            }

            //Make sure the checker is not running any more
            if (checker != null) {
                ScheduledFuture<?> schedule = checker.schedule;
                if (schedule != null) {
                    schedule.cancel(false);
                }
                checker = null;
            }
        }

        if (sessionListener != null) {
            sessionListener.notifySessionFinished(this);
        }
    }

    /**
     * Writes the result of the session into the final zip file. By processing the {@link Container}s and
     * {@link Content}s. When using the fallback mode, the full content of the session folder will be zipped without
     * processing the {@link Container}s and {@link Content}s.
     * 
     * @param fallBackMode indicates to only zip the full content of the session folder.
     * @throws IOException if something goes wrong
     */
    private synchronized void writeResults(boolean fallBackMode) throws IOException {
        File zipFile = new File(getSessionResultFile());
        File sourceDir = new File(getSessionDirectory());

        LOGGER.info("Writing diagnostics file " + zipFile);
        Util.deleteFile(zipFile);

        try (FileOutputStream fileOut = new FileOutputStream(zipFile);
                BufferedOutputStream bufferOut = new BufferedOutputStream(fileOut, 16384) {
                    @Override
                    public void close() throws IOException {
                        // don't let any of the contents accidentally close the zip stream
                        super.flush();
                    }
                };
                ZipOutputStream zip = new ZipOutputStream(bufferOut)) {
            if (!fallBackMode) {
                container.writeSessionResults(zip);
            } else {
                LOGGER.info("Writing zip file in fallback mode due to diagnostics session failure");
                Collection<File> fileList = FileUtils.listFiles(sourceDir, null, true);
                for (File file : fileList) {
                    try (FileInputStream input = new FileInputStream(file)) {
                        String entryName = getEntryName(sourceDir, file);
                        ZipEntry entry = new ZipEntry(entryName);
                        entry.setTime(file.lastModified());
                        zip.putNextEntry(entry);

                        IOUtils.copy(input, zip);
                    } catch (Throwable e) {
                        LOGGER.log(Level.WARNING, "Error creating archive entry for " + file.getCanonicalPath(), e);
                        // TODO error handling and writing it to a file on the result
                    }
                    zip.flush();
                }
            }

        }
        FileUtils.deleteDirectory(sourceDir);
    }

    /**
     * Ob
     *
     * @param source the directory where the file entry is found
     * @param file the file that is about to be added
     * @return the name of an archive entry
     * @throws IOException if something goes wrong
     */
    private String getEntryName(File source, File file) throws IOException {
        String sourcePath = source.getAbsolutePath();
        int index = sourcePath.length() + 1;
        String path = file.getAbsolutePath();

        return path.substring(index);
    }

    /**
     * Checks if the download result is ready
     * 
     * @return true is the download zip is ready
     */
    @Restricted(NoExternalUse.class) //Stapler
    public boolean isDownloadReady() {
        return status == State.SUCCEEDED || status == State.CANCELLED;
    }

    /**
     * Checks if the session is still running
     * 
     * @return true if the diagnostic session is running
     */
    @Restricted(NoExternalUse.class) //Stapler
    public boolean isRunning() {
        return status == State.RUNNING;
    }

    /**
     * Gets the session start date
     * 
     * @return the start date
     */
    @CheckForNull
    @Restricted(NoExternalUse.class) //Stapler
    public Date getStartDate() {
        return startDate == null ? null : (Date) startDate.clone();
    }

    /**
     * Gets the session end date
     * 
     * @return the end date
     */
    @CheckForNull
    @Restricted(NoExternalUse.class) //Stapler
    public Date getEndDate() {
        return endDate == null ? null : (Date) endDate.clone();
    }

    /**
     * Gets username that ran the session
     * 
     * @return the username that ran the session
     */
    @Nonnull
    @Restricted(NoExternalUse.class) //Stapler
    public String getUserName() {
        return userName;
    }

    /**
     * Gets the full username that ran the session
     * 
     * @return the full username that ran the session
     */
    @Nonnull
    @Restricted(NoExternalUse.class) //Stapler
    public String getFullUserName() {
        User user = Jenkins.getInstance().getUser(userName);
        if (user != null) {
            return user.getFullName();
        }
        return userName;
    }

    /**
     * Gets a formated string with the session runtime
     * 
     * @return a formated string with the session runtime
     */
    @Nonnull
    @Restricted(NoExternalUse.class) //Stapler
    public String getRunTime() {
        if (startDate != null) {
            return Util.getTimeSpanString((endDate != null ? endDate.getTime() : new Date().getTime()) - startDate.getTime());
        } else {
            return "";
        }
    }

    /**
     * Gets the unique session id
     * 
     * @return the unique session id
     */
    @Nonnull
    @Restricted(NoExternalUse.class) //Stapler
    public String getId() {
        return id;
    }

    @Override
    public String toString() {
        return getName() + "-" + description;
    }

    /**
     * Returns the {@link DiagnosticRunner} associated to the given {@link Diagnostic}
     * 
     * @param diagnostic the {@link Diagnostic} to find the {@link DiagnosticRunner} for
     * @return the {@link DiagnosticRunner}
     */
    @CheckForNull
    @Restricted(NoExternalUse.class) //Stapler
    public DiagnosticRunner getDiagnosticRunner(@Nonnull Diagnostic diagnostic) {
        return diagnostics.get(diagnostic);
    }

    /**
     * Indicates if the {@link Diagnostic} is running
     * 
     * @param diagnostic the {@link Diagnostic} to check
     * @return <code>true</code> if the {@link Diagnostic} is running
     */
    boolean isDiagnosticRunning(@Nonnull Diagnostic diagnostic) {
        DiagnosticRunner runner = diagnostics.get(diagnostic);
        return runner == null ? false : runner.isRunning();
    }

    /**
     * Deletes the current session from disk.
     * 
     * @throws IOException if things go wrong deleting the files
     */
    synchronized void delete() throws IOException {
        if (status == State.RUNNING) {
            throw new IllegalStateException("Session is running, it must finish or be canceled before it can be deleted");
        }

        LOGGER.log(Level.INFO, "Deleting session {0}", this);

        File zipFile = new File(getSessionResultFile());
        File sourceDir = new File(getSessionDirectory());

        Util.deleteFile(zipFile);
        FileUtils.deleteDirectory(sourceDir);

        LOGGER.log(Level.INFO, "Session {0} deleted", this);
    }

    /**
     * Gets the session user description
     * 
     * @return the session user description
     */
    public String getDescription() {
        return description;
    }

    @Override
    @Restricted(NoExternalUse.class)
    public void notifyDiagnosticFinished(DiagnosticRunner dr) {
        LOGGER.log(Level.FINE, "Notified {0} finished", dr.getDiagnostic().getDescriptor().getDisplayName());

        if (sessionListener != null) {
            sessionListener.notifyDiagnosticFinished(this, dr);
        }

        if (status == State.RUNNING) {
            for (DiagnosticRunner runner : diagnostics.values()) {
                if (runner.isRunning()) {
                    return;
                }
            }
            doFinishSession(false);
        }
    }

    @Override
    @Restricted(NoExternalUse.class)
    public void notifyDiagnosticRunFinished(DiagnosticRunner dr) {
        LOGGER.log(Level.FINE, "Notified {0} finished", dr.getDiagnostic().getDescriptor().getDisplayName());
        sessionListener.notifyDiagnoticRunFinished(this, dr);
    }

    /**
     * Listener interface which allows listening to events on a {@link DiagnosticsSession}
     */
    interface DiagnosticsSessionListener {
        /**
         * Called when a diagnostic run is finished
         * 
         * @param session the {@link DiagnosticsSession} that sent the notification
         * @param dr the {@link DiagnosticRunner} that sent the notification
         */
        void notifyDiagnoticRunFinished(@Nonnull DiagnosticsSession session, @Nonnull DiagnosticRunner dr);

        /**
         * Called when a full diagnostic execution is finished
         * 
         * @param session the {@link DiagnosticsSession} that sent the notification
         * @param dr the {@link DiagnosticRunner} that sent the notification
         */
        void notifyDiagnosticFinished(@Nonnull DiagnosticsSession session, @Nonnull DiagnosticRunner dr);

        /**
         * Called when a session execution is finished
         * 
         * @param session the {@link DiagnosticsSession} that sent the notification
         */
        void notifySessionFinished(@Nonnull DiagnosticsSession session);
    }

    protected Object readResolve() {
        // In case we don't have status but there is a result file, just fail the session
        if (status == null) {
            if (new File(getSessionResultFile()).exists()) {
                status = State.FAILED;
            }
        }
        // Fail the session if Jenkins crashed when the session was running.
        // and zip all the temp folder. Some content will be lost.
        // Do the same in case we don't have a status
        if (status == null || status == State.RUNNING) {
            LOGGER.log(Level.WARNING, "A running session was aborted due to a restart: {0}", this);
            status = State.FAILED;
            try {
                LOGGER.log(Level.WARNING, "Packing partial contents for the aborted session {0}", this);
                writeResults(true);
            } catch (Exception e) {
                LogRecord record = new LogRecord(Level.SEVERE, "Error packing partial contents for the aborted session {0}");
                record.setParameters(new Object[] { this });
                record.setThrown(e);
                LOGGER.log(record);
            }
        }
        return this;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        DiagnosticsSession other = (DiagnosticsSession) obj;
        if (id == null) {
            if (other.id != null)
                return false;
        } else if (!id.equals(other.id))
            return false;
        return true;
    }

}
