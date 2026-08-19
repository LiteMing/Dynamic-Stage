package vibe.liteming.dynamicstage.client.lod;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Creates compact, immutable stage copies of stock DH 3.2 databases. */
public final class DhPackOptimizer {
    private static final String SQLITE_DRIVER = "dh_sqlite.JDBC";
    private static final String SQLITE_PREFIX = "jdbc:dh_sqlite:";
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "DetailLevel", "PosX", "PosZ", "DataChecksum", "Data",
            "ColumnGenerationStep", "ColumnWorldCompressionMode", "Mapping",
            "NorthAdjData", "SouthAdjData", "EastAdjData", "WestAdjData",
            "DataFormatVersion", "CompressionMode", "ApplyToParent", "ApplyToChildren");

    private DhPackOptimizer() {
    }

    public static Result crop(LodPackRegistry.DhPack source, ResourceLocation outputId,
                              int minY, int maxY) throws IOException {
        return crop(source, outputId, minY, maxY, 0, 0, -1);
    }

    public static Result crop(LodPackRegistry.DhPack source, ResourceLocation outputId,
                              int minY, int maxY, int anchorX, int anchorZ,
                              int horizontalRadius) throws IOException {
        return crop(LodPackRegistry.rootDirectory(), source, outputId,
                minY, maxY, anchorX, anchorZ, horizontalRadius);
    }

    static Result crop(Path packageRoot, LodPackRegistry.DhPack source, ResourceLocation outputId,
                       int minY, int maxY, int anchorX, int anchorZ,
                       int horizontalRadius) throws IOException {
        validateBounds(minY, maxY, horizontalRadius);
        packageRoot = packageRoot.toAbsolutePath().normalize();
        Path outputDirectory = packageRoot.resolve(outputId.getNamespace()).resolve(outputId.getPath())
                .toAbsolutePath().normalize();
        if (!outputDirectory.startsWith(packageRoot)
                || Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Optimized LOD package already exists or is unsafe: " + outputId);
        }

        Path outputDatabase = outputDirectory.resolve("dh").resolve("DistantHorizons.sqlite").normalize();
        if (!outputDatabase.startsWith(outputDirectory)) {
            throw new IOException("Optimized DH database path is unsafe");
        }

        try {
            loadDriver();
            SourceState sourceBefore = SourceState.capture(source.database());
            Files.createDirectories(outputDatabase.getParent());
            snapshot(source.database(), outputDatabase);
            SourceState sourceAfter = SourceState.capture(source.database());
            if (!sourceBefore.equals(sourceAfter)) {
                throw new IOException("Source DH database changed while it was being optimized");
            }
            String sourceSnapshotSha256 = sha256(outputDatabase);

            MutableStats stats = optimize(outputDatabase, minY, maxY, anchorX, anchorZ, horizontalRadius);
            compact(outputDatabase);
            String outputSha256 = sha256(outputDatabase);
            writeManifest(outputDirectory, minY, maxY, anchorX, anchorZ, horizontalRadius,
                    sourceSnapshotSha256, outputSha256);
            LodPackRegistry.Pack loaded = LodPackRegistry.load(packageRoot, outputId);
            if (!(loaded instanceof LodPackRegistry.DhPack pack)) {
                throw new IOException("Optimized package is not Distant Horizons");
            }
            return stats.finish(pack, Files.size(outputDatabase), sourceSnapshotSha256, outputSha256);
        } catch (Throwable error) {
            deleteOutput(packageRoot, outputDirectory);
            if (error instanceof IOException io) {
                throw io;
            }
            throw new IOException("Could not optimize DH LOD package", rootCause(error));
        }
    }

    private static MutableStats optimize(Path database, int minY, int maxY,
                                         int anchorX, int anchorZ, int radius) throws Exception {
        try (Connection connection = open(database)) {
            configureForRewrite(connection);
            validateSchema(connection);
            List<Long> rowIds = rowIds(connection);
            MutableStats stats = new MutableStats(rowIds.size(), Files.size(database));
            DhCodec codec = new DhCodec();
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT rowid, DetailLevel, PosX, PosZ, DataChecksum, Data,
                           ColumnGenerationStep, ColumnWorldCompressionMode, Mapping,
                           NorthAdjData, SouthAdjData, EastAdjData, WestAdjData,
                           DataFormatVersion, CompressionMode
                    FROM FullData WHERE rowid = ?
                    """);
                 PreparedStatement update = connection.prepareStatement("""
                    UPDATE FullData
                    SET DataChecksum = ?, Data = ?, NorthAdjData = ?, SouthAdjData = ?,
                        EastAdjData = ?, WestAdjData = ?, ApplyToParent = 0, ApplyToChildren = 0
                    WHERE rowid = ?
                    """);
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM FullData WHERE rowid = ?")) {
                for (long rowId : rowIds) {
                    Row row = readRow(select, rowId);
                    stats.sourceBlobBytes += row.blobBytes();
                    if (!rowIntersectsRadius(row.detailLevel, row.posX, row.posZ,
                            anchorX, anchorZ, radius)) {
                        deleteRow(delete, rowId);
                        stats.removedRows++;
                        stats.radiusRemovedRows++;
                        continue;
                    }
                    CroppedRow cropped = codec.crop(row, minY, maxY, anchorX, anchorZ, radius);
                    stats.sourceSegments += cropped.sourceSegments;
                    stats.removedSegments += cropped.removedSegments;
                    stats.trimmedSegments += cropped.trimmedSegments;
                    stats.radiusRemovedSegments += cropped.radiusRemovedSegments;
                    if (cropped.retainedSegments == 0L) {
                        deleteRow(delete, rowId);
                        stats.removedRows++;
                        continue;
                    }
                    updateRow(update, rowId, cropped);
                    stats.outputRows++;
                    stats.retainedSegments += cropped.retainedSegments;
                }
            } catch (Throwable error) {
                connection.rollback();
                throw error;
            }
            if (stats.outputRows == 0L) {
                connection.rollback();
                throw new IOException("DH crop does not contain any FullData rows");
            }
            pruneAuxiliaryData(connection, minY, maxY, anchorX, anchorZ, radius);
            connection.commit();
            return stats;
        }
    }

    private static Row readRow(PreparedStatement statement, long rowId) throws SQLException, IOException {
        statement.setLong(1, rowId);
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IOException("DH database row disappeared during optimization: " + rowId);
            }
            return new Row(result.getLong("rowid"), result.getInt("DetailLevel"),
                    result.getInt("PosX"), result.getInt("PosZ"),
                    result.getInt("DataChecksum"), requiredBytes(result, "Data"),
                    requiredBytes(result, "ColumnGenerationStep"),
                    requiredBytes(result, "ColumnWorldCompressionMode"),
                    requiredBytes(result, "Mapping"), nullableBytes(result, "NorthAdjData"),
                    nullableBytes(result, "SouthAdjData"), nullableBytes(result, "EastAdjData"),
                    nullableBytes(result, "WestAdjData"), result.getByte("DataFormatVersion"),
                    result.getByte("CompressionMode"));
        }
    }

    private static byte[] requiredBytes(ResultSet result, String column) throws SQLException, IOException {
        byte[] bytes = result.getBytes(column);
        if (bytes == null) {
            throw new IOException("DH FullData row has no " + column + " BLOB");
        }
        return bytes;
    }

    private static byte[] nullableBytes(ResultSet result, String column) throws SQLException {
        return result.getBytes(column);
    }

    private static void updateRow(PreparedStatement statement, long rowId, CroppedRow row) throws SQLException {
        statement.setInt(1, row.checksum);
        statement.setBytes(2, row.data);
        setNullableBytes(statement, 3, row.north);
        setNullableBytes(statement, 4, row.south);
        setNullableBytes(statement, 5, row.east);
        setNullableBytes(statement, 6, row.west);
        statement.setLong(7, rowId);
        statement.executeUpdate();
    }

    private static void setNullableBytes(PreparedStatement statement, int index, byte[] bytes) throws SQLException {
        if (bytes == null) {
            statement.setNull(index, java.sql.Types.BLOB);
        } else {
            statement.setBytes(index, bytes);
        }
    }

    private static void deleteRow(PreparedStatement statement, long rowId) throws SQLException {
        statement.setLong(1, rowId);
        statement.executeUpdate();
    }

    private static List<Long> rowIds(Connection connection) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT rowid FROM FullData ORDER BY rowid")) {
            while (result.next()) {
                ids.add(result.getLong(1));
            }
        }
        return ids;
    }

    private static void validateSchema(Connection connection) throws SQLException, IOException {
        java.util.HashSet<String> columns = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(FullData)")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        if (!columns.containsAll(REQUIRED_COLUMNS)) {
            java.util.HashSet<String> missing = new java.util.HashSet<>(REQUIRED_COLUMNS);
            missing.removeAll(columns);
            throw new IOException("Unsupported DH FullData schema; missing " + missing);
        }
    }

    private static void pruneAuxiliaryData(Connection connection, int minY, int maxY,
                                           int anchorX, int anchorZ, int radius) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS Legacy_FullData_V1");
            if (tableExists(connection, "ChunkHash")) {
                statement.executeUpdate("DELETE FROM ChunkHash");
            }
            if (tableExists(connection, "BeaconBeam")) {
                try (PreparedStatement delete = connection.prepareStatement(radius == -1
                        ? "DELETE FROM BeaconBeam WHERE BlockPosY < ? OR BlockPosY > ?"
                        : "DELETE FROM BeaconBeam WHERE BlockPosY < ? OR BlockPosY > ? OR "
                        + "((CAST(BlockPosX AS BIGINT) - ?) * (CAST(BlockPosX AS BIGINT) - ?) + "
                        + "(CAST(BlockPosZ AS BIGINT) - ?) * (CAST(BlockPosZ AS BIGINT) - ?)) > ?")) {
                    delete.setInt(1, minY);
                    delete.setInt(2, maxY);
                    if (radius != -1) {
                        delete.setInt(3, anchorX);
                        delete.setInt(4, anchorX);
                        delete.setInt(5, anchorZ);
                        delete.setInt(6, anchorZ);
                        delete.setLong(7, (long) radius * radius);
                    }
                    delete.executeUpdate();
                }
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void snapshot(Path source, Path destination) throws SQLException, IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("DH snapshot destination already exists: " + destination);
        }
        try (Connection connection = open(source); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=10000");
            statement.execute("VACUUM INTO '" + sqlitePath(destination) + "'");
        }
    }

    /**
     * Creates the writable DH database used by the renderer for an immutable source package.
     *
     * <p>DH's public read-only flag stops vanilla update hooks, but its 3.2 propagation
     * worker still persists rows whose propagation bits are set.  A stage therefore
     * needs a private database snapshot with those bits cleared; the distributed source
     * must never be opened as DH's active level.</p>
     */
    static void snapshotReadOnly(Path source, Path destination) throws IOException {
        try {
            loadDriver();
            Files.createDirectories(destination.toAbsolutePath().normalize().getParent());
            snapshot(source, destination);
            try (Connection connection = open(destination)) {
                configureForRewrite(connection);
                validateSchema(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE FullData SET ApplyToParent = 0, ApplyToChildren = 0 "
                            + "WHERE ApplyToParent <> 0 OR ApplyToChildren <> 0");
                }
            }
            compact(destination);
            Files.deleteIfExists(destination.resolveSibling(destination.getFileName() + "-wal"));
            Files.deleteIfExists(destination.resolveSibling(destination.getFileName() + "-shm"));
            Files.deleteIfExists(destination.resolveSibling(destination.getFileName() + "-journal"));
        } catch (Throwable error) {
            if (error instanceof IOException io) {
                throw io;
            }
            throw new IOException("Could not create a writable DH runtime snapshot", rootCause(error));
        }
    }

    private static void compact(Path database) throws SQLException {
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("VACUUM");
            statement.execute("PRAGMA optimize");
        }
    }

    private static void configureForRewrite(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=10000");
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA synchronous=OFF");
            statement.execute("PRAGMA temp_store=MEMORY");
        }
    }

    private static Connection open(Path database) throws SQLException {
        return DriverManager.getConnection(SQLITE_PREFIX + database.toAbsolutePath().normalize());
    }

    private static void loadDriver() throws IOException {
        try {
            Class.forName(SQLITE_DRIVER, true, DhPackOptimizer.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError error) {
            throw new IOException("Distant Horizons 3.2 is required to optimize DH packages", error);
        }
    }

    private static String sqlitePath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/').replace("'", "''");
    }

    private static void validateBounds(int minY, int maxY, int radius) throws IOException {
        if (minY > maxY) {
            throw new IOException("Minimum Y must not exceed maximum Y");
        }
        if (radius == 0 || radius < -1) {
            throw new IOException("Horizontal radius must be positive or -1 for unlimited");
        }
    }

    static boolean rowIntersectsRadius(int detailLevel, int posX, int posZ,
                                       int anchorX, int anchorZ, int radius) throws IOException {
        if (radius == -1) {
            return true;
        }
        validateDetailLevel(detailLevel);
        int shift = 6 + detailLevel;
        long minX = (long) posX << shift;
        long minZ = (long) posZ << shift;
        long width = 1L << shift;
        return squareIntersectsCircle(minX, minZ, width, anchorX, anchorZ, radius);
    }

    static boolean columnIntersectsRadius(int detailLevel, int posX, int posZ, int relX, int relZ,
                                          int anchorX, int anchorZ, int radius) throws IOException {
        if (radius == -1) {
            return true;
        }
        validateDetailLevel(detailLevel);
        long rowMinX = (long) posX << (6 + detailLevel);
        long rowMinZ = (long) posZ << (6 + detailLevel);
        long width = 1L << detailLevel;
        long minX = rowMinX + ((long) relX << detailLevel);
        long minZ = rowMinZ + ((long) relZ << detailLevel);
        return squareIntersectsCircle(minX, minZ, width, anchorX, anchorZ, radius);
    }

    private static boolean squareIntersectsCircle(long minX, long minZ, long width,
                                                   int anchorX, int anchorZ, int radius) {
        long maxX = minX + width - 1L;
        long maxZ = minZ + width - 1L;
        long nearestX = Math.max(minX, Math.min(maxX, anchorX));
        long nearestZ = Math.max(minZ, Math.min(maxZ, anchorZ));
        long deltaX = nearestX - anchorX;
        long deltaZ = nearestZ - anchorZ;
        return deltaX * deltaX + deltaZ * deltaZ <= (long) radius * radius;
    }

    private static void validateDetailLevel(int detailLevel) throws IOException {
        if (detailLevel < 0 || detailLevel > 24) {
            throw new IOException("Unsupported DH detail level: " + detailLevel);
        }
    }

    private static void writeManifest(Path outputDirectory, int minY, int maxY,
                                      int anchorX, int anchorZ, int radius,
                                      String sourceSnapshotSha256, String outputSha256) throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("formatVersion", LodPackRegistry.FORMAT_VERSION);
        manifest.addProperty("backend", "distanthorizons");
        manifest.addProperty("minecraftVersion", "1.20.1");
        manifest.addProperty("distantHorizonsVersion", LodPackRegistry.DH_VERSION);
        manifest.addProperty("minY", LodPackRegistry.STAGE_MIN_Y);
        manifest.addProperty("height", LodPackRegistry.STAGE_HEIGHT);
        JsonObject optimization = new JsonObject();
        optimization.addProperty("version", 1);
        optimization.addProperty("policy", radius == -1 ? "y-range" : "radius-y-range");
        optimization.addProperty("cropMinY", minY);
        optimization.addProperty("cropMaxY", maxY);
        if (radius != -1) {
            optimization.addProperty("anchorX", anchorX);
            optimization.addProperty("anchorZ", anchorZ);
            optimization.addProperty("radius", radius);
        }
        optimization.addProperty("sourceSnapshotSha256", sourceSnapshotSha256);
        optimization.addProperty("databaseSha256", outputSha256);
        manifest.add("optimization", optimization);
        Files.writeString(outputDirectory.resolve("manifest.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(manifest) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static void deleteOutput(Path root, Path output) {
        if (!output.startsWith(root) || !Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(output, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record SourceState(FileState database, FileState wal, FileState shm) {
        private static SourceState capture(Path database) throws IOException {
            return new SourceState(FileState.capture(database),
                    FileState.capture(database.resolveSibling(database.getFileName() + "-wal")),
                    FileState.capture(database.resolveSibling(database.getFileName() + "-shm")));
        }
    }

    private record FileState(boolean exists, long size, long modifiedMillis) {
        private static FileState capture(Path file) throws IOException {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return new FileState(false, 0L, 0L);
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("DH SQLite state path is not a regular file: " + file);
            }
            return new FileState(true, attributes.size(), attributes.lastModifiedTime().toMillis());
        }
    }

    private record Row(long rowId, int detailLevel, int posX, int posZ, int checksum,
                       byte[] data, byte[] generation, byte[] worldCompression, byte[] mapping,
                       byte[] north, byte[] south, byte[] east, byte[] west,
                       byte dataFormat, byte compressionMode) {
        private long blobBytes() {
            return length(data) + length(generation) + length(worldCompression) + length(mapping)
                    + length(north) + length(south) + length(east) + length(west);
        }

        private static long length(byte[] bytes) {
            return bytes == null ? 0L : bytes.length;
        }
    }

    private record CroppedRow(int checksum, byte[] data, byte[] north, byte[] south,
                              byte[] east, byte[] west, long sourceSegments,
                              long retainedSegments, long removedSegments,
                              long trimmedSegments, long radiusRemovedSegments) {
    }

    private static final class MutableStats {
        private final long sourceRows;
        private final long sourceBytes;
        private long outputRows;
        private long removedRows;
        private long radiusRemovedRows;
        private long sourceBlobBytes;
        private long sourceSegments;
        private long retainedSegments;
        private long removedSegments;
        private long trimmedSegments;
        private long radiusRemovedSegments;

        private MutableStats(long sourceRows, long sourceBytes) {
            this.sourceRows = sourceRows;
            this.sourceBytes = sourceBytes;
        }

        private Result finish(LodPackRegistry.DhPack pack, long outputBytes,
                              String sourceSha256, String outputSha256) {
            return new Result(pack, sourceRows, outputRows, removedRows, radiusRemovedRows,
                    sourceBlobBytes, sourceSegments, retainedSegments, removedSegments,
                    trimmedSegments, radiusRemovedSegments, sourceBytes, outputBytes,
                    sourceSha256, outputSha256);
        }
    }

    /** Reflection bridge keeps stock DH optional and delegates its BLOB format to DH itself. */
    private static final class DhCodec {
        private final Class<?> dtoClass;
        private final Class<?> byteListClass;
        private final Class<?> directionClass;
        private final Method createDto;
        private final Method createDataSource;
        private final Method encodeV1;
        private final Method encodeV2;
        private final Method compressionFromValue;
        private final Method sectionEncode;
        private final Method listSize;
        private final Method listGetLong;
        private final Method listClear;
        private final Method listAdd;
        private final Method byteListClear;
        private final Method byteListAddElements;
        private final Method byteListToArray;
        private final Method getBottomY;
        private final Method getHeight;
        private final Method setBottomY;
        private final Method setHeight;
        private final int width;

        private DhCodec() throws ReflectiveOperationException {
            ClassLoader loader = DhPackOptimizer.class.getClassLoader();
            dtoClass = Class.forName("com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO", true, loader);
            Class<?> sourceClass = Class.forName(
                    "com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2", true, loader);
            byteListClass = Class.forName("it.unimi.dsi.fastutil.bytes.ByteArrayList", true, loader);
            Class<?> longListClass = Class.forName("it.unimi.dsi.fastutil.longs.LongArrayList", true, loader);
            directionClass = Class.forName("com.seibel.distanthorizons.core.enums.EDhDirection", true, loader);
            Class<?> compressionClass = Class.forName(
                    "com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode", true, loader);
            Class<?> sectionClass = Class.forName("com.seibel.distanthorizons.core.pos.DhSectionPos", true, loader);
            Class<?> pointUtil = Class.forName("com.seibel.distanthorizons.core.util.FullDataPointUtil", true, loader);
            createDto = dtoClass.getMethod("CreateEmptyDataSourceForDecoding");
            createDataSource = dtoClass.getMethod("createUnitTestDataSource");
            Class<?> dataArrayClass = Array.newInstance(longListClass, 0).getClass();
            encodeV1 = dtoClass.getMethod("writeDataSourceDataArrayToBlobV1",
                    dataArrayClass, byteListClass, compressionClass);
            encodeV2 = dtoClass.getDeclaredMethod("writeDataSourceDataArrayToBlobV2",
                    dataArrayClass, byteListClass, directionClass, compressionClass);
            encodeV2.setAccessible(true);
            compressionFromValue = compressionClass.getMethod("getFromValue", byte.class);
            sectionEncode = sectionClass.getMethod("encode", byte.class, int.class, int.class);
            listSize = longListClass.getMethod("size");
            listGetLong = longListClass.getMethod("getLong", int.class);
            listClear = longListClass.getMethod("clear");
            listAdd = longListClass.getMethod("add", long.class);
            byteListClear = byteListClass.getMethod("clear");
            byteListAddElements = byteListClass.getMethod("addElements", int.class, byte[].class);
            byteListToArray = byteListClass.getMethod("toByteArray");
            getBottomY = pointUtil.getMethod("getBottomY", long.class);
            getHeight = pointUtil.getMethod("getHeight", long.class);
            setBottomY = pointUtil.getMethod("setBottomY", long.class, int.class);
            setHeight = pointUtil.getMethod("setHeight", long.class, int.class);
            width = sourceClass.getField("WIDTH").getInt(null);
            if (width != 64) {
                throw new IllegalStateException("Unsupported DH FullData width: " + width);
            }
        }

        private CroppedRow crop(Row row, int minY, int maxY,
                                int anchorX, int anchorZ, int radius) throws Exception {
            validateDetailLevel(row.detailLevel);
            Object dto = createDto.invoke(null);
            Object source = null;
            try {
                set(dto, "pos", sectionEncode.invoke(null,
                        (byte) (row.detailLevel + 6), row.posX, row.posZ));
                set(dto, "dataFormatVersion", row.dataFormat);
                set(dto, "compressionModeValue", row.compressionMode);
                fill(dto, "compressedDataByteArray", row.data);
                fill(dto, "compressedColumnGenStepByteArray", row.generation);
                fill(dto, "compressedWorldCompressionModeByteArray", row.worldCompression);
                fill(dto, "compressedMappingByteArray", row.mapping);
                if (row.dataFormat == 2) {
                    fillRequiredAdjacent(dto, "compressedNorthAdjDataByteArray", row.north);
                    fillRequiredAdjacent(dto, "compressedSouthAdjDataByteArray", row.south);
                    fillRequiredAdjacent(dto, "compressedEastAdjDataByteArray", row.east);
                    fillRequiredAdjacent(dto, "compressedWestAdjDataByteArray", row.west);
                }
                source = invoke(createDataSource, dto);
                Object[] columns = (Object[]) source.getClass().getField("dataPoints").get(source);
                CropCounts counts = cropColumns(columns, row, minY, maxY, anchorX, anchorZ, radius);
                Object compression = compressionFromValue.invoke(null, row.compressionMode);
                byte[] data;
                byte[] north = null;
                byte[] south = null;
                byte[] east = null;
                byte[] west = null;
                if (row.dataFormat == 1) {
                    data = encode(dto, "compressedDataByteArray", encodeV1, columns, compression);
                } else if (row.dataFormat == 2) {
                    data = encode(dto, "compressedDataByteArray", encodeV2, columns, null, compression);
                    north = encode(dto, "compressedNorthAdjDataByteArray", encodeV2,
                            columns, direction("NORTH"), compression);
                    south = encode(dto, "compressedSouthAdjDataByteArray", encodeV2,
                            columns, direction("SOUTH"), compression);
                    east = encode(dto, "compressedEastAdjDataByteArray", encodeV2,
                            columns, direction("EAST"), compression);
                    west = encode(dto, "compressedWestAdjDataByteArray", encodeV2,
                            columns, direction("WEST"), compression);
                } else {
                    throw new IOException("Unsupported DH FullData format: " + row.dataFormat);
                }
                int checksum = source.hashCode();
                return new CroppedRow(checksum, data, north, south, east, west,
                        counts.source, counts.retained, counts.removed,
                        counts.trimmed, counts.radiusRemoved);
            } finally {
                close(source);
                close(dto);
            }
        }

        private CropCounts cropColumns(Object[] columns, Row row, int minY, int maxY,
                                       int anchorX, int anchorZ, int radius) throws Exception {
            long source = 0L;
            long retained = 0L;
            long removed = 0L;
            long trimmed = 0L;
            long radiusRemoved = 0L;
            long minRelativeY = (long) minY - LodPackRegistry.STAGE_MIN_Y;
            long maxRelativeYExclusive = (long) maxY - LodPackRegistry.STAGE_MIN_Y + 1L;
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    Object column = columns[x * width + z];
                    int size = ((Number) listSize.invoke(column)).intValue();
                    source += size;
                    if (!columnIntersectsRadius(row.detailLevel, row.posX, row.posZ, x, z,
                            anchorX, anchorZ, radius)) {
                        listClear.invoke(column);
                        removed += size;
                        radiusRemoved += size;
                        continue;
                    }
                    long[] values = new long[size];
                    int kept = 0;
                    for (int index = 0; index < size; index++) {
                        long point = ((Number) listGetLong.invoke(column, index)).longValue();
                        int bottom = ((Number) getBottomY.invoke(null, point)).intValue();
                        int height = ((Number) getHeight.invoke(null, point)).intValue();
                        long clippedBottom = Math.max(bottom, minRelativeY);
                        long clippedTop = Math.min((long) bottom + height, maxRelativeYExclusive);
                        if (height <= 0 || clippedBottom >= clippedTop) {
                            removed++;
                            continue;
                        }
                        long clipped = point;
                        if (clippedBottom != bottom || clippedTop != (long) bottom + height) {
                            clipped = ((Number) setBottomY.invoke(null, clipped, (int) clippedBottom)).longValue();
                            clipped = ((Number) setHeight.invoke(null, clipped,
                                    (int) (clippedTop - clippedBottom))).longValue();
                            trimmed++;
                        }
                        values[kept++] = clipped;
                    }
                    listClear.invoke(column);
                    for (int index = 0; index < kept; index++) {
                        listAdd.invoke(column, values[index]);
                    }
                    retained += kept;
                }
            }
            return new CropCounts(source, retained, removed, trimmed, radiusRemoved);
        }

        private byte[] encode(Object dto, String fieldName, Method method,
                              Object columns, Object... extra) throws Exception {
            Object output = dtoClass.getField(fieldName).get(dto);
            byteListClear.invoke(output);
            Object[] arguments = new Object[2 + extra.length];
            arguments[0] = columns;
            arguments[1] = output;
            System.arraycopy(extra, 0, arguments, 2, extra.length);
            invoke(method, null, arguments);
            return (byte[]) byteListToArray.invoke(output);
        }

        private void fillRequiredAdjacent(Object dto, String fieldName, byte[] bytes) throws Exception {
            if (bytes == null) {
                throw new IOException("DH FullData v2 row has no " + fieldName + " BLOB");
            }
            fill(dto, fieldName, bytes);
        }

        private void fill(Object dto, String fieldName, byte[] bytes) throws Exception {
            Object list = dtoClass.getField(fieldName).get(dto);
            byteListClear.invoke(list);
            byteListAddElements.invoke(list, 0, bytes);
        }

        private void set(Object dto, String fieldName, Object value) throws ReflectiveOperationException {
            Field field = dtoClass.getField(fieldName);
            field.set(dto, value);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private Object direction(String name) {
            return Enum.valueOf((Class) directionClass, name);
        }

        private static Object invoke(Method method, Object target, Object... arguments) throws Exception {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error fatal) {
                    throw fatal;
                }
                throw error;
            }
        }

        private static void close(Object value) throws Exception {
            if (value != null) {
                value.getClass().getMethod("close").invoke(value);
            }
        }
    }

    private record CropCounts(long source, long retained, long removed,
                              long trimmed, long radiusRemoved) {
    }

    public record Result(LodPackRegistry.DhPack pack, long sourceRows, long outputRows,
                         long removedRows, long radiusRemovedRows, long sourceBlobBytes,
                         long sourceSegments, long retainedSegments, long removedSegments,
                         long trimmedSegments, long radiusRemovedSegments,
                         long sourceBytes, long outputBytes,
                         String sourceSha256, String outputSha256) {
    }
}
