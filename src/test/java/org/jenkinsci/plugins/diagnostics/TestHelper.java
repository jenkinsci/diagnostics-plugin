package org.jenkinsci.plugins.diagnostics;

import java.io.IOException;

import org.codehaus.plexus.util.FileUtils;

public class TestHelper {
    public static void clearSessions() throws IOException {
        DiagnosticsConfig config = DiagnosticsConfig.getInstance();
        for (DiagnosticsSession sess : config.getSessionList()) {
            if (sess.isRunning()) {
                sess.cancel();
            }
            config.deleteSession(sess.getId());
        }
        FileUtils.deleteDirectory(DiagnosticsConfig.getRootDirectory());
    }
}