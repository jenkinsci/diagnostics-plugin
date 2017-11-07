package org.jenkinsci.plugins.diagnostics;

import org.jenkinsci.plugins.diagnostics.DiagnosticsSessionTest.TestSessionListener;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

/**
 * default test class to extend to test a diagnostic.
 */
public abstract class DefaultDiagnosticTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public TemporaryFolder folder= new TemporaryFolder();

    protected File tempDir;
    protected TestSessionListener listener;
    protected DiagnosticsSession session;

    /**
     * Start the session and temporal directory.
     * @throws Exception
     */
    @Before
    public void setup() throws Exception {
        tempDir = folder.newFolder();
        TestHelper.clearSessions();

        listener = new TestSessionListener();
        session = new DiagnosticsSession(tempDir.getAbsolutePath(), "Session1", listener);
        assertThat("Session should have and 'id' when created", session.getId(), Matchers.not(Matchers.isEmptyOrNullString()));
    }

    @After
    public void tearDown() throws Exception {
        TestHelper.clearSessions();
    }

    /**
     * wait until the diagnostic ends.
     * @throws InterruptedException
     */
    protected void waitUntilEnd() throws InterruptedException {
        // Wait for the session to finish
        while (session.isRunning()) {
            Thread.sleep(500);
        }
        //wait for the update of sessionFinishedNotifications
        Thread.sleep(500);
        assertThat("Session finish should have been notified", listener.sessionFinishedNotifications, equalTo(1));
    }
}
