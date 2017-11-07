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
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.tools.Diagnostic;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import org.jenkinsci.plugins.diagnostics.DiagnosticsSession.DiagnosticsSessionListener;
import com.cloudbees.jenkins.support.api.Content;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

import hudson.BulkChange;
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import hudson.util.RobustCollectionConverter;
import jenkins.model.Jenkins;

/**
 * Holds all the {@link DiagnosticsSession}, providing methods to manage their lifecycle and persistence. While the
 * {@link DiagnosticsSession} are running, the status is persisted to disk automatically It will use a
 * {@link DiagnosticsConfig#LAZY_SAVE_MIN_DELAY} to avoid writing to many times to disk. When a
 * {@link DiagnosticsSession} is finished it will be persisted eagerly.
 */
@ThreadSafe
public class DiagnosticsConfig implements Saveable, Serializable, DiagnosticsSessionListener {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(DiagnosticsConfig.class.getName());

    /**
     * The minimum delay between lazy saves
     */
    private static final int LAZY_SAVE_MIN_DELAY = 1000;

    /**
     * Base directory name for the diagnostics
     */
    private static final String DIAGNOSTICS_DIRECTORY_NAME = "diagnostics";

    /**
     * Lock to allow only one thread to save the configuration without penalizing the read threads
     */
    private transient final ReadWriteLock lock;

    /**
     * Holds the {@link DiagnosticsSession} managed by this configuration
     */
    @GuardedBy("lock")
    private HashMap<String, DiagnosticsSession> sessions = new HashMap<String, DiagnosticsSession>();

    /**
     * Indicates if there is any pending to save changes on the sessions
     */
    @GuardedBy("lock")
    private transient boolean dirty;

    /**
     * When did we last save to disk
     */
    @GuardedBy("lock")
    private transient long lastSave;

    private static transient String rootDirectory;

    /**
     * Holds the singleton instance
     */
    static class ResourceHolder {
        private static final DiagnosticsConfig INSTANCE = new DiagnosticsConfig();
    }

    /**
     * Gets the singleton instance
     * 
     * @return the instance
     */
    @Nonnull
    static DiagnosticsConfig getInstance() {
        return ResourceHolder.INSTANCE;
    }

    /**
     * Creates a new {@link DiagnosticsConfig} and loads the configuration from disk
     */
    private DiagnosticsConfig() {
        lock = new ReentrantReadWriteLock();
        dirty = false;
        lastSave = 0;
        load();
    }

    /**
     * Gets the root directory for the diagnostics
     * 
     * @return The root directory for the diagnostics
     */
    @Nonnull
    static String getRootDirectory() {
        if (rootDirectory == null) { // Cache the root directory as Jenkins.getInstance() may fail when calling from the shutdown hook
            rootDirectory = Jenkins.getInstance().getRootDir() + File.separator + DIAGNOSTICS_DIRECTORY_NAME;
        }
        return rootDirectory;
    }

