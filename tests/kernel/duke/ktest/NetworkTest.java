package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.net.Ipv4;
import duke.kernel.net.Network;
import duke.kernel.time.HpetClock;

final class NetworkTest {

    // ktest's virtio-net only hears its own frames back, so DHCP never gets an answer.
    static void testQemusUsualAddressWithoutADhcpServer() throws InterruptedException {
        long deadline = HpetClock.nanos() + 10_000_000_000L;
        while (Network.address() == 0 && HpetClock.nanos() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("10.0.2.15", Ipv4.format(Network.address()), "address");
        assertEquals("255.255.255.0", Ipv4.format(Network.netmask()), "netmask");
        assertEquals("10.0.2.2", Ipv4.format(Network.gateway()), "gateway");
        assertEquals(0, Network.dns(), "no DNS server");
    }

    static void testParseAddresses() {
        assertEquals(Ipv4.address(10, 0, 3, 2), (int) Ipv4.parse("10.0.3.2"), "10.0.3.2");
        assertEquals(-1, (int) Ipv4.parse("255.255.255.255"), "broadcast");
        assertTrue(Ipv4.parse("10.0.3") == null, "three octets");
        assertTrue(Ipv4.parse("10.0.3.2.1") == null, "five octets");
        assertTrue(Ipv4.parse("10.0.3.256") == null, "an octet past 255");
        assertTrue(Ipv4.parse("10..3.2") == null, "an empty octet");
        assertTrue(Ipv4.parse("10.0.3.x") == null, "not a number");
    }
}
