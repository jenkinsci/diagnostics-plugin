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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;

/**
 * Holds the {@link ScheduledExecutorService} for running diagnose tasks.
 * 
 */
@ThreadSafe
class DiagnosticsExecutor {
    private static final Logger LOGGER = Logger.getLogger(DiagnosticsExecutor.class.getName());

    /**
     * The max size of the thread pool property name
     */
    private static final String THREAD_POOL_SIZE_PROPERTY = DiagnosticsExecutor.class.getName() + ".maxDiagnosticsThreads";

    /**
     * Default max thread pool size
     */
    public static final int DEFAULT_THREAD_POOL_SIZE_DEFAULT = 10;

    /**
     * Default max thread keep alive
     */
    public static final int THREAD_POOL_KEEP_ALIVE_SECONDS = 5;

    /**
     * Current max thread pool size
     */
    private static int poolSize = readIntegerProperty(THREAD_POOL_SIZE_PROPERTY, DEFAULT_THREAD_POOL_SIZE_DEFAULT);

    /**
     * The scheduled executor thread pool. This is initialized lazily since it may be created/shutdown many times when
     * running the test suite.
     */
    @GuardedBy("this")
    private static ScheduledThreadPoolExecutor executorService;

    /**
     * Returns the scheduled executor service used by all timed tasks in Jenkins.
     *
     * @return the single {@link ScheduledExecutorService}.
     */
    @Restricted(NoExternalUse.class)
    @Nonnull
    static synchronized ScheduledExecutorService get() {
        if (executorService == null) {
            // corePoolSize is set to a fixed values but will only be created if needed.
            // ScheduledThreadPoolExecutor "acts as a fixed-sized pool"
            executorService = new ScheduledThreadPoolExecutor(0,
                                                              new NamingThreadFactory(new DaemonThreadFactory(), DiagnosticsExecutor.class.getName()));
            // make the executor lightweight when not in use
            // executorService.setMaximumPoolSize(poolSize);
            // Setting the core pool size after the constructor we start the thread pool to start empty and grow to that
            // value
            // If we leave it to 0 it will not grow past 1 thread
            executorService.setCorePoolSize(poolSize);
            executorService.setRemoveOnCancelPolicy(true);
            executorService.setKeepAliveTime(THREAD_POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
            executorService.allowCoreThreadTimeOut(true);

        }
        LOGGER.log(Level.FINEST, "Diagnostic Thread Pool status: {0}", executorService);
        return executorService;
    }

    /**
     * Sets the max pool size
     * 
     * @param poolSize the new max pool size
     */
    static void setPoolSize(int poolSize) {
        DiagnosticsExecutor.poolSize = poolSize;
        if (executorService != null) {
            synchronized (DiagnosticsExecutor.class) {
                executorService.setCorePoolSize(poolSize);
                // executorService.setMaximumPoolSize(poolSize);
            }
        }
    }

    /**
     * Gets the max pool size
     * 
     * @return the max pool size
     */
    static int getPoolSize() {
        return poolSize;
    }

    /**
     * Shutdown the timer and throw it away.
     */
    @Restricted(NoExternalUse.class)
    static synchronized void shutdown() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
            LOGGER.log(Level.INFO, "Diagnostic thread pool has been shutdown, will recreate on next call");
        }
    }

    private static int readIntegerProperty(String propertyName, int defaultValue) {
        String value = System.getProperty(propertyName);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                LOGGER.warning("Ignoring invalid " + propertyName + "=" + value);
            }
        }
        return defaultValue;
    }

    /**
     * Do not create this.
     */
    private DiagnosticsExecutor() {
    }

}
