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
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

import org.apache.commons.lang.StringUtils;
import org.apache.tools.zip.ZipEntry;
import org.apache.tools.zip.ZipOutputStream;

import org.jenkinsci.plugins.diagnostics.diagnostics.Diagnostic;
import com.cloudbees.jenkins.support.api.Container;
import com.cloudbees.jenkins.support.api.Content;

/**
 * A {@link Container} that will store all the {@link DiagnosticsSession} and {@link Diagnostic} related information.
 * When serialized to the configuration file it will store some basic information like the base folder, name, etc.
 * Thread safety is guaranteed to allow concurrent diagnostic runs to store information.
 */
@ThreadSafe
public class DiagnosticsContainer extends Container implements Serializable {
    private static final long serialVersionUID = 1L;

    static final Logger LOGGER = Logger.getLogger(DiagnosticsSession.class.getName());

    /**
     * Name of the container, used for content creation
     */
    private final String name;

    /**
     * Parent of the container, recursive container will be nested on the final ZIP file and work folders
     */
    private final DiagnosticsContainer parent;

    /**
     * Relative path from the parent
     */
    private final String relativeFolderPath;

    /**
     * Full folder path
     */
    private final String folderPath;

    /**
     * Additional details to be added to the manifest
     */
    private String manifestDetails;

    /**
     * Content belonging to this {@link Content} to be included
     */
    private final transient ConcurrentLinkedQueue<Content> toProcessContent;

    /**
     * Nested {@link DiagnosticsContainer} containers to be included
     */
    private final transient ConcurrentLinkedQueue<DiagnosticsContainer> toProcessContainers;

    /**
     * Creates a {@link DiagnosticsContainer} nested into a parent
     * 
     * @param name name of the container
     * @param parent container
     * @param relativeFolderPath relative folder from the parent
     */
    DiagnosticsContainer(@Nonnull String name, @Nonnull DiagnosticsContainer parent, @Nonnull String relativeFolderPath) {
        super();
        toProcessContent = new ConcurrentLinkedQueue<Content>();
        toProcessContainers = new ConcurrentLinkedQueue<DiagnosticsContainer>();
        this.name = name;
        this.parent = parent;
        this.relativeFolderPath = relativeFolderPath;
        this.folderPath = parent.getFolderPath() + File.separator + relativeFolderPath;
    }

    /**
     * Creates a {@link DiagnosticsContainer}
     * 
     * @param name name of the container
     * @param folderPath the full work folder path
     */
    DiagnosticsContainer(@Nonnull String name, @Nonnull String folderPath) {
        super();
        toProcessContent = new ConcurrentLinkedQueue<Content>();
        toProcessContainers = new ConcurrentLinkedQueue<DiagnosticsContainer>();
        this.name = name;
        this.parent = null;
        this.relativeFolderPath = null;
        this.folderPath = folderPath;
    }

    /**
     * Constructor for deserialisation
     * 
     * @param name name of the container
     * @param parent the parent
     * @param relativeFolderPath the relative path
     * @param folderPath the full work folder path
     * @param manifestDetails the manifest details
     */
    DiagnosticsContainer(String name, DiagnosticsContainer parent, String relativeFolderPath, String folderPath, String manifestDetails) {
        super();
        toProcessContent = new ConcurrentLinkedQueue<Content>();
        toProcessContainers = new ConcurrentLinkedQueue<DiagnosticsContainer>();
        this.name = name;
        this.parent = parent;
        this.relativeFolderPath = relativeFolderPath;
        this.folderPath = folderPath;
        this.manifestDetails = manifestDetails;
    }

    protected Object readResolve() {
        synchronized (this) {
            // populate transient fields
            return new DiagnosticsContainer(name, parent, relativeFolderPath, folderPath, manifestDetails);
        }
    }

    @Override
    public void add(@CheckForNull Content content) {
        if (content != null && toProcessContent != null) { // toProcessContent can be null if the class was deserialized
            toProcessContent.add(content);
        }
    }

    /**
     * Adds a nested container
     * 
     * @param container the nested container
     */
    public void add(@CheckForNull DiagnosticsContainer container) {
        if (toProcessContainers != null) { // toProcessContainers can be null if the class was deserialized
            toProcessContainers.add(container);
        }
    }

    /**
     * Gets the name of the container
     * 
     * @return the name of the container
     */
    @Nonnull
    public String getName() {
        return name;
    }

    /**
     * Gets the full path of the folder on disk
     * 
     * @return the full path of the folder on disk
     */
    @Nonnull
    public String getFolderPath() {
        return folderPath;
    }

    /**
     * Gets the relative path inside the container
     * 
     * @return the relative path inside the container
     */
    @CheckForNull
    public String getRelativeFolderPath() {
        return relativeFolderPath;
    }

