package vibe.liteming.dynamicstage.lod;

import net.minecraft.network.FriendlyByteBuf;
import vibe.liteming.dynamicstage.util.ContentHash;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Server-provided metadata for an immutable client-side LOD archive. */
public record LodPackageOffer(Delivery delivery, String url, long bytes, String sha256,
                              boolean serverHosted) {
    public static final int MAX_URL_LENGTH = 2048;
    public static final long MAX_BYTES = 512L * 1024L * 1024L;

    public LodPackageOffer(Delivery delivery, String url, long bytes, String sha256) {
        this(delivery, url, bytes, sha256, false);
    }

    public LodPackageOffer {
        if (delivery == null || delivery == Delivery.LOCAL) {
            throw new IllegalArgumentException("LOD offer requires optional or required delivery");
        }
        if (url == null || url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("Invalid LOD offer URL");
        }
        if (bytes <= 0L || bytes > MAX_BYTES) {
            throw new IllegalArgumentException("Invalid LOD offer size: " + bytes);
        }
        sha256 = sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT);
        if (!ContentHash.isSha256(sha256)) {
            throw new IllegalArgumentException("LOD offer SHA-256 must be 64 lowercase hex characters");
        }
        if (serverHosted) {
            if (!url.isBlank()) {
                throw new IllegalArgumentException("Server-hosted LOD offers cannot include a URL");
            }
        } else {
            if (url.isBlank()) {
                throw new IllegalArgumentException("Invalid LOD offer URL");
            }
            try {
                URI parsed = new URI(url);
                String scheme = parsed.getScheme();
                if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                        || parsed.getHost() == null || parsed.getUserInfo() != null
                        || parsed.getFragment() != null) {
                    throw new IllegalArgumentException("LOD offer URL must be an HTTP(S) URL without credentials");
                }
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid LOD offer URL", e);
            }
        }
    }

    public boolean required() {
        return delivery == Delivery.REQUIRED;
    }

    public static void encode(LodPackageOffer offer, FriendlyByteBuf buf) {
        buf.writeEnum(offer.delivery());
        buf.writeUtf(offer.url(), MAX_URL_LENGTH);
        buf.writeVarLong(offer.bytes());
        buf.writeUtf(offer.sha256(), 64);
    }

    public static LodPackageOffer decode(FriendlyByteBuf buf) {
        Delivery delivery = buf.readEnum(Delivery.class);
        String url = buf.readUtf(MAX_URL_LENGTH);
        long bytes = buf.readVarLong();
        String sha256 = buf.readUtf(64);
        boolean serverHosted = url.isBlank();
        return new LodPackageOffer(delivery, url, bytes, sha256, serverHosted);
    }

    public enum Delivery {
        OPTIONAL,
        REQUIRED,
        LOCAL
    }
}
