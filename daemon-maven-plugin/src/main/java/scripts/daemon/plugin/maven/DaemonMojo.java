package scripts.daemon.plugin.maven;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.codehaus.plexus.util.IOUtil;

/***
 * Maven step that will generate a customised Daemon application script
 */
@Mojo(name = "generate-daemon")
public class DaemonMojo extends AbstractMojo {

    /** The directory in which to store the daemon files */
    @Parameter(property = "outputDirectory", required = true)
    private String outputDirectory;

    /** The name of the application that is being boostrapped, used for logging */
    @Parameter(property = "applicationName", required = true)
    private String applicationName;

    /** The filename that the script should have */
    @Parameter(property = "scriptName", required = true)
    private String scriptName;

    /** The application's main method */
    @Parameter(property = PARAM_MAIN_METHOD, required = false)
    private String mainMethod;

    /** The name of the generic executable file */
    @Parameter(property = PARAM_EXECUTABLE, required = false)
    private String executableFile;

    /** The type of application */
    @Parameter(property = PARAM_APP_TYPE, defaultValue = "")
    private String appType;

    /** The JVM arguments to pass at application startup */
    @Parameter(property = "jvmArgs", defaultValue = "")
    private String jvmArgs;

    /** The arguments to pass to the application's main method */
    @Parameter(property = "appArgs", defaultValue = "")
    private String appArgs;

    /** The additional items to put on the classpath */
    @Parameter(property = "classpath", required = false)
    private String[] additionalClasspath;

    /** The java home directory to use to execute the code */
    @Parameter(property = PARAM_JAVA_HOME, required = false)
    private String javaHome;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject mavenProject;

    /** The directory that contains the common scripts, bundled inside the jar */
    private static final String COMMON_SCRIPTS = "/scripts-bundled/common-scripts.zip";

    /** The daemon script file, which will be copied-over and renamed during build */
    private static final String DAEMON_SCRIPT = "/scripts-bundled/daemon";

    /** The name format of the extension script which will support the given application type */
    private static final String DAEMON_SCRIPT_APPLICATION_EXTENSION = "/scripts-bundled/daemon-%s";

    /** The application configuration file that will be written during the build */
    private static final String APP_CONFIG_FILE = "app-config.sh";

    /**
     * The classpath item delimiter is platform-dependant, we need to use an env variable in the value provided by
     * this plugin, and let the shell script replace it at runtime
     */
    private static final String CLASSPATH_DELIMITER = "${CLASSPATH_FROM_CONFIG}";

    /** Definition of parameter names expected in the POM file */
    public static final String
            PARAM_APP_TYPE = "appType",
            PARAM_EXECUTABLE = "executableFile",
            PARAM_MAIN_METHOD = "mainMethod",
            PARAM_JAVA_HOME = "javaHome";

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path outputDirectory = createOutputDirectory();

        validateParams();

