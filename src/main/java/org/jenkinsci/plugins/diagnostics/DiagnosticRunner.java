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

import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import org.jenkinsci.plugins.diagnostics.diagnostics.Diagnostic;

/**
 * Responsible for running and controlling the lifecycle of a {@link Diagnostic} diagnostic. This is a one-shot runner,
 * which means that the schedule method can only be called once.
 * 
 */
@ThreadSafe
public class DiagnosticRunner implements Runnable, Serializable {
    private static final long serialVersionUID = 1L;
    static final Logger LOGGER = Logger.getLogger(DiagnosticsSession.class.getName());

    /**
     * Number of executions already completed
     */
    private final AtomicInteger runsCompleted;

    /**
     * The {@link Diagnostic} being managed by this object
     */
    private final Diagnostic diagnostic;

    /**
     * A container to store the result information
     */
    private final transient DiagnosticsContainer container;

    /**
     * A listener to be notified when events happen
     */
    private final transient DiagnosticListener listener;

    /**
     * The {@link ScheduledFuture} responsible for running the {@link Diagnostic}
     */
    @GuardedBy("this")
    private transient ScheduledFuture<?> schedule;

    /**
     * Creates {@link DiagnosticRunner}
     * 
     * @param diagnostic to execute
     * @param container that will hold the result information
     * @param listener to be notified when events happen
     */
    DiagnosticRunner(@Nonnull Diagnostic diagnostic, @Nonnull DiagnosticsContainer container, @CheckForNull final DiagnosticListener listener) {
        this.diagnostic = diagnostic;
        this.listener = listener;
        this.container = container;
        this.runsCompleted = new AtomicInteger();
    }

    /**
     * Constructor only for deserialization
     * 
     * @param diagnostic to execute
     * @param runsCompleted number of executions already completed
     */
    private DiagnosticRunner(Diagnostic diagnostic, Integer runsCompleted) {
        this.diagnostic = diagnostic;
        this.runsCompleted = new AtomicInteger(runsCompleted);
        this.container = null;
        this.listener = null;
        this.schedule = null;
    }

    @Override
    public void run() {
        /*
         * The scheduler service will only call this method once, in which case we wouldn't need to be thread safe here.
         * Just in case there is another call not coming from the schedule service, we use an AtomicInteger which ensures that this method can be run concurrently.
         */
        int runsComp = runsCompleted.incrementAndGet();
        LOGGER.log(Level.INFO, "Diagnostic {0} run: {1}", new Object[] { diagnostic.getId(), (runsComp) });
        try {
            diagnostic.runDiagnostic(container, runsComp);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error generating diagnostic report", e);
            // TODO - write errors to result content
        }

        listener.notifyDiagnosticRunFinished(this);
        if (runsComp >= diagnostic.getRuns()) {
            LOGGER.info("Diagnostic " + diagnostic.getId() + " finished.");
            cancel(false);
        }
    }

    /**
     * Schedules the execution of the {@link Diagnostic}. This method can only be called once. When another run has
     * already been scheduled, the result will be noop.
     * 
     * @param scheduledExecutorService to use for executing the diagnostics
     * @return this {@link DiagnosticRunner}
     */
    @Nonnull
    synchronized DiagnosticRunner schedule(@Nonnull ScheduledExecutorService scheduledExecutorService) {
        if (schedule == null) {
            try {
                diagnostic.beforeExecutionStart(container);
                schedule = scheduledExecutorService.scheduleAtFixedRate(this, diagnostic.getInitialDelay(), diagnostic.getPeriod(), TimeUnit.MILLISECONDS);
            } catch (IOException e) {
                LogRecord logRecord = new LogRecord(Level.WARNING, "Error while initializing diagnostic {0}");
                logRecord.setThrown(e);
                logRecord.setParameters(new Object[] { diagnostic });
                LOGGER.log(logRecord);
            }
        } else {
            LOGGER.log(Level.WARNING, "This Diagnostic has already been run or is running. Schedule is not permitted.");
        }
        return this;
    }

    /**
     * @return {code}true{code} if the diagnostic is running
     */
    @Restricted(NoExternalUse.class) // Stapler
    public synchronized boolean isRunning() {
        return schedule != null && !schedule.isDone();
    }

    /**
     * Cancels the scheduled executions for this {@link Diagnostic} and notifies the listeners. If the schedule was
     * already canceled this method does NOOP.
     * 
     * @param interrupt <tt>true</tt> if the thread executing this task should be interrupted; otherwise, in-progress
     * tasks are allowed to complete
     */
    void cancel(boolean interrupt) {
        synchronized (this) {
            // avoid multiple notifications when cancel has been forced
            if (schedule != null && !schedule.isCancelled()) {
                schedule.cancel(interrupt);
            } else {
                return; // leave without notifying if it was already cancelled
            }
        }
        try {
            diagnostic.afterExecutionFinished(container);
        } catch (Exception e) {
            LogRecord logRecord = new LogRecord(Level.WARNING, "Error while finalizing diagnostic {0}");
            logRecord.setThrown(e);
            logRecord.setParameters(new Object[] { diagnostic });
            LOGGER.log(logRecord);
        }

        listener.notifyDiagnosticFinished(this);
    }

    /**
     * @return the container where the diagnostic information should be written
     */
    @Nonnull
    DiagnosticsContainer getContainer() {
        return container;
    }

    /**
     * Gets the {@link Diagnostic} being run
     * 
     * @return the {@link Diagnostic} being run
     */
    @Nonnull
    Diagnostic getDiagnostic() {
        return diagnostic;
    }

    /**
     * Gets the number of runs completed. It included the current run.
     * 
     * @return the number of runs completed
     */
    public int getRunsCompleted() {
        return runsCompleted.get();
    }

    /**
     * Listener interface which allows listening to events on a diagnostics run.
     */
    interface DiagnosticListener {
        /**
         * Called when a full diagnostic execution is finished
         * 
         * @param diadnosticRunner the {@link DiagnosticRunner} that sent the notification
         */
        void notifyDiagnosticFinished(@Nonnull DiagnosticRunner diadnosticRunner);

        /**
         * Called when a diagnostic run is finished
         * 
         * @param diagnosticRunner the {@link DiagnosticRunner} that sent the notification
         */
        void notifyDiagnosticRunFinished(@Nonnull DiagnosticRunner diagnosticRunner);
    }

    protected Object readResolve() {
        return new DiagnosticRunner(this.diagnostic, this.runsCompleted.intValue());
    }

}
