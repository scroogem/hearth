package hearth.steam;

import java.nio.ByteBuffer;

/** e4steam v4's reliable TCP frames, without UDP/addon/dedicated-server features. */
record SteamFrame(byte type, int id, byte[] data) {
    static final byte OPEN = 1, DATA = 2, FIN = 3, RESET = 4, ACK = 6, READY = 7;
    static final int CHUNK = 32768, HEADER = 12;

    byte[] encode() {
        return ByteBuffer.allocate(HEADER + data.length).putInt(0x45345354).put((byte) 4).put(type)
                .putShort((short) 0).putInt(id).put(data).array();
    }

    static SteamFrame decode(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER || buffer.getInt() != 0x45345354 || buffer.get() != 4) return null;
        byte type = buffer.get();
        if (buffer.getShort() != 0) return null;
        int id = buffer.getInt(), length = buffer.remaining();
        if (id == 0 || switch (type) {
            case OPEN -> length != 16;
            case DATA -> length == 0 || length > CHUNK;
            case ACK -> length != 3;
            case FIN, RESET, READY -> length != 0;
            default -> true;
        }) return null;
        byte[] data = new byte[length]; buffer.get(data);
        return new SteamFrame(type, id, data);
    }
}
