# Diagnostics Plugin

Jenkins plugin which allow the execution of long running diagnostics to aid the identification and resolution of problems on a Jenkins installation.

This plugin is maintained by CloudBees.

See also this [plugin's wiki page][wiki]

## Summary

The plugin provides a new option called Diagnostics on the left menu which allows the execution of diagnostics sessions. A list of Diagnosers is presented with a check-box to select which one to run.
When the user clicks on "Run Diagnostics" a Diagnose Session is initiated, and status of the session is displayed on the same page.
After the diagnose session is finished a download button is displayed, allowing the user to download a ZIP file with the results of all the diagnostic runs.

An extension point  `org.jenkinsci.plugins.diagnostics.Diagnoser` is provided to allow any other plugin to contribute new `Diagnosers` which could help diagnose problems related to their specific functionality.

## Implementation details
The `Diagnoser` is the extension point a plugin must implement to run diagnostics, it allows defining the number of times the diagnostic has to be run, the initial delay and the period to run them. Also some information about the diagnostic like short name and display name.

The `DiagnosticsSession` is responsible for grouping all the selected `Diagnoser`s and run them by means of a `DiagnosticRunner` which is a `Runnable` that controls timing, repetitions and notifies the session when the diagnostic is finished.

An independent thread pool is used, managed by `DiagnosticExecutor` because if we used the standard Jenkins scheduler we wouldn't be able to run any diagnostics when that thread pool is with problems. The thread pool is configured to aggressively kill the threads when not in use, in order to reduce footprint.

Every `DiagnosticSession` has a session folder under the "support/diagnostics" folder and every 'Diagnoser' another one inside. When the session is finished the folder is zipped and deleted, allowing the user to download the zip.


## Usage

### Running a session

To execute a new session visit "/diagnostics" or click on the "Diagnostics" link on the left menu. Select the diagnostics[1] to run and click on "Run Diagnostics".
The session status will be displayed on the page.
After the session is finished it will be possible to download the session result in a Zip file by pressing the download button.

[1] The list of available diagnostics will depend on the available Diagnosers and the current user permissions.

## Contributing a Diagnoser
A plugin can contribute new diagnostics by implementing a 'Diagnoser' extension point.

This is a list of the main overridable methods and the usage:
* `runDiagnose` - *Mandatory*. Executes the diagnostic implemented by the overriding `Diagnoser`.
* `getFileName` - *Mandatory*. Define the name of the files and folders to be created. When creating multiple files some information will be post-pended to it.
* `getDisplayName` - *Mandatory*. User friendly name used to display the diagnoser on the screen.
* `getRuns` - Number of times this diagnostic should be executed on the diagnostic session
* `getPeriod` - The period to wait between successive executions in millis
* `getInitialDelay` - Indicates the number millis to delay the first execution
* `isEnabled` - Returns `true` if the current authentication can include this diagnostic. It also allows an implementer to enable or disable it self depending on any given condition
* `isSelectedByDefault` - Indicates if this Diagnostic should be selected by default when a user is going to create a Diagnostic Session.
*

### Example

The following example will run a Thread dump every 10 seconds for 3 times.

```Java
package org.jenkinsci.plugins.diagnostics;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Date;

import com.cloudbees.jenkins.support.impl.ThreadDumps;

import hudson.Extension;

/**
 * Generates a thread dump every few seconds.
 */
@Extension
public class ResponsivenessDiagnoser extends Diagnoser {

    @Override
    public void runDiagnostic(OutputStream out) throws IOException {
        try (PrintStream ps = new PrintStream(out, false, "UTF-8")) {
            ps.println("=== Thread dump at " + new Date() + " ===");
            ThreadDumps.threadDumpModern(out);
            ps.flush();
        }
    }

    @Override
    public String getFileName() {
        return "threadDump";
    }

    @Override
    public String getDisplayName() {
        return "Responsiveness Diagnose - ThreadDumps";
    }

    @Override
    public int getRuns() {
        return 3;
    }

    @Override
    public int getPeriod() {
        return 10;
    }
}
```

## Plugin Development

### Environment

The following build environment is required to build this plugin

* `java-1.7` and `maven-3`

### Build

To build the plugin locally:

    mvn clean package

### Release

To release the plugin:

    mvn release:prepare release:perform -B

### Test local instance

To test in a local Jenkins instance

    mvn hpi:run

[wiki]: https://wiki.jenkins.io/display/JENKINS/Diagnostics+Plugin

## License
[MIT License](http://opensource.org/licenses/MIT)

## Changelog
[Change log](CHANGELOG.md)
