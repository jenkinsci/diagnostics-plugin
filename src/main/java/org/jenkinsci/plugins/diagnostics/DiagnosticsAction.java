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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import javax.servlet.ServletException;

import org.apache.commons.beanutils.BeanUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import org.jenkinsci.plugins.diagnostics.DiagnosticsSession.State;
import org.jenkinsci.plugins.diagnostics.diagnostics.Diagnostic;
import org.jenkinsci.plugins.diagnostics.diagnostics.DiagnosticDescriptor;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.RootAction;
import hudson.model.Descriptor.FormException;
import hudson.util.DescribableList;
import hudson.util.FormApply;
import hudson.util.HttpResponses;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

/**
 * Main root action for generating diagnoses
 */
@Extension
@ThreadSafe
public class DiagnosticsAction implements RootAction {
    static final Logger LOGGER = Logger.getLogger(DiagnosticsAction.class.getName());
    public static final String SESSION_ID = "sessionId";
    private DiagnosticsConfig config;

    public DiagnosticsAction() {
        config = DiagnosticsConfig.getInstance();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    config.save();// make sure data is committed
                    // try to gracefully cancel the sessions
                    for (DiagnosticsSession sess : config.getSessionList()) {
                        sess.cancel();
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "DiagnosticAction shutdown hook failed with exception", e);
                }
            }
        });
    }

    public static DiagnosticsAction getInstance() {
        return ExtensionList.lookup(DiagnosticsAction.class).get(0);
    }

    /**
     * Main action method responsible for creating new sessions
     * 
     * @param req Stappler request
     * @return result of the operation
     * @throws IOException if something went wrong
     * @throws ServletException if something went wrong
     * @throws FormException if something went wrong
     */
    @RequirePOST
    @Restricted(NoExternalUse.class) // Stapler
    @Nonnull
    public synchronized HttpResponse doConfigure(@Nonnull StaplerRequest req) throws IOException, ServletException, FormException {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

        JSONObject json = req.getSubmittedForm();
        DescribableList<Diagnostic, DiagnosticDescriptor<?>> diagnostics = new DescribableList<Diagnostic, DiagnosticDescriptor<?>>(config);
        diagnostics.rebuild(req, json, getDiagnosticDescriptors());

        String description = "";
        if (json.has("description")) {
            description = json.getString("description");
        }

        DiagnosticsSession session = new DiagnosticsSession(DiagnosticsConfig.getRootDirectory(), description, config);
        config.addSession(session); // make sure we add the session to the config before starting it
        session.runDiagnostics(diagnostics);

        return FormApply.success(req.getContextPath() + "/" + getUrlName());
    }

    /**
     * Cancels an executing session
     * 
     * @param sessionId the session id
     * @return result of the operation
     * @throws ServletException if something when wrong
     * @throws IOException if something when wrong
     */
    @RequirePOST
    @Restricted(NoExternalUse.class) // Stapler
    @Nonnull
    public HttpResponse doCancel(@QueryParameter(required = true) String sessionId) throws ServletException, IOException {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
        DiagnosticsSession session = config.getSession(sessionId);
        if (session == null || session.getStatus() != State.RUNNING) {
            return new FormException("Diagnostic session not found or already finished.", "inDiagnostics");
        }
        session.cancel();
        return FormApply.success(Jenkins.getInstance().getRootUrl() + "/" + getUrlName());
    }

    /**
     * Deletes a session. The session must not be running
     * 
     * @param sessionId the session id
     * @return the result of the operation
     * @throws ServletException if something when wrong
     * @throws IOException if something when wrong
     */
    @RequirePOST
    @Restricted(NoExternalUse.class) // Stapler
    @Nonnull
    public HttpResponse doDelete(@QueryParameter(required = true) String sessionId) throws ServletException, IOException {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
        config.deleteSession(sessionId);
        config.save();
        return FormApply.success(Jenkins.getInstance().getRootUrl() + "/" + getUrlName());
    }

    @Restricted(NoExternalUse.class) // Stapler
    @Nonnull
    public HttpResponse doDownload(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

        DiagnosticsSession session = config.getSession(req.getParameter(SESSION_ID));
        if (session == null || session.getStatus() == State.RUNNING) {
            return new FormException("Diagnostic session not found or not finished yet.", SESSION_ID);
        }

        File file = new File(session.getSessionResultFile());
        rsp.addHeader("Content-Disposition", "inline; filename=" + file.getName() + ";");
        return HttpResponses.staticResource(file);
    }

    /**
     * @return the action blurb
     */
    @Restricted(NoExternalUse.class) // Stapler
    public String getActionBlurb() {
        return Messages.DiagnosticsAction_ActionBlurb();
    }

    @Override
    public String getDisplayName() {
        return Messages.DiagnosticsAction_ActionTitle();
    }

    @Override
    public String getIconFileName() {
        return "/plugin/diagnostic/images/24x24/diagnostics.png";
    }

    @Override
    public String getUrlName() {
        return "diagnostics";
    }

    /**
     * @return true if the is any {@link DiagnosticsSession} running
     */
    @Restricted(NoExternalUse.class) // Stapler
    public boolean isSessionRunning() {
        return config.isSessionRunning();
    }

    /**
     * Gets the {@link DiagnosticsSession} list
     * 
     * @return the current {@link DiagnosticsSession} list
     */
    @Restricted(NoExternalUse.class) // Stapler
    @Nonnull
    public List<DiagnosticsSession> getSessions() {
        return config.getSessionList();

    }

    /**
     * Gets a {@link DiagnosticsSession} by it id
     * 
     * @param sessionId the session id
     * @return the {@link DiagnosticsSession} or <code>null</code> if not found
     */
    @Restricted(NoExternalUse.class) // Stapler
    @CheckForNull
    public DiagnosticsSession getSession(@CheckForNull String sessionId) {
        if (sessionId == null) {
            return null;
        }

        return config.getSession(sessionId);
    }

    /**
     * Returns the {@link DiagnosticDescriptor} available instances.
     *
     * @return the {@link DiagnosticDescriptor} instances
     */
    @Restricted(NoExternalUse.class) // Stapler
    @Nonnull
    public static List<DiagnosticDescriptor<?>> getDiagnosticDescriptors() {
        List<DiagnosticDescriptor<?>> result = new ArrayList<DiagnosticDescriptor<?>>(Diagnostic.all());
        return result;
    }

    /**
     * Creates a new instance for each {@link Diagnostic} that has to be selected by default on the form and sets the
     * default values from the {@link DiagnosticDescriptor}
     * 
     * @return the {@link Diagnostic} instances Map
     * @throws InstantiationException if something goes wrong
     * @throws IllegalAccessException if something goes wrong
     * @throws InvocationTargetException if something goes wrong
     */
    @Restricted(NoExternalUse.class) // Stapler
    @CheckForNull
    public Map<DiagnosticDescriptor<?>, Diagnostic> getDiagnosticInstance() throws InstantiationException, IllegalAccessException, InvocationTargetException {
        Map<DiagnosticDescriptor<?>, Diagnostic> diagnostics = new HashMap<DiagnosticDescriptor<?>, Diagnostic>();
        for (DiagnosticDescriptor<?> d : Diagnostic.all()) {
            if (d.isSelectedByDefault()) {
                Diagnostic diag = Diagnostic.all().get(0).clazz.newInstance();
                BeanUtils.copyProperties(diag, d);
                diagnostics.put(d, diag);
            }
        }
        return diagnostics;
    }
}
