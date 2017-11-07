package org.jenkinsci.plugins.diagnostics.diagnostics;

import hudson.model.Descriptor;
import jenkins.model.Jenkins;

/**
 * Describes a {@link Diagnostic}
 *
 * @param <T> The concrete {@link Diagnostic} class
 */
public abstract class DiagnosticDescriptor<T extends Diagnostic> extends Descriptor<Diagnostic> {

    /**
     * Creates the descriptor
     */
    public DiagnosticDescriptor() {
        super();
    }

    /**
     * Creates the descriptor specifying the concrete {@link Diagnostic} class
     * 
     * @param clazz the {@link Diagnostic} class
     */
    public DiagnosticDescriptor(Class<? extends Diagnostic> clazz) {
        super(clazz);
    }

    /**
     * Indicates if this Diagnostic should be selected by default when a user is going to create a Diagnostic Session.
     * 
     * @return {@code true} if should be selected by default
     */
    public boolean isSelectedByDefault() {
        return true;
    }

    /**
     * Returns {@code true} if the current authentication can include this diagnostic. It also allows an implementer to
     * enable or disable it self depending on any given condition
     * 
     * @return {@code true} if the diagnostic should be enabled.
     */
    public boolean isEnabled() {
        return Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER);
    }

    /**
     * Gets the default initial delay
     * 
     * @see Diagnostic#initialDelay
     * @return the default initial delay
     */
    public int getInitialDelay() {
        return 500;
    }

    /**
     * Gets the default number of runs
     * 
     * @see Diagnostic#runs
     * @return the default number of runs
     */
    public int getRuns() {
        return 1;
    }

    /**
     * Gets the default period
     * 
     * @see Diagnostic#period
     * @return the default period
     */
    public int getPeriod() {
        return 1000;
    }
}