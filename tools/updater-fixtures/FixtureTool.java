import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds updater test fixtures (see make-fixtures.ps1). Not shipped in the app.
 *
 *   java FixtureTool.java strip  <in.apk> <out.apk>   copy entries without any v1 signature files;
 *                                                     the APK Signing Block (v2/v3) is dropped too
 *   java FixtureTool.java tamper <in.apk> <out.apk>   flip one byte inside the stored resources.arsc
 *                                                     without rewriting the zip, so the signing
 *                                                     block and certificates stay readable but the
 *                                                     signature no longer matches the contents
 */
public class FixtureTool {
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: strip|tamper <in> <out>");
            System.exit(2);
        }
        Path in = Path.of(args[1]);
        Path out = Path.of(args[2]);
        switch (args[0]) {
            case "strip" -> strip(in, out);
            case "tamper" -> tamper(in, out);
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }

    private static boolean isV1SignatureFile(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String rest = name.substring("META-INF/".length());
        if (rest.contains("/")) return false;
        return rest.equals("MANIFEST.MF") || rest.endsWith(".SF") || rest.endsWith(".RSA")
                || rest.endsWith(".DSA") || rest.endsWith(".EC");
    }

    private static void strip(Path in, Path out) throws IOException {
        try (ZipFile zip = new ZipFile(in.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry src = entries.nextElement();
                if (isV1SignatureFile(src.getName())) continue;
                byte[] data;
                try (InputStream is = zip.getInputStream(src)) {
                    data = is.readAllBytes();
                }
                ZipEntry dst = new ZipEntry(src.getName());
                if (src.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    dst.setMethod(ZipEntry.STORED);
                    dst.setSize(data.length);
                    dst.setCompressedSize(data.length);
                    dst.setCrc(crc.getValue());
                } else {
                    dst.setMethod(ZipEntry.DEFLATED);
                }
                zos.putNextEntry(dst);
                zos.write(data);
                zos.closeEntry();
            }
        }
    }

    private static void tamper(Path in, Path out) throws IOException {
        byte[] apk = Files.readAllBytes(in);
        byte[] name = "resources.arsc".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + 30 + name.length < apk.length; i++) {
            if (apk[i] != 0x50 || apk[i + 1] != 0x4b || apk[i + 2] != 0x03 || apk[i + 3] != 0x04) continue;
            int method = u16(apk, i + 8);
            long size = u32(apk, i + 18);
            int nameLen = u16(apk, i + 26);
            int extraLen = u16(apk, i + 28);
            if (nameLen != name.length) continue;
            boolean match = true;
            for (int k = 0; k < nameLen; k++) {
                if (apk[i + 30 + k] != name[k]) { match = false; break; }
            }
            if (!match) continue;
            if (method != 0 || size < 1024) throw new IOException("resources.arsc is not stored or too small");
            int target = (int) (i + 30 + nameLen + extraLen + size / 2);
            apk[target] ^= 0x01;
            Files.write(out, apk);
            System.out.println("flipped byte at offset " + target);
            return;
        }
        throw new IOException("resources.arsc local header not found");
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }
}
