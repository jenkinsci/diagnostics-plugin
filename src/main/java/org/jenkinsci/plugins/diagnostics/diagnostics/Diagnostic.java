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

package org.jenkinsci.plugins.diagnostics.diagnostics;

import java.io.IOException;
import java.io.Serializable;

import javax.annotation.Nonnull;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import org.jenkinsci.plugins.diagnostics.DiagnosticsContainer;

import hudson.DescriptorExtensionList;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import jenkins.model.Jenkins;

/**
 * Extension point that allows the creation of new diagnostics.
 */
public abstract class Diagnostic extends AbstractDescribableImpl<Diagnostic> implements ExtensionPoint, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Indicates the number milliseconds to delay the first execution
     */
    private int initialDelay;

    /**
     * The period to wait between successive executions in milliseconds
     */
    private int period;

    /**
     * Number of times this diagnostic should be executed on the diagnostic session
     */
    private int runs;

    /**
     * Creates a diagnostic
     */
    public Diagnostic() {
    }

    /**
     * Creates a diagnostic
     * 
     * @param initialDelay number milliseconds to delay the first execution
     * @param period period to wait between successive executions in milliseconds
     * @param runs number of times this diagnostic should be executed on the diagnostic session
     */
    public Diagnostic(int initialDelay, int period, int runs) {
        super();
        this.initialDelay = initialDelay;
        this.period = period;
        this.runs = runs;
    }

    /**
     * Sets the number milliseconds to delay the first execution
     * 
     * @param initialDelay milliseconds to delay the first execution
     */
    public void setInitialDelay(int initialDelay) {
        this.initialDelay = initialDelay;
    }

    /**
     * Indicates the number milliseconds to delay the first execution
     * 
     * @return the time to delay first execution in milliseconds
     */
    public int getInitialDelay() {
        return initialDelay;
    }

    /**
     * Sets the period to wait between successive executions in milliseconds
     * 
     * @param period to wait between successive executions in milliseconds
     */
    public void setPeriod(int period) {
        this.period = period;
    }

    /**
     * The period to wait between successive executions in milliseconds
     * 
     * @return the period between successive executions in milliseconds
     */
    public int getPeriod() {
        return period;
    }

    /**
     * Sets the number of times this diagnostic should be executed on the diagnostic session
     * 
     * @param runs number of times this diagnostic should be executed on the diagnostic session
     */
    public void setRuns(int runs) {
        this.runs = runs;
    }

    /**
     * Number of times this diagnostic should be executed on the diagnostic session
     * 
     * @return the number of times to execute the diagnose
     */
    public int getRuns() {
        return runs;
    }

    /**
     * By default, the {@link Class#getName} of the component implementation
     * 
     * @return the id for this diagnostic
     */
    @Nonnull
    public String getId() {
        return getClass().getName();
    }

    /**
     * Name used for the folder and files to be created. When creating multiple files some information will be
     * post-pended to it.
     * 
     * @return the simple name
     */
    @Nonnull
    public abstract String getFileName();

    /**
     * Executes the diagnostic
     * 
     * @param result {@link DiagnosticsContainer} where the diagnostic should write the results
     * @param run execution number of this call
     * @throws IOException thrown when there is a problem executing the diagnostic
     */
    public abstract void runDiagnostic(@Nonnull DiagnosticsContainer result, final int run) throws IOException;

    /**
     * Called before the first execution of the first run of this diagnostic. Can be used to initialize resources,
     * create content, etc.
     * 
     * @param result the {@link DiagnosticsContainer} to store the result to
     * @throws IOException thrown when there is a problem executing the diagnostic
     */
    public void beforeExecutionStart(DiagnosticsContainer result) throws IOException {
    }

    /**
     * Called when all the executions have been completed or the diagnostics session has been canceled. Can be used to
     * clean up any open resources and include additional information to the container like manifest data, etc.
     * 
     * @param result the {@link DiagnosticsContainer} to store the result to
     * @throws IOException thrown when there is a problem executing the diagnostic
     */
    public void afterExecutionFinished(@Nonnull DiagnosticsContainer result) throws IOException {
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        return getId().equals(((Diagnostic) obj).getId());
    }

    @Override
    public DiagnosticDescriptor<?> getDescriptor() {
        return (DiagnosticDescriptor<?>) super.getDescriptor();
    }

    /**
     * All the registered {@link DiagnosticDescriptor}s.
     * 
     * @return All the registered {@link DiagnosticDescriptor}s.
     */
    @Nonnull
    @Restricted(NoExternalUse.class)
    public static DescriptorExtensionList<Diagnostic, DiagnosticDescriptor<?>> all() {
        return Jenkins.getInstance().<Diagnostic, DiagnosticDescriptor<?>> getDescriptorList(Diagnostic.class);
    }
}
