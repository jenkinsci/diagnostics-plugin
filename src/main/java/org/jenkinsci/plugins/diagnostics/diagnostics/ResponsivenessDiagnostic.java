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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import javax.annotation.Nonnull;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.WriterOutputStream;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;
import org.kohsuke.stapler.DataBoundConstructor;

import org.jenkinsci.plugins.diagnostics.DiagnosticsContainer;
import org.jenkinsci.plugins.diagnostics.DiagnosticsHelper;
import com.cloudbees.jenkins.support.api.FileContent;
import com.cloudbees.jenkins.support.impl.GCLogs;
import com.cloudbees.jenkins.support.impl.ThreadDumps;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

import hudson.Extension;

/**
 * Diagnostic to help debug micro-hangs on a Jenkins instance. It generates a thread dump and GC logs for every
 * execution
 * 
 */
@SuppressWarnings("restriction")
public class ResponsivenessDiagnostic extends DefaultDiagnostic {
    private static final long serialVersionUID = 1L;

    private transient HashMap<GarbageCollectorMXBean, GCNotificationListener> listeners;
    private File gcEventsLog;
    transient PrintWriter eventsLogPW;
    
    /**
     * Creates an instance
     */
    public ResponsivenessDiagnostic() {
        super();
    }

    /**
     * Creates an instance
     * 
     * @param initialDelay number milliseconds to delay the first execution
     * @param period period to wait between successive executions in milliseconds
     * @param runs number of times this diagnostic should be executed on the diagnostic session
     */
    @DataBoundConstructor
    public ResponsivenessDiagnostic(int initialDelay, int period, int runs) {
        super(initialDelay, period, runs);
    }

    @Override
    public String getFileName() {
        return "threadDump";
    }

    @Override
    public void runDiagnostic(@Nonnull PrintWriter out, int run) throws IOException {
        LOGGER.info("ResponsivenessDiagnostic run " + run);
        out.println("=== Thread dump at " + new Date() + " ===");
        try (WriterOutputStream os = new WriterOutputStream(out, "utf-8")) {
            ThreadDumps.threadDumpModern(os);
        }
        eventsLogPW.printf("%s - Thread dump '%s'%n", DiagnosticsHelper.getDateFormat().format(new Date()), currentFileName);
    }

    @Override
    public void beforeExecutionStart(DiagnosticsContainer result) throws IOException {
        super.beforeExecutionStart(result);

        // Prepare the file for the GC logs and create the PrintStream to write to it
        String gcEventLogFilename = "GCEventCLogs-" + DiagnosticsHelper.getProcessId() + ".txt";
        File rootDir = new File(result.getFolderPath());
        if (rootDir.exists() || rootDir.mkdirs()) {
            gcEventsLog = new File(rootDir, gcEventLogFilename);
        } else {
            throw new IOException("Could not create root dir for diagnostics " + rootDir);
        }

        eventsLogPW = new PrintWriter(gcEventsLog, "utf-8");
        eventsLogPW.println("=== This file contains all the GC event logs and pointers to the generated thread dumps in a time line fashion ===");
        eventsLogPW.println();

        // Register the file for later archiving
        result.add(new FileContent(gcEventLogFilename, gcEventsLog));
        installGCListeners();
    }

    @Override
    public void afterExecutionFinished(DiagnosticsContainer result) throws IOException {
        try {
            super.afterExecutionFinished(result);
            removeGCListeners();
        } finally {
            IOUtils.closeQuietly(eventsLogPW);
        }

        // Add gc logs
        new GCLogs().addContents(result);
    }

    /**
     * Installs listeners for each {@link GarbageCollectorMXBean} in order to log the events
     */
    @IgnoreJRERequirement
    public synchronized void installGCListeners() {
        listeners = new HashMap<GarbageCollectorMXBean, GCNotificationListener>();
        long jvmStartTime = ManagementFactory.getRuntimeMXBean().getStartTime();
        // Using JMX API that should be available on all Oracle and OpenJDK JVMs after 1.7u1 but just in case
        // we'll check and warn the user in case we don't have that API. The GC Logs will still be available if enabled.
        try {
            // Obtain all the garbage collector MXBeans usually
            List<GarbageCollectorMXBean> gcbeans = ManagementFactory.getGarbageCollectorMXBeans();

            // Install listeners for each one
            for (GarbageCollectorMXBean gcbean : gcbeans) {
                NotificationEmitter emitter = (NotificationEmitter) gcbean;
                GCNotificationListener listener = new GCNotificationListener(eventsLogPW, jvmStartTime);
                listeners.put(gcbean, listener);
                // Add the listener
                emitter.addNotificationListener(listener, new GCNotificationFilter(), null);
            }
        } catch (LinkageError e) {
            LOGGER.warning("JMX API for Garbage collection events not available. GC timeline won't be available on the generated reports. GC Log should be available if enabled with the JVM start parameters");
        }
    }

