package vibe.liteming.dynamicstage.bake;

import java.util.stream.Stream;

/**
 * LoD provider SPI: source-agnostic backdrop bake input.
 * <p>
 * Providers read their data format offline (region files, sqlite, voxy db) and
 * emit a uniform stream of {@link LodColumn}s. Source differences are isolated
 * behind this interface; the bake pipeline only sees the uniform output.
 */
public interface LodProvider {

    /**
     * Provider id: "anvil" | "voxy_file" ...
     */
    String id();

    /**
     * True when this provider has usable data for the given source world.
     */
    boolean isAvailable(ServerLevelRef sourceWorld);

    /**
     * Reads the columns overlapping the given region spec.
     * Implementations must not load or tick real chunks; they read files directly.
     */
    Stream<LodColumn> readRegion(RegionSpec spec);

    /**
     * Provider storage format version, pinned into the manifest as
     * {@code providerFormatVersion} so schema changes are detectable.
     */
    int formatVersion();
}
