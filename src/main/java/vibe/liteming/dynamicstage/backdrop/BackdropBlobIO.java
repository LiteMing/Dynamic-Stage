package vibe.liteming.dynamicstage.backdrop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reader/writer for the Backdrop Blob (.sdb) format v1.
 * <p>
 * Pure-Java module with no Minecraft runtime dependency. Layout:
 * <pre>
 * [8B] magic "SDDBL01\0"
 * [4B] manifest length (int, BE)
 * [n]  manifest JSON (UTF-8)
 * [4B] payload length (int, BE)
 * [m]  deflate-compressed payload
 * </pre>
 * Payload (decompressed):
 * <pre>
 * [4B] column count (int, BE)
 * [..] columns (grouped by ascending lodLevel):
 *        short x, short z, short yStart, short yEnd, varint paletteIndex, byte lodLevel
 * [8B] payload CRC32 (long, BE)
 * </pre>
 */
public final class BackdropBlobIO {

    public static final String MAGIC = "SDDBL01\0";
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_BLOB_BYTES = 50 * 1024 * 1024;
    public static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    public static final int MAX_COLUMNS = 2_000_000;

    private BackdropBlobIO() {
    }

    public static byte[] write(BackdropManifest manifest, List<BackdropColumn> columns) {
        byte[] payload = encodePayload(columns);
        byte[] manifestBytes = manifest.toJson().getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            data.writeBytes(MAGIC);
            data.writeInt(manifestBytes.length);
            data.write(manifestBytes);
            data.writeInt(payload.length);
            data.write(payload);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialise backdrop blob", e);
        }

        byte[] blob = out.toByteArray();
        if (blob.length > MAX_BLOB_BYTES) {
            throw new IllegalArgumentException("Backdrop blob exceeds 50MB cap: " + blob.length + " bytes");
        }
        return blob;
    }

    public static Blob read(byte[] blob) {
        if (blob.length > MAX_BLOB_BYTES) {
            throw new IllegalArgumentException("Backdrop blob exceeds 50MB cap: " + blob.length + " bytes");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob));
        try {
            byte[] magic = new byte[MAGIC.length()];
            in.readFully(magic);
            if (!MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("Not a Backdrop blob (bad magic)");
            }

            int manifestLen = in.readInt();
            if (manifestLen <= 0 || manifestLen > MAX_MANIFEST_BYTES || manifestLen > blob.length) {
                throw new IllegalArgumentException("Corrupt backdrop blob: invalid manifest length " + manifestLen);
            }
            byte[] manifestBytes = new byte[manifestLen];
            in.readFully(manifestBytes);
            BackdropManifest manifest = BackdropManifest.fromJson(new String(manifestBytes, StandardCharsets.UTF_8));

            int payloadLen = in.readInt();
            if (payloadLen <= 0 || payloadLen > blob.length) {
                throw new IllegalArgumentException("Corrupt backdrop blob: invalid payload length " + payloadLen);
            }
            byte[] payload = new byte[payloadLen];
            in.readFully(payload);
            if (in.available() != 0) {
                throw new IllegalArgumentException("Corrupt backdrop blob: trailing bytes");
            }

            List<BackdropColumn> columns = decodePayload(payload);
            return new Blob(manifest, columns);
        } catch (EOFException e) {
            throw new IllegalArgumentException("Truncated backdrop blob", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read backdrop blob", e);
        }
    }

    private static byte[] encodePayload(List<BackdropColumn> columns) {
        if (columns.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("Backdrop column count exceeds " + MAX_COLUMNS);
        }
        List<BackdropColumn> sorted = new ArrayList<>(columns);
        sorted.sort((a, b) -> {
            int byLod = Integer.compare(a.lodLevel(), b.lodLevel());
            if (byLod != 0) {
                return byLod;
            }
            int byX = Integer.compare(a.x(), b.x());
            return byX != 0 ? byX : Integer.compare(a.z(), b.z());
        });

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(raw)) {
            data.writeInt(sorted.size());
            for (BackdropColumn col : sorted) {
                data.writeShort(col.x());
                data.writeShort(col.z());
                data.writeShort(col.yStart());
                data.writeShort(col.yEnd());
                writeVarInt(data, col.paletteIndex());
                data.writeByte(col.lodLevel());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode backdrop payload", e);
        }

        byte[] rawBytes = raw.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(rawBytes);
        ByteArrayOutputStream withCrc = new ByteArrayOutputStream(rawBytes.length + Long.BYTES);
        try (DataOutputStream data = new DataOutputStream(withCrc)) {
            data.write(rawBytes);
            data.writeLong(crc.getValue());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode backdrop payload", e);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out,
                new Deflater(Deflater.BEST_COMPRESSION))) {
            deflater.write(withCrc.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to compress backdrop payload", e);
        }
        return out.toByteArray();
    }

    private static List<BackdropColumn> decodePayload(byte[] payload) {
        ByteArrayOutputStream decompressed = new ByteArrayOutputStream();
        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(payload))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inflater.read(buffer)) != -1) {
                if (decompressed.size() + read > MAX_BLOB_BYTES) {
                    throw new IllegalArgumentException("Corrupt backdrop payload: decompressed size exceeds 50MB cap");
                }
                decompressed.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Corrupt backdrop payload (deflate error)", e);
        }

        byte[] data = decompressed.toByteArray();
        if (data.length > MAX_BLOB_BYTES) {
            throw new IllegalArgumentException("Corrupt backdrop payload: decompressed size " + data.length
                    + " exceeds 50MB cap");
        }
        if (data.length < Long.BYTES) {
            throw new IllegalArgumentException("Truncated backdrop payload");
        }
        long storedCrc = readLong(data, data.length - Long.BYTES);
        long expectedCrc = crc32Of(data, data.length - Long.BYTES);
        if (storedCrc != expectedCrc) {
            throw new IllegalArgumentException("Corrupt backdrop payload: CRC mismatch");
        }

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            int count = in.readInt();
            if (count < 0 || count > MAX_COLUMNS) {
                throw new IllegalArgumentException("Corrupt backdrop payload: implausible column count " + count);
            }
            List<BackdropColumn> columns = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int x = in.readShort();
                int z = in.readShort();
                int yStart = in.readShort();
                int yEnd = in.readShort();
                int paletteIndex = readVarInt(in);
                int lodLevel = in.readUnsignedByte();
                columns.add(new BackdropColumn(x, z, yStart, yEnd, paletteIndex, lodLevel));
            }
            if (in.available() != Long.BYTES) {
                throw new IllegalArgumentException("Corrupt backdrop payload: trailing column data");
            }
            return columns;
        } catch (EOFException e) {
            throw new IllegalArgumentException("Truncated backdrop payload", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to decode backdrop payload", e);
        }
    }

    private static long readLong(byte[] data, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[offset + i] & 0xFFL);
        }
        return v;
    }

    private static long crc32Of(byte[] data, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, length);
        return crc.getValue();
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (shift < 32) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("VarInt too long");
    }

    public record Blob(BackdropManifest manifest, List<BackdropColumn> columns) {
    }
}
