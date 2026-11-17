package MailClient;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Discovers a MailFlow server through UDP broadcast on active IPv4 adapters. */
final class LanMailServerDiscovery {
    private static final int DISCOVERY_PORT = 5000;
    private LanMailServerDiscovery() { }

    static Endpoint discover(Duration timeout) throws IOException {
        return discover(timeout,DISCOVERY_PORT);
    }
    static Endpoint discover(Duration timeout, int discoveryPort) throws IOException {
        String requestId=UUID.randomUUID().toString();
        byte[] request=(requestId+"|DISCOVER_MAILFLOW").getBytes(StandardCharsets.UTF_8);
        try(DatagramSocket socket=new DatagramSocket()) {
            socket.setBroadcast(true);
            for(int attempt=0;attempt<2;attempt++) {
                for(InetAddress target:broadcastTargets())
                    socket.send(new DatagramPacket(request,request.length,target,discoveryPort));
            }
            long deadline=System.nanoTime()+timeout.toNanos();
            byte[] bytes=new byte[1024];
            while(System.nanoTime()<deadline) {
                socket.setSoTimeout((int)Math.max(1,Math.min(1000,
                        Duration.ofNanos(deadline-System.nanoTime()).toMillis())));
                DatagramPacket response=new DatagramPacket(bytes,bytes.length);
                try { socket.receive(response); } catch(SocketTimeoutException exception) { continue; }
                String value=new String(response.getData(),response.getOffset(),response.getLength(),StandardCharsets.UTF_8);
                String prefix=requestId+"|MAILFLOW_SERVER|";
                if(!value.startsWith(prefix)) continue;
                try {
                    int port=Integer.parseInt(value.substring(prefix.length()));
                    if(port>=1 && port<=65535) return new Endpoint(response.getAddress().getHostAddress(),port);
                } catch(NumberFormatException ignored) { }
            }
        }
        throw new IOException("Không tìm thấy MailFlow Server trong mạng LAN");
    }

    private static Set<InetAddress> broadcastTargets() throws IOException {
        Set<InetAddress> targets=new LinkedHashSet<>();
        targets.add(InetAddress.getByName("127.0.0.1"));
        targets.add(InetAddress.getByName("255.255.255.255"));
        Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
        while(interfaces.hasMoreElements()) {
            NetworkInterface network=interfaces.nextElement();
            if(!network.isUp() || network.isLoopback()) continue;
            for(InterfaceAddress address:network.getInterfaceAddresses())
                if(address.getAddress() instanceof Inet4Address && address.getBroadcast()!=null)
                    targets.add(address.getBroadcast());
        }
        return targets;
    }

    record Endpoint(String host,int port) { }
}
