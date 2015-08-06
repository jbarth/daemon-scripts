package scripts.daemon.plugin.maven;

/***
 * Valid application types that the daemon script is capable of runngin
 */
public enum ApplicationType {

    /** A java application, invoke the JVM with some defined Main Method */
    JAVA,

    /** A generic executable */
    EXECUTABLE;

    @Override
    public String toString() {
        return name();
    }

}
