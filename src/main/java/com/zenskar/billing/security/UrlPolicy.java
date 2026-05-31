package com.zenskar.billing.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates that an outbound delivery target is a safe URL — the primary
 * defense against SSRF for a service whose whole job is to POST to
 * caller-supplied URLs.
 * <p>
 * A webhook dispatcher will happily fire requests at whatever URL a customer
 * registers. Without this guard, a caller can register
 * {@code http://169.254.169.254/latest/meta-data/} (cloud credentials) or an
 * internal address ({@code http://localhost:8080/actuator},
 * {@code http://10.0.0.5/…}) and turn the dispatcher into an SSRF proxy.
 * <p>
 * Two checks, both load-bearing:
 * <ol>
 *   <li><b>Scheme allow-list</b> — only {@code http}/{@code https} (configurable).
 *       This blocks {@code file://}, {@code gopher://}, etc.</li>
 *   <li><b>Address class</b> — when {@code blockPrivateAddresses} is on, the host
 *       is resolved and every resolved address is rejected if it is loopback,
 *       any-local, link-local (covers the {@code 169.254.169.254} metadata
 *       endpoint), site-local (RFC-1918), an IPv6 unique-local address
 *       ({@code fc00::/7}), or multicast.</li>
 * </ol>
 * Validation runs both at registration (reject early) and at delivery time
 * (DNS can rebind between the two — the registration-time resolution to a
 * public address does not bind the delivery-time resolution).
 * <p>
 * Pure — no Spring imports. Wired as a bean in {@code BillingConfig} from
 * {@code billing.security.*}.
 */
public final class UrlPolicy {

    /** Thrown when a URL is not a permitted delivery target. Maps to HTTP 400. */
    public static final class BlockedUrlException extends IllegalArgumentException {
        public BlockedUrlException(String message) {
            super(message);
        }
    }

    private final Set<String> allowedSchemes;
    private final boolean blockPrivateAddresses;

    public UrlPolicy(Collection<String> allowedSchemes, boolean blockPrivateAddresses) {
        this.allowedSchemes = allowedSchemes.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.blockPrivateAddresses = blockPrivateAddresses;
    }

    /**
     * Validate {@code url} and return it parsed. Throws {@link BlockedUrlException}
     * (a subtype of {@link IllegalArgumentException}, so the global handler maps it
     * to 400) if the URL is malformed or points at a disallowed target.
     */
    public URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw new BlockedUrlException("url required");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new BlockedUrlException("malformed url: " + url);
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new BlockedUrlException("url must be absolute and include a scheme: " + url);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!allowedSchemes.contains(scheme)) {
            throw new BlockedUrlException("url scheme not allowed: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BlockedUrlException("url has no host: " + url);
        }
        if (blockPrivateAddresses) {
            InetAddress[] resolved;
            try {
                resolved = InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new BlockedUrlException("url host does not resolve: " + host);
            }
            for (InetAddress addr : resolved) {
                if (isDisallowed(addr)) {
                    throw new BlockedUrlException(
                            "url resolves to a disallowed address: " + host + " -> " + addr.getHostAddress());
                }
            }
        }
        return uri;
    }

    private static boolean isDisallowed(InetAddress addr) {
        return addr.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || addr.isAnyLocalAddress()  // 0.0.0.0, ::
                || addr.isLinkLocalAddress() // 169.254.0.0/16 (incl. metadata), fe80::/10
                || addr.isSiteLocalAddress() // 10/8, 172.16/12, 192.168/16
                || addr.isMulticastAddress()
                || isUniqueLocalIpv6(addr);  // fc00::/7
    }

    private static boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
