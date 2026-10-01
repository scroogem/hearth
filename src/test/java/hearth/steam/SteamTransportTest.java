package hearth.steam;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.BufferOverflowException;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.*;

class SteamTransportTest {
    @Test void rejectsForeignFramesAndPreservesTheReliableWireFormat() {
        byte[] payload = new byte[]{4, 8, 15, 16};
        byte[] packet = new SteamFrame(SteamFrame.DATA, -1, payload).encode();
        assertEquals(0x45345354, ByteBuffer.wrap(packet).getInt());
        var decoded = SteamFrame.decode(ByteBuffer.wrap(packet));
        assertEquals(-1, decoded.id());
        assertArrayEquals(payload, decoded.data());
        assertNull(SteamFrame.decode(ByteBuffer.wrap(new byte[11])));
        packet[4] = 3;
        assertNull(SteamFrame.decode(ByteBuffer.wrap(packet)));
        for (var invalid : new SteamFrame[]{
                new SteamFrame(SteamFrame.DATA, 0, payload),
                new SteamFrame(SteamFrame.DATA, 1, new byte[0]),
                new SteamFrame(SteamFrame.DATA, 1, new byte[SteamFrame.CHUNK + 1]),
                new SteamFrame(SteamFrame.OPEN, 1, new byte[15]),
                new SteamFrame(SteamFrame.FIN, 1, payload),
                new SteamFrame((byte) 100, 1, payload)}) {
            assertNull(SteamFrame.decode(ByteBuffer.wrap(invalid.encode())));
        }
    }

    @Test void preservesMessageOrderAndReleasesNativeBuffersEvenOnFailure() throws Exception {
        try (var sdk = new FakeSdk(); var transport = new SteamNetworkingMessagesTransport(sdk,
                new SteamNetworkingMessagesTransport.SessionListener() {
                    public void onSessionRequest(long peer) {}
                    public void onSessionFailed(long peer, int reason, String detail) {}
                })) {
            ByteBuffer outgoing = ByteBuffer.allocateDirect(4).put(new byte[]{1, 2, 3, 4});
            outgoing.flip().position(1);
            assertEquals(1, transport.sendResult(42, outgoing, 480));
            assertEquals(9, sdk.flags);
            assertEquals(42, sdk.peer);
            assertArrayEquals(new byte[]{2, 3, 4}, sdk.sent);
            sdk.queue(42, new byte[]{9, 8});
            sdk.queue(43, new byte[]{7});
            assertEquals(2, transport.availablePacketSize(480));
            assertThrows(BufferOverflowException.class, () -> transport.receive(ByteBuffer.allocate(1), 480));
            assertEquals(1, sdk.released);
            assertEquals(1, transport.availablePacketSize(480));
            ByteBuffer received = ByteBuffer.allocate(8);
            assertEquals(43, transport.receive(received, 480).remoteSteamId());
            assertEquals(7, received.get(0));
            assertEquals(2, sdk.released);
            assertEquals(1, sdk.receiveCalls);
        }
    }

    private static final class FakeSdk implements SteamNetworkingMessagesTransport.NativeAccess, AutoCloseable {
        final ArrayDeque<Memory> queued = new ArrayDeque<>(), allocated = new ArrayDeque<>();
        byte[] sent;
        int flags, released, receiveCalls;
        long peer;
        void queue(long id, byte[] bytes) {
            Memory payload = new Memory(bytes.length), message = new Memory(256);
            payload.write(0, bytes, 0, bytes.length);
            message.clear(); message.setPointer(0, payload); message.setInt(8, bytes.length);
            try (Memory identity = SteamNetworkingMessagesTransport.newIdentity(id)) {
                message.write(16, identity.getByteArray(0, 136), 0, 136);
            }
            allocated.add(payload); allocated.add(message); queued.add(message);
        }
        public int send(Pointer identity, Pointer data, int size, int flags, int channel) {
            this.peer = SteamNetworkingMessagesTransport.readSteamId(identity);
            this.flags = flags; sent = data.getByteArray(0, size); return 1;
        }
        public int receive(int channel, Pointer[] messages, int maximum) {
            receiveCalls++; int count = 0;
            while (count < maximum && !queued.isEmpty()) messages[count++] = queued.removeFirst();
            return count;
        }
        public boolean accept(Pointer identity) { return true; }
        public void closeSession(Pointer identity) {}
        public void initializeRelayNetworkAccess() {}
        public void releaseMessage(Pointer message) { released++; }
        public void setCallbacks(SteamNetworkingMessagesTransport.SessionRequestCallback request,
                                 SteamNetworkingMessagesTransport.SessionFailedCallback failure) {}
        public void setDebugOutput(int level, SteamNetworkingMessagesTransport.DebugOutputCallback callback) {}
        public void close() { allocated.forEach(Memory::close); }
    }
}