        try {

            /* The common scripts (shared across multiple projects) will be bundled as a resource zip with this jar.
             * Therefore, we simply need to decompress / extract it to the target directory. */
            copyCommonScripts(outputDirectory);

            /* Copy the daemon script to the output directory. The user has the ability to state the filename of the
             * script, thus the output file will have a different name. */
            copyDaemonScript(outputDirectory);

            /* Write the application config out to the shell script that will be sourced by the daemon script when it
             * is invoked. */
            writeApplicationConfig(outputDirectory);

        } catch (MojoFailureException ex) {
            getLog().error(String.format("Error producing daemon: %s", ex.getMessage()), ex);
            throw ex;
        }
    }

    /***
     * The daemon script can launch either Java applications or generic executables. Here we need to validate that the
     * {@link #appType} provided is a supported value.
     * <p />
     *
     * NOTE: This is a new parameter, but we're going to make it mandatory. Therefore, we need a nice error if it is not
     * present, as downstream consumers will run into this problem.
     */
    protected void validateParams() {
        ApplicationType parsedAppType = null;

        if (StringUtils.isNoneBlank(appType)) {
            try {
                parsedAppType = ApplicationType.valueOf(appType);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(String.format(
                    "The '%s' value provided for property %s is not supported. Supported types: %s",
                    appType, PARAM_APP_TYPE, Arrays.toString(ApplicationType.values())), ex);
            }
        } else {
            throw new IllegalArgumentException(String.format(
                "No value provided for property %s. Supported types: %s", PARAM_APP_TYPE, Arrays.toString(ApplicationType.values())));
        }

        switch (parsedAppType) {
            case EXECUTABLE:
                if (StringUtils.isBlank(executableFile)) {
                    throw new IllegalArgumentException(String.format("Parameter '%s' is not optional when application type is %s",
                        PARAM_EXECUTABLE, ApplicationType.EXECUTABLE));
                }
                break;
            case JAVA:
                if (StringUtils.isBlank(javaHome)) {
                    throw new IllegalArgumentException(String.format("Parameter '%s' is not optional when application type is %s",
                        PARAM_JAVA_HOME, ApplicationType.JAVA));
                }

                if (StringUtils.isBlank(mainMethod)) {
                    throw new IllegalArgumentException(String.format("Parameter '%s' is not optional when application type is %s",
                        PARAM_MAIN_METHOD, ApplicationType.JAVA));
                }
                break;
            default:
                /* NO-OP: No validation to do? */
                break;
        }
    }

    /***
     * Copy the common scripts from the input directory to the output directory.
     * <p />
     *
     * This is slightly more complicated than you'd hope, as we need to copy the contents to the same paths, not copy
     * the directory itself.
     *
     * @param outputDirectory The directory to copy the files to
     * @throws MojoFailureException
     */
    private void copyCommonScripts(Path outputDirectory) throws MojoFailureException {

        /* The plexus ZipUnArchiver only works with java Files. Therefore, we need to copy zip over to a temp file
         * outside of the jar before we can extract it. */

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("common-scripts", ".zip");

            copyFromJarToFileSystem(COMMON_SCRIPTS, tempFile);

            // Actually perform the unzip
            ZipUnArchiver unzipper = new ZipUnArchiver(tempFile.toFile());
            unzipper.enableLogging(new ConsoleLogger());
            unzipper.setDestDirectory(outputDirectory.toFile());
            unzipper.extract();
        } catch (IOException ex) {
            throw new MojoFailureException("Error whilst extracting scripts file from JAR", ex);
        } finally {
            try {
                // Make sure we tidy-up... remove the temp file
                if (tempFile != null) {
                    Files.delete(tempFile);
                }
            } catch (IOException ex) {
                throw new MojoFailureException("Could not clean-up temp file", ex);
            }
        }
    }

    /**
     * Determines the user the application should run under in the given environment.
     */
    private String determineUserForEnvironment(String environment) throws MojoFailureException {
        String userPropertyName = "smf.user." + environment;
        final String userForEnvironment = mavenProject.getProperties().getProperty(userPropertyName);
        if (StringUtils.isEmpty(userForEnvironment)) {
            throw new MojoFailureException(String.format("Could not determine user for environment %s. Please define this under the maven property %s!", environment, userPropertyName));
        }
        return userForEnvironment;
    }

    /***
     * Copy the daemon script from within the current jar onto the filesystem in the target directory. This will also
     * rename the script, to give it the name specified by the user.
     *
     * @param outputDirectory The output directory, to copy the file into
     * @throws MojoFailureException If the file could not be copied over.
     */
    private void copyDaemonScript(Path outputDirectory) throws MojoFailureException {
        try {
            // Copy the main daemon script, using the correct name
            copyFromJarToFileSystem(DAEMON_SCRIPT, outputDirectory.resolve(scriptName));

            // Copy the application-specific extensions to the daemon script
            String extensionFilename = String.format(DAEMON_SCRIPT_APPLICATION_EXTENSION, appType.toString().toLowerCase());
            copyFromJarToFileSystem(extensionFilename,
                outputDirectory.resolve(Paths.get(extensionFilename).getFileName()));

        } catch (IOException ex) {
            throw new MojoFailureException("Could not copy daemon script into place", ex);
        }
    }

    /***
     * The user will have specified a number of parameters as part of the configuration for this Maven plugin. These
     * simply need transposing into the shell script config file, which will be sourced by the daemon script at
     * startup.
     *
     * @param outputDirectory The output directory, in which the config file should be written.
     * @throws MojoFailureException If the file could not be written
     */
    private void writeApplicationConfig(Path outputDirectory) throws MojoFailureException {
        FileWriter propertyWriter = null;
        try {
            // Setup the properties
            Map<String, String> config = new HashMap<>();
            config.put("APP_NAME", applicationName);
            config.put("APP_TYPE", appType.toLowerCase());
            config.put("APP_MAINMETHOD", mainMethod);
            config.put("APP_JVM_ARGS", processArguments(getIfPresent(jvmArgs)));
            config.put("APP_CLI_ARGS", processArguments(getIfPresent(appArgs)));
            config.put("APP_JAVA_HOME", javaHome);
            config.put("APP_EXECUTABLE", executableFile);

            if (additionalClasspath != null && additionalClasspath.length > 0) {
                config.put("CLASSPATH_FROM_CONFIG", Joiner.on(CLASSPATH_DELIMITER).join(additionalClasspath));
            }

            // Then write them to a file
            propertyWriter = new FileWriter(Paths.get(this.outputDirectory).resolve(APP_CONFIG_FILE).toFile());
            for (Map.Entry<String, String> entry : config.entrySet()) {
                propertyWriter.write(String.format("%s=\"%s\"%n", entry.getKey(), entry.getValue()));
            }

        } catch (Exception ex) {
            throw new MojoFailureException("Could not create applciation properties file", ex);
        } finally {
            IOUtil.close(propertyWriter);
        }
    }

    /**
     * Strip breaking whitespace from JVM/program arguments and fail on double quotes. Note that this method will also
     * trim additional leading/trailing whitespace from arguments.
     *
     * @param arguments Program or JVM arguments as a string from the POM
     * @return processed arguments with line breaks removed
     * @throws org.apache.maven.plugin.MojoFailureException if a double quote character is encountered
     */
    protected String processArguments(String arguments) throws MojoFailureException {
        if (arguments.contains("\"")) {
            throw new MojoFailureException(
                String.format("Double quotes are not supported in arguments - please use single quotes instead. " +
                    "Offending arguments string: %s", arguments));
        }

        Iterable<String> parts = Splitter.on(CharMatcher.BREAKING_WHITESPACE)
            .trimResults(CharMatcher.WHITESPACE)
            .omitEmptyStrings()
            .split(arguments);

        String processed = Joiner.on(" ").join(parts);
        return processed;
    }

    /***
     * Maven is a bit daft and if the string is null it will assign it the value "null" so if we get the null string, we
     * will using the empty string in its place.
     *
     * @param st The String to check
     * @return String Either an empty string of the original string
     */
    private String getIfPresent(String st) {
        return (st == null) ? StringUtils.EMPTY : st;
    }

    /***
     * Ensure that the output directory is present and valid.
     *
     * @return The output directory that has been created (as needed)
     * @throws MojoFailureException If the directory could not be created
     */
    private Path createOutputDirectory() throws MojoFailureException {
        Path directory = Paths.get(outputDirectory);

        if (!Files.exists(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (IOException ex) {
                throw new MojoFailureException(String.format("Cannot create directory '%s'", outputDirectory), ex);
            }
        }

        return directory;
    }

    /***
     * Copy the given file from inside the current JAR onto the filesystem
     *
     * @param jarPath The file path, locating the resource within the JAR
     * @param filesystemPath The path to extract the file to
     * @throws IOException If the file could not be extracted
     */
    private static final void copyFromJarToFileSystem(String jarPath, Path filesystemPath) throws IOException {
        InputStream jarFileStream = null;
        FileOutputStream outputStream = null;

        try {

            jarFileStream = DaemonMojo.class.getResourceAsStream(jarPath);
            if (jarFileStream == null) {
                throw new IllegalArgumentException(String.format("File '%s' could not be found in JAR", jarPath));
            }

            outputStream = new FileOutputStream(filesystemPath.toFile());

            byte[] temp = new byte[1024];

            int bytesRead = 0;
            while ((bytesRead = jarFileStream.read(temp)) != -1) {
                outputStream.write(temp, 0, bytesRead);
            }

        } finally {
            IOUtil.close(jarFileStream);
            IOUtil.close(outputStream);
        }
    }

}
