package re.imc.skriptextra.storage.driver;

import java.sql.DriverManager;
import java.sql.SQLException;

/** Must run inside the driver's loader because DriverManager checks the caller's loader. */
public final class DriverCleanup {
    private DriverCleanup() {}
    public static void deregister() throws SQLException {
        var drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            var driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == DriverCleanup.class.getClassLoader())
                DriverManager.deregisterDriver(driver);
        }
    }
}
