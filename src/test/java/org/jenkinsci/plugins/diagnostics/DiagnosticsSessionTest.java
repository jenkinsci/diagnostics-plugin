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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.isEmptyString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.acegisecurity.util.FieldUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;

import org.jenkinsci.plugins.diagnostics.DiagnosticsSession.DiagnosticsSessionListener;
import org.jenkinsci.plugins.diagnostics.diagnostics.Diagnostic;
import org.jenkinsci.plugins.diagnostics.diagnostics.TestDiagnostic;
import org.jenkinsci.plugins.diagnostics.diagnostics.TestDiagnostic1;
import org.jenkinsci.plugins.diagnostics.diagnostics.TestDiagnostic2;
import org.jenkinsci.plugins.diagnostics.diagnostics.TestDiagnosticFailing1;
import org.jenkinsci.plugins.diagnostics.diagnostics.TestDiagnosticFailing2;
import com.cloudbees.jenkins.support.impl.ThreadDumps;

public class DiagnosticsSessionTest {
    static final Logger LOGGER = Logger.getLogger(DiagnosticsSessionTest.class.getName());
    private static final int D1_RUNS = 10;
    private static final int D2_RUNS = 15;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TemporaryDirectoryAllocator tempDirAllocator;
    private File tempDir;

    @Before
    public void setup() throws Exception {
        tempDirAllocator = new TemporaryDirectoryAllocator();
        tempDir = tempDirAllocator.allocate();
        TestHelper.clearSessions();
    }

    @After
    public void tearDown() throws Exception {
        TestHelper.clearSessions();
    }

    @Test
    public void sanityChecksTest() throws Exception {

        TestSessionListener listener = new TestSessionListener();
        DiagnosticsSession session = new DiagnosticsSession(tempDir.toString(), "Session1", listener);

        assertThat("Session should have and 'id' when created", session.getId(), org.hamcrest.Matchers.not(org.hamcrest.Matchers.isEmptyOrNullString()));

        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        TestDiagnostic1 d1 = new TestDiagnostic1(0, 100, D1_RUNS);
        diagnostics.add(d1);
        TestDiagnostic2 d2 = new TestDiagnostic2(0, 200, D2_RUNS);
        diagnostics.add(d2);

        // Dates should be null before run check dates
        assertThat("Session shouldn't an 'start date' before starting", session.getStartDate(), nullValue());
        assertThat("Session shouldn't an 'end date' before starting", session.getEndDate(), nullValue());
        assertThat("Session shouldn't have a 'run time' before starting", session.getRunTime(), isEmptyString());

        File sessionDirectory = new File(session.getSessionDirectory());
        File resultFile = new File(session.getSessionResultFile());

        session.runDiagnostics(diagnostics);

        // Give some time for the threads to start
        Thread.sleep(100);

        assertThat("Session status should be RUNNING", session.getStatus(), equalTo(DiagnosticsSession.State.RUNNING));

        // Delete session should fail if running
        try {
            session.delete();
            Assert.fail("The delete operation should fail when session is running");
        } catch (IllegalStateException e) {
            // Expected
        }

        // Directory should exist, not deleted
        assertThat("The session directory should exist", sessionDirectory.isDirectory(), equalTo(true));

        // We should have some run time during run
        assertThat("The session should have a 'run time'", session.getRunTime(), org.hamcrest.Matchers.not(org.hamcrest.Matchers.isEmptyString()));

        // Wait for the session to finish
        while (session.isRunning()) {
            Thread.sleep(500);
        }

        assertThat("Diagnostic runs notification should match the requested exceutions", d1.runsFinished, equalTo(D1_RUNS));
        assertThat("Diagnostic finished runs notification should match the requested executions", d1.runsFinishedNotifications, equalTo(D1_RUNS));
        assertThat("Diagnostic finish should have been notified exactly once", d1.diagnosticFinishedNotifications, equalTo(1));

        assertThat("Diagnostic runs notification should match the requested exceutions", d2.runsFinished, equalTo(D2_RUNS));
        assertThat("Diagnostic finished runs notification should match the requested executions", d2.runsFinishedNotifications, equalTo(D2_RUNS));
        assertThat("Diagnostic finish should have been notified exactly once", d2.diagnosticFinishedNotifications, equalTo(1));

        assertThat("Session finish should have been notified", listener.sessionFinishedNotifications, equalTo(1));

        Set<Diagnostic> diagnosticList = session.getDiagnosticList();
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(d1));
        assertThat("Requested diagnosti should be in the diagnostics list", diagnosticList, hasItem(d2));

