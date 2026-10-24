package re.imc.skriptextra.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

public record JdbcOptions(String host, int port, String database, String table,
                          String user, String password, String sslMode,
                          int batchSize, int flushMillis, int retryMillis,
                          int connectTimeoutMillis, int socketTimeoutMillis,
                          int shutdownTimeoutMillis, long maxJournalBytes) {
    public JdbcOptions {
        if (host == null || !host.matches("[a-zA-Z0-9_.:\\-]+") || port < 1 || port > 65535
                || database == null || !database.matches("[a-zA-Z0-9_]+")
                || table == null || !table.matches("[a-zA-Z0-9_]{1,64}")
                || user == null || password == null)
            throw new IllegalArgumentException("Invalid JDBC endpoint, database/table identifier, or credentials");
        if (!java.util.Set.of("disable", "trust", "verify-ca", "verify-full").contains(sslMode))
            throw new IllegalArgumentException("Invalid JDBC ssl-mode");
        if (batchSize < 1 || batchSize > 4096 || flushMillis < 1 || flushMillis > 1000
                || retryMillis < 10 || retryMillis > 30000 || connectTimeoutMillis < 100
                || socketTimeoutMillis < 100 || connectTimeoutMillis > 30000 || socketTimeoutMillis > 30000
                || shutdownTimeoutMillis < socketTimeoutMillis + connectTimeoutMillis + 1000
                || maxJournalBytes < 1024 * 1024)
            throw new IllegalArgumentException("Invalid JDBC batch, timeout, or journal limits");
    }

    public String url() {
        String address = host.contains(":") ? "[" + host + "]" : host;
        return "jdbc:mariadb://" + address + ":" + port + "/" + database;
    }

    public Properties properties() {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password);
        p.setProperty("connectTimeout", Integer.toString(connectTimeoutMillis));
        p.setProperty("socketTimeout", Integer.toString(socketTimeoutMillis));
        p.setProperty("sslMode", sslMode);
        p.setProperty("useBulkStmts", "true");
        return p;
    }

    public String identity() {
        return host + ":" + port + "/" + database + "/" + table;
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    // Avoid the record's generated toString, which would print the password.
    @Override public String toString() { return "JdbcOptions[" + identity() + "]"; }
}
