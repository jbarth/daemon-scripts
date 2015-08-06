package scripts.daemon.plugin.maven;

import static scripts.daemon.plugin.maven.DaemonMojo.PARAM_APP_TYPE;
import static scripts.daemon.plugin.maven.DaemonMojo.PARAM_EXECUTABLE;
import static scripts.daemon.plugin.maven.DaemonMojo.PARAM_JAVA_HOME;
import static scripts.daemon.plugin.maven.DaemonMojo.PARAM_MAIN_METHOD;
import static org.hamcrest.Matchers.containsString;

import org.apache.maven.plugin.testing.MojoRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/***
 * Make sure the POM provies a valid application type
 */
public class ApplicationTypeValidationTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Rule
    public MojoRule mojoRule = new MojoRule();

    @Test
    public void noValueProvided() throws Exception {
        DaemonMojo daemonMojo = new DaemonMojo();

        exception.expect(IllegalArgumentException.class);
        exception.expectMessage(containsString("No value provided for property appType"));
        daemonMojo.validateParams();
    }

    @Test
    public void invalidValueProvided() throws Exception {
        DaemonMojo daemonMojo = new DaemonMojo();
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_APP_TYPE, "this-is-garbage");

        exception.expect(IllegalArgumentException.class);
        exception.expectMessage(containsString("'this-is-garbage' value provided for property appType is not supported"));
        daemonMojo.validateParams();
    }

    @Test
    public void javaWithValidParameters() throws Exception {
        DaemonMojo daemonMojo = new DaemonMojo();
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_APP_TYPE, ApplicationType.JAVA.toString());
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_JAVA_HOME, "A");
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_MAIN_METHOD, "A");

        daemonMojo.validateParams();
        // Do nothing, expect no exception
    }

    @Test
    public void javaNeedsJavaHome() throws Exception {
        DaemonMojo daemonMojo = new DaemonMojo();
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_APP_TYPE, ApplicationType.JAVA.toString());
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_JAVA_HOME, null);
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_MAIN_METHOD, "A");

        exception.expect(IllegalArgumentException.class);
        exception.expectMessage(containsString("'javaHome' is not optional"));

        daemonMojo.validateParams();
    }

    @Test
    public void javaNeedsMainMethod() throws Exception {
        DaemonMojo daemonMojo = new DaemonMojo();
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_APP_TYPE, ApplicationType.JAVA.toString());
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_JAVA_HOME, "123abc");
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_MAIN_METHOD, null);

        exception.expect(IllegalArgumentException.class);
        exception.expectMessage(containsString("'mainMethod' is not optional"));

        daemonMojo.validateParams();
    }

    @Test
    public void executableNeedsFiletoExecute() throws Exception {
        DaemonMojo daemonMojo = new DaemonMojo();
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_APP_TYPE, ApplicationType.EXECUTABLE.toString());
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_EXECUTABLE, null);

        exception.expect(IllegalArgumentException.class);
        exception.expectMessage(containsString("'executableFile' is not optional"));

        daemonMojo.validateParams();
    }

    @Test
    public void executableWithValidParameters() throws Exception {
        DaemonMojo daemonMojo = new DaemonMojo();
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_APP_TYPE, ApplicationType.EXECUTABLE.toString());
        mojoRule.setVariableValueToObject(daemonMojo, PARAM_EXECUTABLE, "asdf");

        daemonMojo.validateParams();
        // Do nothing, expect no exception
    }
}