        assertThat("The session should have succeeded", session.getStatus(), equalTo(DiagnosticsSession.State.SUCCEEDED));

        assertThat("There should be an start date", session.getStartDate(), notNullValue());
        assertThat("There should be an end date", notNullValue());

        assertThat("There should be some run time text after canceling", session.getRunTime(), org.hamcrest.Matchers.not(org.hamcrest.Matchers.isEmptyString()));

        assertThat("Session user name should be SYSTEM", session.getUserName(), equalTo("SYSTEM")); // Default user the
                                                                                                    // jenkins rule is
                                                                                                    // running
        assertThat("Session full user name should be SYSTEM", session.getFullUserName(), equalTo("SYSTEM"));

        assertThat("Download should be ready", session.isDownloadReady(), equalTo(true));
        assertThat("The session folder shouldn't exist any more", sessionDirectory.isDirectory(), equalTo(false));
        assertThat("The result file should be available", resultFile.exists(), equalTo(true));

        assertThat(session.getDescription(), equalTo("Session1"));

        // fails on second run
        try {
            session.runDiagnostics(diagnostics);
            Assert.fail("The second run of a session should fail and throw an IllegalStateException");
        } catch (IllegalStateException e) {
            // Expected
        }

        // Create fake directory to see if it is deleted too
        sessionDirectory.mkdir();

        // delete session
        session.delete();
        assertThat("The session folder shouldn't exists any more", sessionDirectory.isDirectory(), equalTo(false));
        assertThat("The result file shouldn't exists any more", resultFile.exists(), equalTo(false));

