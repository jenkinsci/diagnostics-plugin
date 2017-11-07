/*
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
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

package org.jenkinsci.plugins.diagnostics.diagnostics;

import org.jenkinsci.plugins.diagnostics.DiagnosticsHelper;
import com.cloudbees.jenkins.support.SupportPlugin;
import com.cloudbees.jenkins.support.api.Component;
import com.cloudbees.jenkins.support.impl.AboutJenkins;
import com.cloudbees.jenkins.support.impl.AdministrativeMonitors;
import hudson.DescriptorExtensionList;
import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.model.Describable;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;

import static java.util.logging.Level.FINE;

/**
 * Diagnostic to help debug micro-hangs on a Jenkins instance. It generates a Support bundle for every execution.
 */
public class SupportBundleDiagnostic extends DefaultDiagnostic {
    private static final long serialVersionUID = 1L;
    /**
     * <p>field to store the configuration type of the support bundle to generate.</p>
     */
    private SupportBundleConfig supportBundleConfig;

    /**
     * Creates an instance
     */
    public SupportBundleDiagnostic() {
        super();
    }

    /**
     * Creates an instance.
     *
     * @param initialDelay        number milliseconds to delay the first execution.
     * @param period              period to wait between successive executions in milliseconds.
     * @param runs                number of times this diagnostic should be executed on the diagnostic session.
     * @param supportBundleConfig Type of support bundle.
     */
    @DataBoundConstructor
    public SupportBundleDiagnostic(int initialDelay, int period, int runs, @Nonnull SupportBundleConfig supportBundleConfig) {
        super(initialDelay, period, runs,"support-bundle",ZIP);
        this.supportBundleConfig = supportBundleConfig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void runDiagnostic(@Nonnull java.io.PrintWriter out, final int run) throws IOException {
        try (OutputStream output = getCurrentOutputStream()) {
            LOGGER.log(FINE, "SupportBundleDiagnostic run {0} file: {1}", new Object[]{run, currentFileName});
            LOGGER.log(FINE, "Support Bundle Type {0}", new Object[]{supportBundleConfig.getDescriptor().getDisplayName()});
            SupportPlugin.writeBundle(output, supportBundleConfig.getComponents());
            getDiagnosticLog().printf("%s - Support Bundle '%s'%n", DiagnosticsHelper.getDateFormat().format(new Date()), currentFileName);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error generating diagnostic report", e);
        }
    }

    /**
     * base class for support bundle configurations.
     */
    public static abstract class SupportBundleConfig implements ExtensionPoint, Describable<SupportBundleConfig>, Serializable {
        transient protected List<Component> components = new ArrayList<>();

        protected SupportBundleConfig() {
        }

        public Descriptor<SupportBundleConfig> getDescriptor() {
            return Jenkins.getInstance().getDescriptor(getClass());
        }

        public List<Component> getComponents() {
            return components;
        }
    }

    /**
     * descriptor for support bundles configurations.
     */
    public static class SupportBundleDescriptor extends Descriptor<SupportBundleConfig> {
        private String displayName;

        public SupportBundleDescriptor(Class<? extends SupportBundleConfig> clazz, String displayName) {
            super(clazz);
            this.displayName = displayName;
        }

        @Nonnull
        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * minimum support bundle configuration.
     */
    public static class BasicSupportBundle extends SupportBundleConfig {
        public static final String BASIC_SUPPORT_BUNDLE = "Basic Support Bundle";

        @DataBoundConstructor
        public BasicSupportBundle() {
            super();
            this.components.add(new AboutJenkins());
            this.components.add(new AdministrativeMonitors());
            this.components.add(new com.cloudbees.jenkins.support.impl.BuildQueue());
            this.components.add(new com.cloudbees.jenkins.support.impl.EnvironmentVariables());
            this.components.add(new com.cloudbees.jenkins.support.impl.FileDescriptorLimit());
            this.components.add(new com.cloudbees.jenkins.support.impl.JenkinsLogs());
        }

        @Extension
        public static final SupportBundleDescriptor D = new SupportBundleDescriptor(BasicSupportBundle.class, BASIC_SUPPORT_BUNDLE);
    }

    /**
     * medium support bundle configuration.
     */
    public static class MediumSupportBundle extends SupportBundleConfig {
        public static final String MEDIUM_SUPPORT_BUNDLE = "Medium Support Bundle";

        @DataBoundConstructor
        public MediumSupportBundle() {
            super();
            this.components.add(new AboutJenkins());
            this.components.add(new AdministrativeMonitors());
            this.components.add(new com.cloudbees.jenkins.support.impl.BuildQueue());
            this.components.add(new com.cloudbees.jenkins.support.impl.EnvironmentVariables());
            this.components.add(new com.cloudbees.jenkins.support.impl.FileDescriptorLimit());
            this.components.add(new com.cloudbees.jenkins.support.impl.JenkinsLogs());
            this.components.add(new com.cloudbees.jenkins.support.impl.LoadStats());
            this.components.add(new com.cloudbees.jenkins.support.impl.LoggerManager());
            this.components.add(new com.cloudbees.jenkins.support.impl.DumpExportTable());
            this.components.add(new com.cloudbees.jenkins.support.impl.ThreadDumps());
            this.components.add(new com.cloudbees.jenkins.support.impl.GCLogs());
        }

        @Extension
        public static final SupportBundleDescriptor D = new SupportBundleDescriptor(MediumSupportBundle.class, MEDIUM_SUPPORT_BUNDLE);
    }

    /**
     * Full support bundle configuration.
     */
    public static class FullSupportBundle extends SupportBundleConfig {
        public static final String FULL_SUPPORT_BUNDLE = "Full Support Bundle";

        @DataBoundConstructor
        public FullSupportBundle() {
            super();
            this.components.addAll(SupportPlugin.getComponents());
        }

        @Extension
        public static final SupportBundleDescriptor D = new SupportBundleDescriptor(FullSupportBundle.class, FULL_SUPPORT_BUNDLE);
    }

    public SupportBundleConfig getSupportBundleConfig() {
        return supportBundleConfig;
    }

    public void setSupportBundleConfig(SupportBundleConfig supportBundleConfig) {
        this.supportBundleConfig = supportBundleConfig;
    }

    @Extension
    public static class DescriptorImpl extends DiagnosticDescriptor<SupportBundleDiagnostic> {

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return "Support Bundle";
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getInitialDelay() {
            return 500;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getRuns() {
            return 1;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getPeriod() {
            return 60000;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isSelectedByDefault() {
            return false;
        }

        /**
         * @return default support bundle configuration type.
         */
        public SupportBundleConfig getSupportBundleConfig() {
            return new BasicSupportBundle();
        }

        /**
         * @return list of component that it is possible to configure on a support bundle.
         */
        public DescriptorExtensionList<SupportBundleConfig, Descriptor<SupportBundleConfig>> getSupportBundleDescriptor() {
            return Jenkins.getInstance().<SupportBundleConfig, Descriptor<SupportBundleConfig>>getDescriptorList(SupportBundleConfig.class);
        }
    }
}
