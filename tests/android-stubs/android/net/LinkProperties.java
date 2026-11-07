package android.net;
import java.net.InetAddress;
import java.util.List;
public class LinkProperties { private final List<InetAddress> dns; public LinkProperties(List<InetAddress> dns) { this.dns = dns; } public List<InetAddress> getDnsServers() { return dns; } }
