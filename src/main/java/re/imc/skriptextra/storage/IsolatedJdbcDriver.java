package re.imc.skriptextra.storage;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

/** Paper closes the addon's loader before Skript closes its stores. Keep the driver alive independently. */
final class IsolatedJdbcDriver implements AutoCloseable {
    private final URLClassLoader loader;
    private final Driver driver;

    IsolatedJdbcDriver() throws SQLException {
        URLClassLoader opened = null;
        try {
            URL driverJar = Class.forName("org.mariadb.jdbc.Driver", false, getClass().getClassLoader())
                    .getProtectionDomain().getCodeSource().getLocation();
            URL helperJar = getClass().getProtectionDomain().getCodeSource().getLocation();
            opened = new URLClassLoader(new URL[]{driverJar, helperJar}, ClassLoader.getPlatformClassLoader());
            driver = (Driver)Class.forName("org.mariadb.jdbc.Driver", true, opened).getConstructor().newInstance();
            // The driver registers itself statically. We use direct connect and remove that global registration.
            Class.forName("re.imc.skriptextra.storage.driver.DriverCleanup", true, opened)
                    .getMethod("deregister").invoke(null);
            loader = opened;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (opened != null) try { opened.close(); } catch (IOException ignored) { }
            throw new SQLException("Cannot initialize isolated JDBC driver", "42000", e);
        }
    }

    Connection connect(String url, Properties properties) throws SQLException { return driver.connect(url, properties); }
    @Override public void close() throws IOException { loader.close(); }
}
