package MailServer;

import java.nio.file.Path;

/** Child JVM used only by persistence regression tests. */
public final class RestartFixtureMain {
    public static void main(String[] args) throws Exception {
        Path diagnostic = Path.of(args[0]).resolve("child-diagnostic.log");
        UdpMailServer.Listener silent = new UdpMailServer.Listener() {
            private void log(String message) {
                try { java.nio.file.Files.writeString(diagnostic, message + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
                catch (Exception e) { throw new IllegalStateException("Cannot write test diagnostic", e); }
            }
            public void onClientSeen(java.net.InetSocketAddress address) { log("RECEIVED"); }
            public void onRequest(java.net.InetSocketAddress address) { }
            public void onLog(String message) { log(message); }
        };
        try (UdpMailServer server = new UdpMailServer("127.0.0.1", 0, new MailStorageService(Path.of(args[0])), silent)) {
            System.out.println("READY " + server.getPort()); System.out.flush(); server.run();
        }
    }
}
