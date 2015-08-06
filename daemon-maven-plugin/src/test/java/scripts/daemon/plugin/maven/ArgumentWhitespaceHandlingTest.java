package scripts.daemon.plugin.maven;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import org.apache.maven.plugin.MojoFailureException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/***
 * The JVM and Application arguments may be split over several lines in the XML file, therefore there are some
 * special considerations to take when using the values injected by maven...
 */
public class ArgumentWhitespaceHandlingTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Test
    public void testTrimLineFeeds() throws Exception {
        DaemonMojo daemonMojo = new DaemonMojo();

        String result = daemonMojo.processArguments("A\rB\rC");
        assertThat("Line feeds should be trimmed from arguments", result, is("A B C"));
    }

    @Test
    public void testTrimDoubleSpaces() throws Exception {
        DaemonMojo daemonMojo = new DaemonMojo();

        String result = daemonMojo.processArguments("A   B  C");
        assertThat("Additional spaces should be trimmed from arguments", result, is("A B C"));
    }

    @Test
    public void testSupportForDoubleQuotes() throws Exception {
        DaemonMojo daemonMojo = new DaemonMojo();

        exception.expect(MojoFailureException.class);
        exception.expectMessage(containsString("Double quotes are not supported in arguments"));
        daemonMojo.processArguments("\"");
    }
}
