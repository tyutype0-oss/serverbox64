package android.net;
public class ConnectivityManager { private final Network network; private final LinkProperties properties; public ConnectivityManager(Network network, LinkProperties properties) { this.network = network; this.properties = properties; } public Network getActiveNetwork() { return network; } public LinkProperties getLinkProperties(Network network) { return properties; } }