    /**
     * Gets a {@link DiagnosticsSession} by its id
     * 
     * @param id the {@link DiagnosticsSession} id
     * @return the {@link DiagnosticsSession} or <code>null</code> if not found
     */
    @CheckForNull
    DiagnosticsSession getSession(@Nonnull String id) {
        lock.readLock().lock();
        try {
            return sessions.get(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Adds a session to the configuration
     * 
     * @param session the session
     */
    void addSession(@Nonnull DiagnosticsSession session) {
        lock.writeLock().lock();
        try {
            dirty = true;
            sessions.put(session.getId(), session);
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * <code>true</code> if there is any session running
     * 
     * @return <code>true</code> if there is any session running
     */
    boolean isSessionRunning() {
        lock.readLock().lock();
        try {
            for (DiagnosticsSession session : sessions.values()) {
                if (session.isRunning()) {
                    return true;
                }
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the list of {@link DiagnosticsSession}
     * 
     * @return the list of {@link DiagnosticsSession}
     */
    @Nonnull
    List<DiagnosticsSession> getSessionList() {
        lock.readLock().lock();
        try {
            ArrayList<DiagnosticsSession> list = new ArrayList<DiagnosticsSession>(sessions.values());
            Collections.sort(list, new Comparator<DiagnosticsSession>() {
                @Override
                public int compare(DiagnosticsSession o1, DiagnosticsSession o2) {
                    Date d1 = o1.getStartDate();
                    Date d2 = o2.getStartDate();
                    if (d1 != null && d2 != null) {
                        return d1.compareTo(d2);
                    } else {
                        if (d2 != null) {
                            return -1;
                        } else {
                            return 0;
                        }
                    }
                }
            });
            return list;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Deletes a {@link DiagnosticsSession} from the session list and executes the delete method of the session to free
     * resources. A session can only be deleted if it isn't running
     * 
     * @param sessionId to look up for the session
     * @throws IOException if things go wrong
     * @throws IllegalStateException is the session is running
     */
    @Nonnull
    void deleteSession(@Nonnull String sessionId) throws IOException {
        lock.writeLock().lock();
        try {
            DiagnosticsSession diagnosticsSession = sessions.get(sessionId);
            if (diagnosticsSession != null) {
                if (diagnosticsSession.isRunning()) {
                    throw new IllegalStateException("Cannot delete a session while it is running. Cancel it first.");
                }
                sessions.remove(sessionId);
                diagnosticsSession.delete();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Loads serializable fields of this instance from the persisted storage.
     *
     * <p>
     * If there was no previously persisted state, this method is no-op.
     *
     */
    private synchronized void load() {
        try {
            XmlFile xml = getConfigXml();
            if (xml.exists()) {
                xml.unmarshal(this);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Couldt load Diagnostic Sessions from disk", e);
        }
    }

    /**
     * Saves serializable fields of this instance to the persisted storage.
     */
    @Override
    @Restricted(NoExternalUse.class)
    public void save() {
        lock.writeLock().lock();
        try {
            dirty = false;
            lastSave = System.currentTimeMillis();
            if (BulkChange.contains(this)) {
                return;
            }
            XmlFile config = getConfigXml();
            config.write(this);
            SaveableListener.fireOnChange(this, config);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save Diagnostics configuration", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void lazySave() {
        long timeMillis = System.currentTimeMillis();

        lock.readLock().lock();
        if (dirty && timeMillis - lastSave > LAZY_SAVE_MIN_DELAY) {
            lock.readLock().unlock();
            save();
        } else {
            lock.readLock().unlock();
        }
    }

    private void makeDirty() {
        lock.readLock().lock();
        if (!dirty) {
            lock.readLock().unlock();
            lock.writeLock().lock();
            if (!dirty) {
                dirty = true;
            }
            lock.writeLock().unlock();
        } else {
            lock.readLock().unlock();
        }
    }

    @Override
    @Restricted(NoExternalUse.class)
    public void notifyDiagnoticRunFinished(DiagnosticsSession session, DiagnosticRunner dr) {
        makeDirty();
        lazySave();
    }

    @Override
    @Restricted(NoExternalUse.class)
    public void notifyDiagnosticFinished(DiagnosticsSession session, DiagnosticRunner dr) {
        makeDirty();
        lazySave();
    }

    @Override
    public void notifySessionFinished(DiagnosticsSession session) {
        save(); // At session end we force a save
    }

    /**
     * Gets the file where {@link #load()} and {@link #save()} persists data.
     * 
     * @return the file where {@link #load()} and {@link #save()} persists data.
     */
    protected XmlFile getConfigXml() {
        return new XmlFile(Jenkins.XSTREAM, new File(getRootDirectory(), File.separator + "diagnosticsSessions.xml"));
    }

    // Customize the way we persist the sessions
    static {
        Jenkins.XSTREAM2.aliasPackage("diagnostic", "org.jenkinsci.plugins.diagnostics.diagnostics");
        Jenkins.XSTREAM2.alias("diagnosticRunner", DiagnosticRunner.class);
        Jenkins.XSTREAM2.alias("diagnosticsSession", DiagnosticsSession.class);
        Jenkins.XSTREAM2.alias("diagnostic", Diagnostic.class);
        Jenkins.XSTREAM2.alias("content", Content.class);
        Jenkins.XSTREAM2.alias("container", DiagnosticsContainer.class);
        Jenkins.XSTREAM2.alias("diagnosticsConfig", DiagnosticsConfig.class);
    }

    /**
     * Customized the persistence of this configurations. The list of {@link DiagnosticsSession} is persisted as a
     * simple list and rebuild into a hash table when loaded
     */
    public static class ConverterImpl extends RobustCollectionConverter {

        public ConverterImpl(XStream xs) {
            super(xs);
        }

        @Override
        public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
            DiagnosticsConfig src = (DiagnosticsConfig) source;
            writer.startNode("sessions");
            for (final Iterator<DiagnosticsSession> iter = src.sessions.values().iterator(); iter.hasNext();) {
                final DiagnosticsSession session = iter.next();
                writeItem(session, context, writer);
            }
            writer.endNode();
        }

        @Override
        public Object unmarshal(HierarchicalStreamReader reader, final UnmarshallingContext context) {
            DiagnosticsConfig config = (DiagnosticsConfig) context.currentObject();
            if (config == null) {
                config = new DiagnosticsConfig();
            }
            reader.moveDown();
            while (reader.hasMoreChildren()) {
                reader.moveDown();
                DiagnosticsSession session = (DiagnosticsSession) readItem(reader, context, config);
                config.sessions.put(session.getId(), session);
                reader.moveUp();
            }
            reader.moveUp();
            return config;
        }

        @Override
        public boolean canConvert(@SuppressWarnings("rawtypes") Class type) {
            return DiagnosticsConfig.class == type;
        }
    }

    private Object readResolve() {
        synchronized (this) {
            return getInstance();
        }
    }
}
