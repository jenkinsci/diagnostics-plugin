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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

import org.apache.log4j.lf5.util.StreamUtils;
import org.hamcrest.collection.IsEmptyCollection;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;
import org.xml.sax.SAXException;

import org.jenkinsci.plugins.diagnostics.DiagnosticsSession.State;
import org.jenkinsci.plugins.diagnostics.diagnostics.TestDiagnostic1;
import org.jenkinsci.plugins.diagnostics.diagnostics.TestDiagnostic2;
import org.jenkinsci.plugins.diagnostics.diagnostics.TestDiagnosticNeverEnds;
import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.UnexpectedPage;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlFormUtil;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class DiagnosticsActionTest {
    static final Logger LOGGER = Logger.getLogger(DiagnosticsActionTest.class.getName());

    @Rule
    public JenkinsRule j = new JenkinsRule();
    // public RestartableJenkinsRule j = new RestartableJenkinsRule();

    private TemporaryDirectoryAllocator tempDirAllocator;

    @Before
    public void setup() throws Exception {
        tempDirAllocator = new TemporaryDirectoryAllocator();
        TestHelper.clearSessions();
    }

    @After
    public void tearDown() throws Exception {
        TestHelper.clearSessions();
    }

    /**
     * Creates multiple sessions stores them on a Diagnostics configuration and tests several methods
     * 
     * @throws Exception
     */
    @Test
    public void sanityChecksTest() throws Exception {
        DiagnosticsAction action = DiagnosticsAction.getInstance();
        DiagnosticsConfig config = DiagnosticsConfig.getInstance();

        // Create a couple of sessions
        String session1Name = "session1";
        createSession(session1Name, 10000, new Class<?>[] { TestDiagnostic1.class, TestDiagnosticNeverEnds.class });
        assertThat("There should be sessions running", action.isSessionRunning(), equalTo(true));

        DiagnosticsSession session = null;
        for (DiagnosticsSession tmpSession : action.getSessions()) {
            if (tmpSession.getDescription().equals(session1Name)) {
                session = tmpSession;
                break;
            }
        }

        assertThat("Could not find running session", session, notNullValue());
        createSession("session2", 10000, new Class<?>[] { TestDiagnostic1.class, TestDiagnostic2.class });

        // cancel a running session
        WebClient webClient = j.createWebClient();
        HtmlPage result = webClient.goTo("diagnostics").getAnchorByName("cancel-" + session.getId()).click();
        String urlTxt = result.getUrl().toString();
        String sessionId = urlTxt.substring(urlTxt.indexOf("=") + 1);
        assertThat("Session being canceled should match de requested id", session.getId(), equalTo(sessionId));
        assertThat("Should have redirected to confirm cancel page", urlTxt, containsString("cancelConfirmation"));
        HtmlFormUtil.getSubmitButton(result.getFormByName("cancel")).click();
        assertThat("Session should have been canceled", session.getStatus(), equalTo(State.CANCELLED));

        // Wait for the sessions to finish
        while (action.isSessionRunning()) {
            Thread.sleep(200);
        }

        assertThat("Session from action should be the same as from Diagnostic config", action.getSession(session.getId()), equalTo(session));
        assertThat("The first session should be cancelled", session.getStatus(), equalTo(DiagnosticsSession.State.CANCELLED));
        assertThat("There should be a canceled session in the config file", config.getConfigXml().asString(), containsString("CANCELLED"));
        assertThat("Session from getSession should be the same as the one from the list", action.getSession(session.getId()), equalTo(session));

        // Test deletion
        result = webClient.goTo("diagnostics").getAnchorByName("delete-" + session.getId()).click();
        urlTxt = result.getUrl().toString();
        sessionId = urlTxt.substring(urlTxt.indexOf("=") + 1);
        assertThat("Session being deleted should match de requested id", session.getId(), equalTo(sessionId));
        assertThat("Should have redirected to confirm delete page", urlTxt, containsString("deleteConfirmation"));
        HtmlFormUtil.getSubmitButton(result.getFormByName("delete")).click();
        assertThat("There should be only one session left", config.getSessionList().size(), equalTo(1));

        session = action.getSessions().get(0);

        webClient = j.createWebClient();
        UnexpectedPage download = webClient.goTo("diagnostics").getElementByName("download-" + session.getId()).click();

        assertThat("Should have redirected to download url", download.getUrl().toString(), containsString("download"));
        assertThat("The download session should match", download.getUrl().toString(), containsString(config.getSessionList().get(0).getId()));

        File tempDir = tempDirAllocator.allocate();
        File file = new File(tempDir, "tempFile.zip");
        try (FileOutputStream downloaded = new FileOutputStream(file);
                InputStream input = download.getInputStream()) {
            StreamUtils.copy(input, downloaded);
        }

        // Check the contents of the zip file
        try (ZipFile zip = new ZipFile(file)) {
            assertThat("There should be some entries on the zip", zip.size(), not(equalTo(0)));
            assertThat("There should be a manifest", zip.getEntry("manifest.md"), notNullValue());
        }
        assertThat(action.getSession(null), nullValue());
    }

    private void createSession(String name, int initialDelay, Class<?>[] enabledDiagnostics) throws ElementNotFoundException, IOException, SAXException {
        HtmlForm form = j.createWebClient().goTo("diagnostics").getFormByName("diagnostic-contents");
        form.getInputByName("_.description").setValueAttribute(name);

        for (Class<?> diagnosticClass : enabledDiagnostics) {
            form.getInputByName(diagnosticClass.getName().replace('.', '-')).setChecked(true);
        }

        List<HtmlInput> delays = form.getInputsByName("_.initialDelay");
        assertThat("No Diagnostics where displayed on the creation form", delays, not(IsEmptyCollection.empty()));

        for (HtmlInput delay : delays) {
            delay.setValueAttribute(String.valueOf(initialDelay)); // Avoid the session start to slow us down
        }

        HtmlFormUtil.getSubmitButton(form).click();
    }
}