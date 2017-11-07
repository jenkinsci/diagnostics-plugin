package org.jenkinsci.plugins.diagnostics;

import org.jenkinsci.plugins.diagnostics.DiagnosticsSession.State;
import org.jenkinsci.plugins.diagnostics.diagnostics.Diagnostic;
import org.jenkinsci.plugins.diagnostics.diagnostics.IODiagnostic;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertThat;

/**
 * test for the Diagnostic IODiagnostic.
 */
public class IODiagnosticTest extends DefaultDiagnosticTest {

    @Test
    public void runDiagnosticsTest() throws Exception {
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();

        IODiagnostic d1 = new IODiagnostic(0, 100, 1);
        diagnostics.add(d1);
        IODiagnostic d2 = new IODiagnostic(2000, 100, 1);
        diagnostics.add(d2);
        IODiagnostic d3 = new IODiagnostic(5000, 100, 1);
        diagnostics.add(d3);

        session.runDiagnostics(diagnostics);
        waitUntilEnd();

        java.util.Set<Diagnostic> diagnosticList = session.getDiagnosticList();
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(d1));
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(d2));
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(d3));
        assertThat("The session should have succeeded", session.getStatus(), equalTo(State.SUCCEEDED));
    }

}
