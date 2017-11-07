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
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.acegisecurity.util.FieldUtils;
import org.apache.commons.lang.math.RandomUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;

import org.jenkinsci.plugins.diagnostics.diagnostics.Diagnostic;
import org.jenkinsci.plugins.diagnostics.diagnostics.TestDiagnostic1;
import org.jenkinsci.plugins.diagnostics.diagnostics.TestDiagnostic2;

public class DiagnosticsConfigTest {
    static final Logger LOGGER = Logger.getLogger(DiagnosticsConfigTest.class.getName());

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TemporaryDirectoryAllocator tempDirAllocator;

    @Before
    public void setup() throws Exception {
        tempDirAllocator = new TemporaryDirectoryAllocator();

        // clean up all the sessions that may be left from a previous test run
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
        ArrayList<DiagnosticsSession> sessions = new ArrayList<DiagnosticsSession>();
        DiagnosticsConfig config = DiagnosticsConfig.getInstance();
        DiagnosticsSession session;
        List<Diagnostic> diagnostics;
        TestDiagnostic1 d1;
        TestDiagnostic2 d2;

        // Create a few sessions
        for (int i = 0; i < 5; i++) {
            session = new DiagnosticsSession(DiagnosticsConfig.getRootDirectory(), "Session1", config);
            sessions.add(session);
            diagnostics = new ArrayList<Diagnostic>();
            d1 = new TestDiagnostic1(0, 100, 10);
            diagnostics.add(d1);
            d2 = new TestDiagnostic2(0, 200, 10);
            diagnostics.add(d2);

            session.runDiagnostics(diagnostics);
            config.addSession(session);

            Thread.sleep(RandomUtils.nextInt(20) * 10); // Allow some time difference between starts
        }

        // try to delete a running session
        try {
            config.deleteSession(sessions.get(4).getId());
            fail("The RUNNING session shouldn't have been deleted");
        } catch (IllegalStateException e) {
            // Expected
        }
        assertThat("There should still be sessions running", config.isSessionRunning(), equalTo(true));

        // test the get session by id and that the sessions is stored on the config file
        session = sessions.remove(RandomUtils.nextInt(5));
        DiagnosticsSession session2 = config.getSession(session.getId());

        assertThat("Obtained session by id should be the same as the original session", session2, equalTo(session));
        assertThat("The session should have been saved", config.getConfigXml().asString(), containsString(session.getId()));

        // Wait for the sessions to finish
        while (config.isSessionRunning()) {
            Thread.sleep(500);
        }

        // Test delete session
        config.deleteSession(session.getId());
        config.save();

        assertThat("The session should have been deleted from the SessionConfig", config.getSession(session.getId()), nullValue());
        assertThat("The session should be deleted", new File(session.getSessionResultFile()).exists(), equalTo(false));
        assertThat("The session shouldn't be on the config file", config.getConfigXml().asString(), not(containsString(session.getId())));
        assertThat("The session list from the config should be equals to the ones created", config.getSessionList(), equalTo((List<DiagnosticsSession>) sessions));

        // Test saving a changed session
        session = sessions.get(RandomUtils.nextInt(4));
        assertThat("There shouldn't be any failed sessions", config.getConfigXml().asString(), not(containsString("FAILED")));
        FieldUtils.setProtectedFieldValue("status", session, DiagnosticsSession.State.FAILED);
        config.save();
        assertThat("The session should have been saved with the new description", config.getConfigXml().asString(), containsString("FAILED"));

        // Test the instantiation of a session config when there is an existing config file
        config = null;
        setPrivateStaticFieldValue(DiagnosticsConfig.ResourceHolder.class, "INSTANCE", invokePrivateConstructor(DiagnosticsConfig.class));
        config = DiagnosticsConfig.getInstance();
        assertThat("The session list from the config should be equals to the ones created", config.getSessionList(), containsInAnyOrder(sessions.toArray()));
    }

    private void setPrivateStaticFieldValue(Class<?> clazz, String fieldName, Object value) throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);

        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

        field.set(null, value);
    }

    private <T> T invokePrivateConstructor(Class<T> clazz)
            throws NoSuchMethodException, SecurityException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        Constructor<T> constructor;
        constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        T instance = constructor.newInstance();
        return instance;
    }
}