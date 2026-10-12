import java.net.URI;
import org.polyzon.PolyzonClient;

/**
 * Calls the API through an HTTP proxy. HTTPS is tunnelled with CONNECT, so the proxy never
 * sees the API key.
 *
 * <pre>
 * export POLYZON_PROXY=http://user:password@proxy.example.com:3128
 * # Basic proxy authentication over HTTPS tunnels is off in the JDK by default:
 * java -Djdk.http.auth.tunneling.disabledSchemes= -cp target/classes examples/ProxyExample.java
 * </pre>
 *
 * Without an explicit proxy the client follows the JVM settings (-Dhttps.proxyHost=...,
 * -Dhttps.proxyPort=..., -Dhttp.nonProxyHosts=...).
 */
public class ProxyExample {
    public static void main(String[] args) {
        String proxy = args.length > 0 ? args[0] : System.getenv("POLYZON_PROXY");
        if (proxy == null) {
            System.err.println("Pass the proxy URL as an argument or set POLYZON_PROXY");
            System.exit(2);
        }
        try (PolyzonClient polyzon = PolyzonClient.builder().proxy(URI.create(proxy)).build()) {
            System.out.println("Token: " + polyzon.me().token().name());
        }
    }
}
