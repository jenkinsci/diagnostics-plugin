package org.jenkinsci.plugins.diagnostics;

import org.jenkinsci.plugins.diagnostics.diagnostics.Diagnostic;
import org.jenkinsci.plugins.diagnostics.diagnostics.SupportBundleDiagnostic;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

/**
 * test for the Diagnostic SupportBundleDiagnostic.
 */
public class SupportBundleDiagnosticTest extends DefaultDiagnosticTest {

    @Test
    public void runDiagnosticsTest() throws Exception {
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        SupportBundleDiagnostic d1 = new SupportBundleDiagnostic(0, 100, 1, new SupportBundleDiagnostic.BasicSupportBundle());
        diagnostics.add(d1);
        SupportBundleDiagnostic d2 = new SupportBundleDiagnostic(2000, 100, 1, new SupportBundleDiagnostic.MediumSupportBundle());
        diagnostics.add(d2);
        SupportBundleDiagnostic d3 = new SupportBundleDiagnostic(5000, 100, 1, new SupportBundleDiagnostic.FullSupportBundle());
        diagnostics.add(d3);



        session.runDiagnostics(diagnostics);
        waitUntilEnd();

        Set<Diagnostic> diagnosticList = session.getDiagnosticList();
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(d1));
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(d2));
        assertThat("Requested diagnostic should be in the diagnostics list", diagnosticList, hasItem(d3));
        assertThat("The session should have succeeded", session.getStatus(), equalTo(DiagnosticsSession.State.SUCCEEDED));
    }

}
