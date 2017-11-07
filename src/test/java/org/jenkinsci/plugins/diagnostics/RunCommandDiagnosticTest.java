package org.jenkinsci.plugins.diagnostics;

import org.jenkinsci.plugins.diagnostics.diagnostics.Diagnostic;
import org.jenkinsci.plugins.diagnostics.diagnostics.RunCommandDiagnostic;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertThat;

/**
 * test fot the Diagnostic RunCommandDiagnostic.
 */
public class RunCommandDiagnosticTest extends DefaultDiagnosticTest {

    @Test
    public void runDiagnosticsTest() throws Exception {
        List<Diagnostic> diagnostics = new ArrayList<>();

        String cmd = "/bin/sh -c echo 'hello world'";
        if(File.pathSeparatorChar==';'){
            cmd = "cmd /c echo 'hello world'";
        }

        RunCommandDiagnostic d1 = new RunCommandDiagnostic(0, 100, 1, cmd);
        diagnostics.add(d1);
        RunCommandDiagnostic d2 = new RunCommandDiagnostic(2000, 100, 1, cmd);
        diagnostics.add(d2);
        RunCommandDiagnostic d3 = new RunCommandDiagnostic(5000, 100, 1, cmd);
        diagnostics.add(d3);

        session.runDiagnostics(diagnostics);
        waitUntilEnd();

        java.util.Set<Diagnostic> diagnosticList = session.getDiagnosticList();
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(d1));
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(d2));
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(d3));
        assertThat("The session should have succeeded", session.getStatus(), equalTo(org.jenkinsci.plugins.diagnostics.DiagnosticsSession.State.SUCCEEDED));
    }

}
