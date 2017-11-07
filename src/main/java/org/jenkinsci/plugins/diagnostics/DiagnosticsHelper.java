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

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

/**
 * Helper class for common methods
 * 
 */
public class DiagnosticsHelper {

    private static String processId;

    /**
     * Returns a default date format to use when printing diagnostic information and generating file names
     * 
     * @return default date format to use when printing diagnostic information
     */
    @Nonnull
    public static DateFormat getDateFormat() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss.SSS'Z'");
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        return dateFormat;
    }

    /**
     * Helper method to calculate the current Jenkins process Id
     * 
     * @return the process id
     */
    @Nonnull
    public static String getProcessId() {
        if (processId == null) {
            initProcessId();
        }
        return processId;
    }

    private static synchronized void initProcessId() {
        if (processId == null) {
            RuntimeMXBean mBean = ManagementFactory.getRuntimeMXBean();
            String process = mBean.getName();
            Matcher processMatcher = Pattern.compile("^(-?[0-9]+)@.*$").matcher(process);
            if (processMatcher.matches()) {
                processId = processMatcher.group(1).trim();
            } else {
                processId = "UNKNOWN";
            }
        }
    }

    /**
     * Prepends a given <code>prefix</code> to all the lines on the <code>input</code> <code>String</code>.
     * 
     * @param prefix the prefix to prepend
     * @param input the <code>String</code> to be processed
     * @return the result <code>String</code>
     */
    public static String prefixLines(String prefix, String input) {
        return prefix + input.replaceAll("(?:\r\n?|\n)(?!\\z)", "$0" + Matcher.quoteReplacement(prefix));
    }
}
