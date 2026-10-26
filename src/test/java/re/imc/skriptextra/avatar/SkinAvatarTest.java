package re.imc.skriptextra.avatar;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.stream.IntStream;

public final class SkinAvatarTest {
    private static int checks;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    public static String texture(String hash) {
        return encode("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}");
    }
    private static String encode(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
    private static String prefix(int rgb) {
        StringBuilder result = new StringBuilder("§x");
        for (char c : String.format("%06x", rgb).toCharArray()) result.append('§').append(c);
        return result.toString();
    }
    private static void rejected(String texture) throws Exception {
        try { SkinAvatar.textureHash(texture); throw new AssertionError("invalid texture accepted"); }
        catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Path.of(System.getProperty("avatar.test.root")), "skin-test-");
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) for (int x = 8; x < 16; x++) image.setRGB(x, y, 0xff204060);
        image.setRGB(40, 8, 0xffff0000);
        image.setRGB(41, 8, 0x0000ff00);
        image.setRGB(42, 8, 0x80ffffff);
        image.setRGB(48, 16, 0xffff00ff); // Detect the old, incorrect overlay coordinates.
        ImageIO.write(image, "png", directory.resolve("abcd_false.png").toFile());
        SkinAvatar service = new SkinAvatar();
        String[] base = service.load(texture("abcd"), false, "█", directory);
        check(base.length == 8, "8 rows");
        check(base[0].equals((prefix(0x204060) + "█").repeat(8)), "correct face rectangle");
        Files.delete(directory.resolve("abcd_false.png"));
        String[] overlay = service.load(texture("abcd"), true, "█", directory);
        check(overlay[0].startsWith(prefix(0xff0000) + "█"), "correct overlay rectangle");
        check(overlay[0].substring(15, 30).equals(prefix(0x204060) + "█"), "transparent overlay preserves face");
        check(overlay[0].substring(30, 45).equals(prefix(0x90a0b0) + "█"), "partial alpha blend");
        String[] custom = service.load(texture("abcd"), false, "◆", directory);
        check(custom[0].endsWith("◆") && !custom[0].contains("█"), "character has own cache key");
        base[0] = "mutated";
        check(service.load(texture("abcd"), false, "█", directory)[0].startsWith("§x"), "cache copy isolation");
        check(IntStream.range(0, 32).parallel().allMatch(i -> {
            try { return service.load(texture("abcd"), true, "█", directory)[0].equals(overlay[0]); }
            catch (IOException e) { throw new RuntimeException(e); }
        }), "concurrent requests");
        image.setRGB(8, 8, 0xffabcdef);
        ImageIO.write(image, "png", directory.resolve("dcba_true.png").toFile());
        check(service.load(texture("dcba"), false, "█", directory)[0].startsWith(prefix(0xabcdef)), "new skin bypasses old cache");
        String[] small = service.load(texture("abcd"), false, "◆", directory, 4);
        check(small.length == 4 && small[0].equals((prefix(0x204060) + "◆").repeat(4)), "4 rows by 4 columns");
        String[] large = service.load(texture("abcd"), true, "●", directory, 16);
        check(large.length == 16 && large[0].length() == 16 * 15, "16 rows by 16 columns");
        check(large[0].startsWith((prefix(0xff0000) + "●").repeat(2)), "nearest neighbour enlarges pixels");
        check(service.load(texture("abcd"), false, "█", directory).length == 8, "size has separate cache key");
        check(service.cached(texture("abcd"), true, "●", 16).length == 16, "synchronous memory cache");
        try { service.load(texture("abcd"), false, "█", directory, 0); throw new AssertionError("zero lines accepted"); }
        catch (IOException expected) { checks++; }
        try { service.load(texture("abcd"), false, "█", directory, 65); throw new AssertionError("too many lines accepted"); }
        catch (IOException expected) { checks++; }
        check(SkinAvatar.textureHash(texture("abcd")).equals("abcd"), "native texture payload");
        rejected("not base64!");
        rejected(encode("not json"));
        rejected(encode("{\"textures\":{}}"));
        rejected(encode("{\"textures\":{\"SKIN\":{\"url\":\"http://localhost/texture/abcd\"}}}"));
        rejected(encode("{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/../bad\"}}}"));
        BufferedImage legacy = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        check(SkinAvatar.render(legacy, true, "█").length == 8, "legacy 64x32 skin");
        try { SkinAvatar.render(new BufferedImage(8, 8, 2), false, "█"); throw new AssertionError("bad dimensions accepted"); }
        catch (IOException expected) { checks++; }
        System.out.println("SkinAvatarTest: " + checks + " checks passed");
    }
}
