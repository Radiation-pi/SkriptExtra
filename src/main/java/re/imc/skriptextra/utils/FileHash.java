package re.imc.skriptextra.utils;

import com.google.common.hash.Hashing;
import java.io.File;
import java.nio.file.Files;
import re.imc.skriptextra.utils.PluginConfig;

public class FileHash {
    public static String sha1(File file) throws Exception {
        long maximumMiB = PluginConfig.longValue("features.functions.file-sha1.maximum-file-size-mib", 0L);
        if (maximumMiB > 0L && file.length() > maximumMiB * 1024L * 1024L) {
            throw new IllegalArgumentException("File exceeds the configured maximum size of " + maximumMiB + " MiB");
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        return Hashing.sha1().hashBytes(bytes).toString();
    }
}