    /**
     * Removes the listener to the {@link GarbageCollectorMXBean}s
     */
    @IgnoreJRERequirement
    public synchronized void removeGCListeners() {
        if (listeners != null && listeners.size() > 0) {
            try {
            // Obtain all the garbage collector MXBeans usually
            List<GarbageCollectorMXBean> gcbeans = ManagementFactory.getGarbageCollectorMXBeans();

            // Removes listeners for each one
            for (GarbageCollectorMXBean gcbean : gcbeans) {
                NotificationEmitter emitter = (NotificationEmitter) gcbean;
                GCNotificationListener listener = listeners.get(gcbean);

                if (listener != null) {
                    // remove the listener
                    try {
                        emitter.removeNotificationListener(listener);
                    } catch (ListenerNotFoundException e) {
                        LogRecord logRecord = new LogRecord(Level.WARNING, "GC Notification listener for {0} couldn't be removed");
                        logRecord.setThrown(e);
                        logRecord.setParameters(new Object[] { gcbean.getObjectName() });
                        LOGGER.log(logRecord);
                    }
                }
            }
            } catch (LinkageError e) {
                // Ignore -- we already logged the error at install time
            }
        }
    }

    /**
     * Listener for the GC notifications
     */
    @IgnoreJRERequirement
    private static class GCNotificationListener implements NotificationListener {
        // keep a count of the total time spent in GCs
        long totalGcDuration = 0;
        long lastRun = 0;
        PrintWriter eventsLogPW;
        long jvmStartTime;

        public GCNotificationListener(PrintWriter eventsLogPW, long jvmStartTime) {
            this.eventsLogPW = eventsLogPW;
            this.jvmStartTime = jvmStartTime;
        }

        // implement the notifier callback handler
        @Override
        public void handleNotification(Notification notification, Object handback) {

            // make sure we only handle GC notifications.
            if (notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
                
                GarbageCollectionNotificationInfo notifInfo = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
                GcInfo gcInfo = notifInfo.getGcInfo();

                eventsLogPW.printf("%s - %s: - %d %s (from %s) %d milliseconds; start-end times %s - %s%n",
                                   DiagnosticsHelper.getDateFormat().format(new Date()), notifInfo.getGcAction(),
                                   gcInfo.getId(), notifInfo.getGcName(), notifInfo.getGcCause(), gcInfo.getDuration(),
                                   DiagnosticsHelper.getDateFormat().format(new Date(jvmStartTime + gcInfo.getStartTime())),
                                   DiagnosticsHelper.getDateFormat().format(new Date(jvmStartTime + gcInfo.getEndTime())));
                
                // Get the information about each memory space, and pretty print it
                Map<String, MemoryUsage> memBeforeMap = gcInfo.getMemoryUsageBeforeGc();
                Map<String, MemoryUsage> memAfterMap = gcInfo.getMemoryUsageAfterGc();
                for (Entry<String, MemoryUsage> memAfterEntry : memAfterMap.entrySet()) {
                    String name = memAfterEntry.getKey();
                    MemoryUsage memDetailAfter = memAfterEntry.getValue();
                    MemoryUsage memDetailBefore = memBeforeMap.get(name);
                    long memUsedAfter = memDetailAfter.getUsed();
                    double memPercentBefore = (double) memDetailBefore.getUsed() / (double) memDetailBefore.getCommitted();
                    double memPercentAfter = (double) memUsedAfter / (double) memDetailBefore.getCommitted(); // >100%  when it expands

                    eventsLogPW.printf("   %s(%s) used: %.2f%% -> %.2f (%dMB)%n",
                                       name, memDetailAfter.getCommitted() >= memDetailAfter.getMax() ? "fully expanded" : "still expandable",
                                       memPercentBefore * 100, memPercentAfter * 100, memUsedAfter / 1024 / 1024 + 1);
                }

                eventsLogPW.println();
                totalGcDuration += gcInfo.getDuration();
                eventsLogPW.printf("   GC acumulated overhead %4f%%%n", (double) totalGcDuration / ((double) gcInfo.getEndTime()) * 100);
                eventsLogPW.printf("     GC last run overhead %4f%%%n", (double) totalGcDuration / ((double) (gcInfo.getEndTime() - lastRun)) * 100);
                lastRun = gcInfo.getEndTime();
                eventsLogPW.println();
            }
            eventsLogPW.flush();
        }
    }

    /**
     * Filters the GC notifications we want to receive
     */
    @IgnoreJRERequirement
    private static final class GCNotificationFilter implements NotificationFilter {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isNotificationEnabled(Notification notification) {
            return notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION);
        }
    }

    /**
     * Our descriptor.
     */
    @Extension
    public static class DescriptorImpl extends DiagnosticDescriptor<ResponsivenessDiagnostic> {
        @Override
        public String getDisplayName() {
            return "Responsiveness Diagnose - ThreadDumps";
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
            return 1000;
        }
    }
}
