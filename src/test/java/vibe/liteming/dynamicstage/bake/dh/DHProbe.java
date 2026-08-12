package vibe.liteming.dynamicstage.bake.dh;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plain-main probe for the DH read path (no JUnit discovery involved).
 * Usage: gradlew dhProbe -PdhProbe=<path-to-DistantHorizons.sqlite>
 */
public final class DHProbe {

    private DHProbe() {
    }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : System.getProperty("dh.probe");
        if (path == null) {
            System.out.println("usage: -PdhProbe=<sqlite>");
            return;
        }
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + Path.of(path).toAbsolutePath())) {
            for (int level = 6; level <= 8; level++) {
                String sql = "SELECT PosX, PosZ, MinY, Data, Mapping, CompressionMode, DataFormatVersion "
                        + "FROM FullData WHERE DetailLevel = " + (level - 6);
                AtomicLong sections = new AtomicLong();
                AtomicLong columns = new AtomicLong();
                AtomicLong segments = new AtomicLong();
                AtomicLong mappingEntries = new AtomicLong();
                AtomicLong unsupported = new AtomicLong();
                try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        int compressionMode = rs.getInt("CompressionMode");
                        int dataFormatVersion = rs.getInt("DataFormatVersion");
                        if (dataFormatVersion != 1) {
                            unsupported.incrementAndGet();
                            continue;
                        }
                        byte[] data = rs.getBytes("Data");
                        byte[] mapping = rs.getBytes("Mapping");
                        byte[] d = decompress(data, compressionMode);
                        byte[] m = decompress(mapping, compressionMode);
                        sections.incrementAndGet();
                        if (d != null) {
                            parseColumns(d, columns, segments);
                        }
                        if (m != null) {
                            mappingEntries.addAndGet(countMapping(m));
                        }
                    }
                }
                System.out.println("detail=" + level + " (blockWidth " + (1 << level) + ") sections-scan ok"
                        + " sections=" + sections + " columnRuns=" + columns + " segments=" + segments
                        + " mappingEntries=" + mappingEntries + " unsupportedVersions=" + unsupported);
            }
        }
    }

    private static void parseColumns(byte[] raw, AtomicLong columns, AtomicLong segments) {
        if (raw == null || raw.length < 2) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        int offset = 0;
        for (int col = 0; col < 4096 && offset + 2 <= raw.length; col++) {
            int len = buf.getShort(offset) & 0xFFFF;
            offset += 2;
            if (len < 0 || len > 256 || offset + len * 8L > raw.length) {
                break;
            }
            columns.incrementAndGet();
            for (int i = 0; i < len; i++) {
                long meta = buf.getInt(offset) & 0xFFFFFFFFL;
                offset += 8;
                int height = (int) (meta & 0xFFF);
                if (height > 0) {
                    segments.incrementAndGet();
                }
            }
        }
    }

    private static long countMapping(byte[] raw) {
        long count = 0;
        if (raw == null || raw.length < 4) {
            return 0;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            int n = in.readInt();
            for (int i = 0; i < n && i < 65536; i++) {
                int len = in.readUnsignedShort();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                count++;
            }
        } catch (IOException ignored) {
        }
        return count;
    }

    private static byte[] decompress(byte[] compressed, int compressionMode) {
        if (compressed == null) {
            return null;
        }
        if (compressionMode == 0) {
            return compressed;
        }
        try (InputStream in = switch (compressionMode) {
            case 1 -> new net.jpountz.lz4.LZ4FrameInputStream(new ByteArrayInputStream(compressed));
            case 3 -> new org.tukaani.xz.XZInputStream(new ByteArrayInputStream(compressed));
            default -> throw new IOException("unsupported DH compression mode " + compressionMode);
        }; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } catch (EOFException ignored) {
                // Some DH XZ streams omit the end marker; partial output remains usable.
            }
            return out.toByteArray();
        } catch (IOException e) {
            System.err.println("DH decompression failed: " + e.getMessage());
            return null;
        }
    }
}
