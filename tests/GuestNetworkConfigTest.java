package dev.servers.app.runtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class GuestNetworkConfigTest {
    private static final class TestContext extends Context {
        private final ConnectivityManager connectivity;
        TestContext(ConnectivityManager connectivity) { this.connectivity = connectivity; }
        @Override public File getDataDir() { return new File(System.getProperty("java.io.tmpdir")); }
        @Override public <T> T getSystemService(Class<T> type) {
            return type.cast(connectivity);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("serverbox-dns-test");
        try {
            File cache = Files.createDirectory(temp.resolve("cache")).toFile();
            InetAddress ipv4 = InetAddress.getByName("1.1.1.1");
            InetAddress ipv6 = InetAddress.getByName("2001:4860:4860::8888");
            InetAddress linkLocal = InetAddress.getByName("fe80::1");
            ConnectivityManager manager = new ConnectivityManager(new Network(),
                    new LinkProperties(Arrays.asList(ipv4, linkLocal, ipv6)));
            File resolver = GuestNetworkConfig.writeResolvConf(new TestContext(manager), cache);
            String contents = new String(Files.readAllBytes(resolver.toPath()), StandardCharsets.US_ASCII);
            check(contents.contains("nameserver 1.1.1.1"), "IPv4 DNS server missing");
            check(contents.contains("nameserver 2001:4860:4860:0:0:0:0:8888"), "global IPv6 DNS server missing");
            check(!contents.contains("fe80"), "link-local IPv6 DNS must not be exported without Android interface scope");
            check(contents.contains("options timeout:2 attempts:2 ndots:1"), "resolver options missing");

            File offlineResolver = GuestNetworkConfig.writeResolvConf(new TestContext(
                    new ConnectivityManager(null, null)), cache);
            String offlineContents = new String(Files.readAllBytes(offlineResolver.toPath()), StandardCharsets.US_ASCII);
            check(offlineContents.startsWith("options timeout:2"),
                    "offline mode must still create a valid resolver file for local hosting");
            check(!offlineContents.contains("nameserver"),
                    "offline mode must not invent a DNS server");
            System.out.println("PASS: Android DNS is exported safely; offline/local hosting remains possible");
        } finally {
            File[] children = temp.toFile().listFiles();
            if (children != null) for (File child : children) child.delete();
            temp.toFile().delete();
        }
    }
}
