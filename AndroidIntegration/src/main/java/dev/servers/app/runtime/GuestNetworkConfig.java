package dev.servers.app.runtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import java.io.File;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Writes the active Android network's DNS configuration for the Linux guest. */
public final class GuestNetworkConfig {
    private GuestNetworkConfig() {}

    public static File writeResolvConf(Context context, File cacheDirectory) throws IOException {
        if (context == null || cacheDirectory == null) {
            throw new IllegalArgumentException("Context and cache directory are required");
        }
        if (Files.isSymbolicLink(cacheDirectory.toPath()) || !cacheDirectory.isDirectory()) {
            throw new IOException("Runtime cache is not a real directory");
        }

        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        Network network = connectivity == null ? null : connectivity.getActiveNetwork();
        LinkProperties properties = (connectivity == null || network == null)
                ? null : connectivity.getLinkProperties(network);

        // Offline/local-only hosting must still be possible. In that case write
        // a valid options-only resolver file; guest hostname resolution simply
        // remains unavailable until Android has an active network.
        List<InetAddress> dnsServers = properties == null ? null : properties.getDnsServers();
        StringBuilder config = new StringBuilder();
        int written = 0;
        if (dnsServers != null) {
            for (InetAddress address : dnsServers) {
                if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) continue;
                // Link-local IPv6 DNS requires an interface scope. That scope is
                // Android-specific and is not reliably meaningful inside PRoot.
                if (address instanceof Inet6Address && address.isLinkLocalAddress()) continue;
                String host = address.getHostAddress();
                int scope = host.indexOf('%');
                if (scope >= 0) host = host.substring(0, scope);
                if (host.isEmpty() || host.indexOf('\n') >= 0 || host.indexOf(' ') >= 0) continue;
                config.append("nameserver ").append(host).append('\n');
                written++;
                if (written >= 6) break;
            }
        }
        config.append("options timeout:2 attempts:2 ndots:1\n");

        Path destination = cacheDirectory.toPath().resolve("resolv.conf");
        if (Files.isSymbolicLink(destination)) throw new IOException("Guest resolver config must not be a symlink");
        Path temporary = cacheDirectory.toPath().resolve("resolv.conf.tmp");
        try {
            Files.write(temporary, config.toString().getBytes(StandardCharsets.US_ASCII));
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return destination.toFile();
    }
}