    /**
     * Gets the parent {@link DiagnosticsContainer}
     * 
     * @return the parent {@link DiagnosticsContainer}
     */
    @CheckForNull
    public DiagnosticsContainer getParent() {
        return parent;
    }

    /**
     * Writes the diagnostic session result to the provided {@link ZipOutputStream} and deletes the temp folders if
     * everything goes as expected.
     * 
     * @param zip - {@link ZipOutputStream} to write the results to
     * @throws IOException - in case of errors writing the results
     */
    synchronized void writeSessionResults(@Nonnull ZipOutputStream zip) throws IOException {

        try (StringWriter manifest = new StringWriter();
                PrintWriter manifestWriter = new PrintWriter(manifest);
                StringWriter errors = new StringWriter();
                PrintWriter errorWriter = new PrintWriter(errors);) {

            String title = Messages.DiagnosticsAction_ActionTitle();
            manifestWriter.println(title);
            manifestWriter.println(StringUtils.repeat("=", title.length()));
            manifestWriter.println();
            manifestWriter.printf("Generated on %s%n%n", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ").format(new Date()));
            manifestWriter.println("Included diagnostics:");
            manifestWriter.println();

            doWriteResult(zip, manifestWriter, errorWriter, 0);

            // add the manifest to the zip file
            manifestWriter.flush();
            String manifestContent = manifest.toString();
            try {
                zip.putNextEntry(new ZipEntry("manifest.md"));
                zip.write(manifestContent.getBytes("utf-8"));
            } catch (IOException e) {
                LogRecord logRecord = new LogRecord(Level.WARNING, "Could not write manifest.md to support bundle");
                logRecord.setThrown(e);
                logRecord.setParameters(new Object[] { name });
                LOGGER.log(logRecord);
                errorWriter.println(MessageFormat.format("Could not write manifest.md to support bundle", name));
                errorWriter.println("-----------------------------------------------------------------------");
                errorWriter.println();
                e.printStackTrace(errorWriter);
                errorWriter.println();
            }

            // add the error log to the zip file
            errorWriter.flush();
            String errorContent = errors.toString();
            if (!StringUtils.isBlank(errorContent)) {
                try {
                    zip.putNextEntry(new ZipEntry("manifest/errors.txt"));
                    zip.write(errorContent.getBytes("utf-8"));
                } catch (IOException e) {
                    // ignore
                }
            }
            zip.flush();
            LOGGER.info("Write diagnostic file finished");
        }
    }

    /**
     * Sets additional detail information to be included on the manifest file
     * 
     * @param details to be included on the manifest
     */
    public synchronized void setManifestDetails(@Nonnull String details) {
        this.manifestDetails = details;
    }

    /**
     * Write the results specific to this container
     * 
     * @param zip - {@link ZipOutputStream} to write the results to
     * @param manifestWriter - used to write the manifest information
     * @param errorWriter - used to write any error that may be encountered
     * @param level - used for indentation level inside the manifest
     * @throws IOException - in case of errors writing to the zip file
     */
    private synchronized void doWriteResult(@Nonnull ZipOutputStream zip, @Nonnull PrintWriter manifestWriter, @Nonnull PrintWriter errorWriter, int level) throws IOException {
        String prefix = StringUtils.repeat("  ", level);

        if (name != null) {
            manifestWriter.printf("%s * %s%n", prefix, name);
        }
        if (manifestDetails != null) {
            manifestWriter.println(DiagnosticsHelper.prefixLines(prefix, manifestDetails));
        }

        // Process our own content
        if (toProcessContent != null) { // Can happen when the class has been deserialized
            while (!toProcessContent.isEmpty()) {
                Content c = toProcessContent.poll();
                if (c == null) {
                    continue;
                }

                final String name = getRelativeFolderPath() != null ? getRelativeFolderPath() + "/" + c.getName() : c.getName();
                try {
                    manifestWriter.printf("%s   - `%s`%n", prefix, name);

                    ZipEntry entry = new ZipEntry(name);
                    entry.setTime(c.getTime());
                    zip.putNextEntry(entry);
                    c.writeTo(zip);
                } catch (Throwable e) {
                    LogRecord logRecord = new LogRecord(Level.WARNING, "Could not attach ''{0}'' to support bundle");
                    logRecord.setThrown(e);
                    logRecord.setParameters(new Object[] { name });
                    LOGGER.log(logRecord);
                    errorWriter.println(MessageFormat.format("Could not attach ''{0}'' to support bundle", name));
                    errorWriter.println("-----------------------------------------------------------------------");
                    errorWriter.println();
                    e.printStackTrace(errorWriter);
                    errorWriter.println();
                } finally {
                    zip.flush();
                }
            }
        }

        // Process the nested containers
        if (toProcessContainers != null) {
            while (!toProcessContainers.isEmpty()) {
                toProcessContainers.poll().doWriteResult(zip, manifestWriter, errorWriter, level + 1);
            }
        }
    }

}