        threadPoolShouldBeIdle();

    }

    @Test
    public void cancelSessionTest() throws Exception {

        TestSessionListener listener = new TestSessionListener();
        DiagnosticsSession session = new DiagnosticsSession(tempDir.toString(), "Session1", listener);

        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        TestDiagnostic1 d1 = new TestDiagnostic1(0, 100, 50);
        diagnostics.add(d1);
        TestDiagnostic2 d2 = new TestDiagnostic2(0, 100, 50);
        diagnostics.add(d2);

        File sessionDirectory = new File(session.getSessionDirectory());
        File resultFile = new File(session.getSessionResultFile());

        session.runDiagnostics(diagnostics);

        // Wait for some diagnostics to actually run
        Thread.sleep(500);

        // Cancel the session
        session.cancel();

        assertThat("There should be an start date", session.getStartDate(), notNullValue());
        assertThat("There should be an end date", notNullValue());

        assertThat("There should be some run time text after canceling", session.getRunTime(), org.hamcrest.Matchers.not(org.hamcrest.Matchers.isEmptyString()));
        assertThat("Download should be ready", session.isDownloadReady(), equalTo(true));
        assertThat("The session folder shouldn't exist any more", sessionDirectory.isDirectory(), equalTo(false));
        assertThat("The result file should be available", resultFile.exists(), equalTo(true));

        // Create fake directory to see if it is deleted too
        sessionDirectory.mkdir();

        // delete session
        session.delete();
        assertThat("The session folder shouldn't exists any more", sessionDirectory.isDirectory(), equalTo(false));
        assertThat("The result file shouldn't exists any more", resultFile.exists(), equalTo(false));

        threadPoolShouldBeIdle();

    }

    @Test
    public void sessionWithoutAnyDiagnosticTest() throws Exception {

        TestSessionListener listener = new TestSessionListener();
        DiagnosticsSession session = new DiagnosticsSession(tempDir.toString(), "Session1", listener);

        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();

        File resultFile = new File(session.getSessionResultFile());

        session.runDiagnostics(diagnostics);

        // Wait for the session to finish
        while (session.isRunning()) {
            Thread.sleep(500);
        }

        assertThat("There should be an start date", session.getStartDate(), notNullValue());
        assertThat("There should be an end date", notNullValue());

        assertThat("There should be some run time text after canceling", session.getRunTime(), org.hamcrest.Matchers.not(org.hamcrest.Matchers.isEmptyString()));
        assertThat("Download should be ready", session.isDownloadReady(), equalTo(true));
        assertThat("The result file should be available", resultFile.exists(), equalTo(true));

        threadPoolShouldBeIdle();
    }

    /**
     * Tests that the session will be finished by the SessionChecker even with a misbehaving runner which doesn't notify
     * the events.
     * 
     * @throws Exception if something goes wrong
     */
    @Test
    public void sessionWithARunnerThatDoesNotNotifyTest() throws Exception {

        TestSessionListener listener = new TestSessionListener();
        DiagnosticsSession session = new DiagnosticsSession(tempDir.toString(), "Session1", listener) {
            private static final long serialVersionUID = 1L;

            //Override the notification handler methods to simulate we are not being notified
            @Override
            public void notifyDiagnosticFinished(DiagnosticRunner dr) {
            }

            @Override
            public void notifyDiagnosticRunFinished(DiagnosticRunner dr) {
            }
        };

        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        TestDiagnostic1 d1 = new TestDiagnostic1(0, 100, 5);
        diagnostics.add(d1);
        TestDiagnostic2 d2 = new TestDiagnostic2(0, 100, 5);
        diagnostics.add(d2);

        File resultFile = new File(session.getSessionResultFile());

        session.runDiagnostics(diagnostics);

        // Wait for the session to finish
        while (session.isRunning()) {
            Thread.sleep(500);
        }

        assertThat("There should be an start date", session.getStartDate(), notNullValue());
        assertThat("There should be an end date", notNullValue());

        assertThat("There should be some run time text after canceling", session.getRunTime(), org.hamcrest.Matchers.not(org.hamcrest.Matchers.isEmptyString()));
        assertThat("Download should be ready", session.isDownloadReady(), equalTo(true));
        assertThat("The result file should be available", resultFile.exists(), equalTo(true));

        threadPoolShouldBeIdle();
    }

    /**
     * Test the delete method for a failed session which doesn't have a single file but the temp folder
     * 
     * @throws Exception
     */
    @Test
    public void failSessionDeleteTest() throws Exception {
        TestSessionListener listener = new TestSessionListener();
        DiagnosticsSession session = new DiagnosticsSession(tempDir.toString(), "Session1", listener);

        // Create a session directory with a couple of dummy sub-directories
        File sessionDirectory = new File(session.getSessionDirectory());
        File sessionDirectory2;
        assertThat("Session directory couldn't be created", sessionDirectory.mkdirs(), equalTo(true));

        sessionDirectory2 = new File(session.getSessionDirectory(), "directory1");
        assertThat("Diagnostic 1 directory couldn't be created", sessionDirectory2.mkdirs(), equalTo(true));
        new File(sessionDirectory2, "file1").createNewFile();
        new File(sessionDirectory2, "file2").createNewFile();

        sessionDirectory2 = new File(session.getSessionDirectory(), "directory2");
        assertThat("Diagnostic 2 directory couldn't be created", sessionDirectory2.mkdirs(), equalTo(true));
        new File(sessionDirectory2, "file1").createNewFile();
        new File(sessionDirectory2, "file2").createNewFile();

        FieldUtils.setProtectedFieldValue("status", session, DiagnosticsSession.State.FAILED);

        session.delete();
        assertThat("Session directory shouldn't exist after session deletion", sessionDirectory.isDirectory(), equalTo(false));
        threadPoolShouldBeIdle();
    }

    /**
     * Test the fallback archiving performed when a session has failed due to a jenkins restart
     * 
     * @throws Exception
     */
    @Test
    public void failSessionFallBackArchiveTest() throws Exception {
        TestSessionListener listener = new TestSessionListener();
        DiagnosticsSession session = new DiagnosticsSession(tempDir.toString(), "Session1", listener);

        // Create a session directory with a couple of dummy sub-directories
        File sessionDirectory = new File(session.getSessionDirectory());
        File sessionDirectory2;
        assertThat("Session directory couldn't be created", sessionDirectory.mkdirs(), equalTo(true));
        sessionDirectory2 = new File(session.getSessionDirectory(), "directory1");
        assertThat("Diagnostic 1 directory couldn't be created", sessionDirectory2.mkdirs(), equalTo(true));
        new File(sessionDirectory2, "file1").createNewFile();
        new File(sessionDirectory2, "file2").createNewFile();

        sessionDirectory2 = new File(session.getSessionDirectory(), "directory2");
        assertThat("Diagnostic 2 directory couldn't be created", sessionDirectory2.mkdirs(), equalTo(true));
        new File(sessionDirectory2, "file1").createNewFile();
        new File(sessionDirectory2, "file2").createNewFile();

        // Simulate a restart with a running session
        FieldUtils.setProtectedFieldValue("status", session, DiagnosticsSession.State.RUNNING);

        assertThat("The session status should have been changed to RUNNING", session.getStatus(), equalTo(DiagnosticsSession.State.RUNNING));

        // At this point we have a RUNNING session with a temp folder
        // Simulate the call to the read resolve method of the session
        session.readResolve();

        // At this point the folder should have been packed to a Zip file and deleted.
        assertThat("The session directory should no longer exist", sessionDirectory.isDirectory(), equalTo(false));

        File result = new File(session.getSessionResultFile());
        assertThat("Result file should exist", result.exists(), equalTo(true));

        // Check the contents of the zip file
        try (ZipFile zip = new ZipFile(result)) {

            // Print entries for diagnosability
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                System.out.println(entry.getName());
            }

            assertThat("The file should be in the result file", zip.getEntry("directory1/file1"), notNullValue());
            assertThat("The file should be in the result file", zip.getEntry("directory1/file2"), notNullValue());
            assertThat("The file should be in the result file", zip.getEntry("directory2/file1"), notNullValue());
            assertThat("The file should be in the result file", zip.getEntry("directory2/file2"), notNullValue());
        }
        threadPoolShouldBeIdle();
    }

    /**
     * Test a session with different failing diagnostics to check if the session can handle different types of
     * exceptions: Runtime and checked.
     * 
     * @throws Exception
     */
    @Test
    public void failingDiagnosticsSessionTest() throws Exception {

        TestSessionListener listener = new TestSessionListener();
        DiagnosticsSession session = new DiagnosticsSession(tempDir.toString(), "Session1", listener);

        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        TestDiagnostic1 d1 = new TestDiagnostic1(0, 100, D1_RUNS);
        diagnostics.add(d1);
        TestDiagnosticFailing1 df1 = new TestDiagnosticFailing1(0, 200, D1_RUNS);
        diagnostics.add(df1);
        TestDiagnosticFailing2 df2 = new TestDiagnosticFailing2(0, 200, D2_RUNS);
        diagnostics.add(df2);

        File sessionDirectory = new File(session.getSessionDirectory());
        File resultFile = new File(session.getSessionResultFile());

        session.runDiagnostics(diagnostics);

        // Give some time for the threads to start
        Thread.sleep(100);

        // Directory should exist
        assertThat("Session directory should already exist", sessionDirectory.isDirectory(), equalTo(true));

        while (session.isRunning()) {
            Thread.sleep(500);
        }

        assertThat("Diagnostic runs notification should match the requested exceutions", d1.runsFinished, equalTo(D1_RUNS));
        assertThat("Diagnostic finished runs notification should match the requested executions", d1.runsFinishedNotifications, equalTo(D1_RUNS));
        assertThat("Diagnostic finish should have been notified exactly once", d1.diagnosticFinishedNotifications, equalTo(1));

        assertThat("Diagnostic runs notification should match the requested exceutions", df1.runsFinished, equalTo(D1_RUNS));
        assertThat("Diagnostic finished runs notification should match the requested executions", df1.runsFinishedNotifications, equalTo(D1_RUNS));
        assertThat("Diagnostic finish should have been notified exactly once", df1.diagnosticFinishedNotifications, equalTo(1));

        assertThat("Diagnostic runs notification should match the requested exceutions", df2.runsFinished, equalTo(D2_RUNS));
        assertThat("Diagnostic finished runs notification should match the requested executions", df2.runsFinishedNotifications, equalTo(D2_RUNS));
        assertThat("Diagnostic finish should have been notified exactly once", df2.diagnosticFinishedNotifications, equalTo(1));

        assertThat(listener.sessionFinishedNotifications, equalTo(1));

        Set<Diagnostic> diagnosticList = session.getDiagnosticList();
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(d1));
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(df1));
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(df2));

        assertThat("The session should have SUCCEEDED", session.getStatus(), equalTo(DiagnosticsSession.State.SUCCEEDED));
        assertThat("The session folder should have been deleted after session is finished", sessionDirectory.isDirectory(), equalTo(false));

        assertThat("The download should be ready", session.isDownloadReady(), equalTo(true));
        assertThat("The result file should exist", resultFile.exists(), equalTo(true));
        threadPoolShouldBeIdle();
    }

    private void threadPoolShouldBeIdle() throws InterruptedException {
        // Some checks to the thread pool
        ScheduledThreadPoolExecutor threadPool = (ScheduledThreadPoolExecutor) DiagnosticsExecutor.get();

        assertThat("There max pool size should be set to the defined value", threadPool.getCorePoolSize(), equalTo(DiagnosticsExecutor.getPoolSize()));
        assertThat("There thread idle timeout should be set to the defined value", (int) threadPool.getKeepAliveTime(TimeUnit.SECONDS), equalTo(DiagnosticsExecutor.THREAD_POOL_KEEP_ALIVE_SECONDS));
        assertThat("There shouldn't be any active thread by now", threadPool.getActiveCount(), equalTo(0));

        // Check that the thread pool is correctly configured and will kill all threads after the timeout
        long keepAliveTime = threadPool.getKeepAliveTime(TimeUnit.SECONDS) + 10;
        for (int i = 0; i < keepAliveTime && threadPool.getPoolSize() > 0; i++) {
            Thread.sleep(1000);
        }
        if (threadPool.getPoolSize() > 0) {
            //print debug information about the pool
            System.out.println(threadPool);
            if (threadPool.getActiveCount() > 0 || threadPool.getPoolSize() > 0) {
                try {
                    ThreadDumps.threadDumpModern(System.out);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
        }
        assertThat("There shouldn't be any waiting threads by now", threadPool.getPoolSize(), equalTo(0));
    }

    public static class TestSessionListener implements DiagnosticsSessionListener {
        int sessionFinishedNotifications = 0;

        @Override
        public void notifyDiagnoticRunFinished(DiagnosticsSession session, DiagnosticRunner dr) {
            LOGGER.log(Level.INFO, "Notified run {0} diagnostic {1} finished", new Object[] { dr.getRunsCompleted(), dr.getDiagnostic().getDescriptor().getDisplayName() });
            ((TestDiagnostic) dr.getDiagnostic()).runsFinishedNotifications++;
        }

        @Override
        public void notifyDiagnosticFinished(DiagnosticsSession session, DiagnosticRunner dr) {
            LOGGER.log(Level.INFO, "Notified diagnostic {0} finished", dr.getDiagnostic().getDescriptor().getDisplayName());
            ((TestDiagnostic) dr.getDiagnostic()).diagnosticFinishedNotifications++;

            assertThat("The diagnostic runner from the session should match the one from the notification", dr, equalTo(session.getDiagnosticRunner(dr.getDiagnostic())));
            assertThat("Diagnostic should be finished when notifying it is", session.isDiagnosticRunning(dr.getDiagnostic()), equalTo(false));
        }

        @Override
        public void notifySessionFinished(DiagnosticsSession session) {
            LOGGER.log(Level.INFO, "Notified diagnostic session {0} finished", session);
            sessionFinishedNotifications++;
        }

    }

}