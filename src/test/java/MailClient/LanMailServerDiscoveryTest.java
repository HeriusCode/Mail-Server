package MailClient;

import MailServer.MailStorageService;
import MailServer.UdpMailServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanMailServerDiscoveryTest {
    @TempDir Path root;

    @Test void discoversRunningUdpMailServer() throws Exception {
        try(UdpMailServer server=new UdpMailServer("0.0.0.0",0,new MailStorageService(root),null)) {
            Thread worker=new Thread(() -> {
                try { server.run(); } catch(Exception ignored) { }
            });
            worker.start();
            var endpoint=LanMailServerDiscovery.discover(Duration.ofSeconds(2),server.getPort());
            java.net.InetAddress.getByName(endpoint.host());
            assertEquals(server.getPort(),endpoint.port());
            server.close(); worker.join(2000);
        }
    }
}
