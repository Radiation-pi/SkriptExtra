package re.imc.skriptextra.avatar;

import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure image work: callers supply a texture snapshot, never a live player. */
public final class SkinAvatar {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, BufferedImage> images = lru(128);
    private final Map<RenderKey, String[]> rendered = lru(512);
    private final Object[] locks = new Object[16];

    public SkinAvatar() {
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    public String[] load(String texture, boolean overlay, String character, Path directory) throws IOException {
        return load(texture, overlay, character, directory, 8);
    }

    public String[] load(String texture, boolean overlay, String character, Path directory, int lines) throws IOException {
        if (lines < 1 || lines > 64) throw new IOException("头像行数必须在 1–64 之间");
        if (character == null || character.isEmpty()) throw new IOException("头像字符不能为空");
        String hash = textureHash(texture);
        RenderKey key = new RenderKey(hash, overlay, character, lines);
        // Coalesce requests for the same skin, including different render options.
        synchronized (locks[(hash.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            String[] cached = rendered.get(key);
            if (cached != null) return cached.clone();
            BufferedImage image = images.get(hash);
            if (image == null) {
                image = loadImage(hash, directory);
                images.put(hash, image);
            }
            String[] result = render(image, overlay, character, lines);
            rendered.put(key, result);
            return result.clone();
        }
    }

    public String[] cached(String texture, boolean overlay, String character, int lines) throws IOException {
        String[] result = rendered.get(new RenderKey(textureHash(texture), overlay, character, lines));
        return result == null ? null : result.clone();
    }

    public static String textureHash(String texture) throws IOException {
        try {
            String json = new String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8);
            String url = JsonParser.parseString(json).getAsJsonObject()
                    .getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
            URI uri = URI.create(url);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || !"textures.minecraft.net".equals(uri.getHost())
                    || !uri.getPath().matches("/texture/[0-9a-fA-F]{1,128}")) {
                throw new IOException("皮肤纹理 URL 无效");
            }
            return uri.getPath().substring(9);
        } catch (RuntimeException e) {
            throw new IOException("无法解析头颅纹理", e);
        }
    }

    private BufferedImage loadImage(String hash, Path directory) throws IOException {
        // Read existing HeadChat caches as well as the shared format.
        for (String suffix : new String[]{".png", "_false.png", "_true.png"}) {
            Path path = directory.resolve(hash + suffix);
            if (Files.isRegularFile(path)) {
                try {
                    BufferedImage image = ImageIO.read(path.toFile());
                    validate(image);
                    return image;
                } catch (IOException ignored) {
                    // A partial/corrupt cache must not prevent a fresh download.
                }
            }
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://textures.minecraft.net/texture/" + hash))
                .timeout(Duration.ofSeconds(10)).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IOException("皮肤下载 HTTP " + response.statusCode());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
            validate(image);
            Files.createDirectories(directory);
            ImageIO.write(image, "png", directory.resolve(hash + ".png").toFile());
            return image;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("皮肤下载已中断", e);
        }
    }

    private static void validate(BufferedImage image) throws IOException {
        if (image == null || image.getWidth() != 64 || (image.getHeight() != 32 && image.getHeight() != 64)) {
            throw new IOException("皮肤必须为 64×32 或 64×64 PNG");
        }
    }

    static String[] render(BufferedImage image, boolean overlay, String character) throws IOException {
        return render(image, overlay, character, 8);
    }

    static String[] render(BufferedImage image, boolean overlay, String character, int lines) throws IOException {
        validate(image);
        if (lines < 1 || lines > 64) throw new IOException("头像行数必须在 1–64 之间");
        String[] rows = new String[lines];
        for (int y = 0; y < lines; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < lines; x++) {
                int skinX = x * 8 / lines;
                int skinY = y * 8 / lines;
                int pixel = image.getRGB(8 + skinX, 8 + skinY);
                if (overlay) pixel = blend(pixel, image.getRGB(40 + skinX, 8 + skinY));
                // Legacy hex codes work in both Skript chat and text displays.
                String hex = String.format("%06x", pixel & 0xffffff);
                row.append('§').append('x');
                for (int i = 0; i < 6; i++) row.append('§').append(hex.charAt(i));
                row.append(character);
            }
            rows[y] = row.toString();
        }
        return rows;
    }

    private static int blend(int base, int top) {
        int alpha = top >>> 24;
        int rgb = 0;
        for (int shift : new int[]{16, 8, 0}) {
            int value = (((top >>> shift) & 255) * alpha + ((base >>> shift) & 255) * (255 - alpha) + 127) / 255;
            rgb |= value << shift;
        }
        return rgb;
    }

    private static <K, V> Map<K, V> lru(int limit) {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return size() > limit; }
        });
    }

    private record RenderKey(String hash, boolean overlay, String character, int lines) {}
}
