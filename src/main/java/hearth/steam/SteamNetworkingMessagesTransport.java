// Adapted from e4steam, Kamilchik, Apache-2.0. See THIRD_PARTY_NOTICES.md.
package hearth.steam;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayDeque;

/**
 * Packet-oriented transport backed by ISteamNetworkingMessages, the
 * connectionless facade over Steam Networking Sockets.
 */
final class SteamNetworkingMessagesTransport implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger("hearth");
    private static final int STEAM_IDENTITY_TYPE = 16;
    private static final int STEAM_IDENTITY_SIZE = 136;
    private static final int STEAM_IDENTITY_VALUE_SIZE = Long.BYTES;

    private static final int SEND_NO_NAGLE = 1;
    private static final int SEND_RELIABLE = 8;
    private static final int SEND_RELIABLE_NO_NAGLE = SEND_RELIABLE | SEND_NO_NAGLE;


    private static final long IDENTITY_TYPE_OFFSET = 0;
    private static final long IDENTITY_SIZE_OFFSET = 4;
    private static final long IDENTITY_VALUE_OFFSET = 8;

    private static final long MESSAGE_DATA_OFFSET = 0;
    private static final long MESSAGE_SIZE_OFFSET = 8;
    private static final long MESSAGE_IDENTITY_OFFSET = 16;


    private static final long CONNECTION_INFO_END_REASON_OFFSET = 176;
    private static final long CONNECTION_INFO_END_DEBUG_OFFSET = 180;
    private static final int CONNECTION_INFO_END_DEBUG_SIZE = 128;

    static final class Received {
        private final long remoteSteamId;
        private final int size;

        Received(long remoteSteamId, int size) {
            this.remoteSteamId = remoteSteamId;
            this.size = size;
        }

        long remoteSteamId() { return remoteSteamId; }
        int size() { return size; }
    }

    interface SessionListener {
        void onSessionRequest(long remoteSteamId);

        void onSessionFailed(long remoteSteamId, int endReason, String detail);
    }

    interface SessionRequestCallback extends Callback {
        void invoke(Pointer request);
    }

    interface SessionFailedCallback extends Callback {
        void invoke(Pointer failure);
    }

    interface DebugOutputCallback extends Callback {
        void invoke(int type, String message);
    }

    interface NativeAccess {
        int send(Pointer identity, Pointer data, int size, int flags, int channel);

        int receive(int channel, Pointer[] messages, int maxMessages);

        boolean accept(Pointer identity);

        void closeSession(Pointer identity);


        void initializeRelayNetworkAccess();

        void releaseMessage(Pointer message);

        void setCallbacks(SessionRequestCallback request, SessionFailedCallback failure);

        void setDebugOutput(int detailLevel, DebugOutputCallback callback);
    }

    private final NativeAccess nativeAccess;
    private final SessionListener listener;
    private final Map<Long, Memory> identities = new HashMap<>();
    private final SessionRequestCallback requestCallback = this::handleSessionRequest;
    private final SessionFailedCallback failedCallback = this::handleSessionFailed;
    private final DebugOutputCallback debugOutputCallback = this::handleDebugOutput;

    private static final int RECEIVE_BATCH_SIZE = 32;
    private final ArrayDeque<Pointer> pendingMessages = new ArrayDeque<>(RECEIVE_BATCH_SIZE);
    private boolean closed;

    static SteamNetworkingMessagesTransport open(Path steamApiLibrary, SessionListener listener)
            throws IOException {
        return new SteamNetworkingMessagesTransport(
                new JnaNativeAccess(steamApiLibrary),
                listener
        );
    }

    SteamNetworkingMessagesTransport(NativeAccess nativeAccess, SessionListener listener) {
        this.nativeAccess = Objects.requireNonNull(nativeAccess, "nativeAccess");
        this.listener = Objects.requireNonNull(listener, "listener");
        try {
            nativeAccess.setCallbacks(requestCallback, failedCallback);
            nativeAccess.setDebugOutput(4, debugOutputCallback);
            nativeAccess.initializeRelayNetworkAccess();
        } catch (RuntimeException | Error exception) {
            try {
                nativeAccess.setCallbacks(null, null);
                nativeAccess.setDebugOutput(0, null);
            } catch (Throwable ignored) {
            }
            throw exception;
        }
    }

    int sendResult(long remoteSteamId, ByteBuffer payload, int channel) throws IOException {
        ensureOpen();
        if (!payload.isDirect()) {
            throw new IOException("Steam Networking Messages requires a direct send buffer");
        }
        int size = payload.remaining();
        Pointer data = Native.getDirectBufferPointer(payload);
        if (data == null) {
            throw new IOException("Could not access the direct Steam send buffer");
        }
        data = data.share(payload.position());
        return nativeAccess.send(identity(remoteSteamId), data, size, SEND_RELIABLE_NO_NAGLE, channel);
    }

    int availablePacketSize(int channel) throws IOException {
        ensureOpen();
        if (pendingMessages.isEmpty()) {
            Pointer[] messages = new Pointer[RECEIVE_BATCH_SIZE];
            int count = nativeAccess.receive(channel, messages, RECEIVE_BATCH_SIZE);
            if (count < 0 || count > RECEIVE_BATCH_SIZE) {
                throw new IOException("Steam returned an invalid received-message count: " + count);
            }
            if (count == 0) {
                return 0;
            }
            for (int index = 0; index < count; index++) {
                if (messages[index] == null) {
                    while (!pendingMessages.isEmpty()) {
                        nativeAccess.releaseMessage(pendingMessages.removeFirst());
                    }
                    throw new IOException("Steam returned a null received message");
                }
                pendingMessages.addLast(messages[index]);
            }
        }
        Pointer pendingMessage = pendingMessages.peekFirst();
        int size = pendingMessage.getInt(MESSAGE_SIZE_OFFSET);
        if (size == 0) {
            nativeAccess.releaseMessage(pendingMessages.removeFirst());
        }
        return size;
    }

    Received receive(ByteBuffer target, int channel) throws IOException {
        ensureOpen();
        Pointer message = pendingMessages.pollFirst();
        if (message == null) {
            throw new IOException("No Steam Networking Messages packet is available on channel " + channel);
        }

        try {
            int size = message.getInt(MESSAGE_SIZE_OFFSET);
            if (size < 0) {
                throw new IOException("Steam returned a negative message size: " + size);
            }
            if (size > target.remaining()) {
                throw new BufferOverflowException();
            }
            Pointer data = message.getPointer(MESSAGE_DATA_OFFSET);
            if (size > 0 && data == null) {
                throw new IOException("Steam returned a null message payload");
            }
            if (size > 0) {
                target.put(data.getByteBuffer(0, size));
            }
            return new Received(readSteamId(message.share(MESSAGE_IDENTITY_OFFSET)), size);
        } finally {
            nativeAccess.releaseMessage(message);
        }
    }

    void discardPendingMessage() throws IOException {
        ensureOpen();
        Pointer message = pendingMessages.pollFirst();
        if (message != null) {
            nativeAccess.releaseMessage(message);
        }
    }

    boolean accept(long remoteSteamId) {
        ensureOpenUnchecked();
        return nativeAccess.accept(identity(remoteSteamId));
    }

    void closePeer(long remoteSteamId) {
        if (closed) {
            return;
        }
        Memory identity = identities.remove(remoteSteamId);
        try (Memory value = identity == null ? newIdentity(remoteSteamId) : identity) { nativeAccess.closeSession(value); }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        nativeAccess.setCallbacks(null, null);
        nativeAccess.setDebugOutput(0, null);
        Pointer message;
        while ((message = pendingMessages.pollFirst()) != null) {
            nativeAccess.releaseMessage(message);
        }
        for (Memory identity : identities.values()) {
            nativeAccess.closeSession(identity);
            identity.close();
        }
        identities.clear();
    }

    private void handleSessionRequest(Pointer request) {
        if (closed || request == null) {
            return;
        }
        long remoteSteamId = readSteamId(request);
        if (remoteSteamId != 0) {
            try {
                listener.onSessionRequest(remoteSteamId);
            } catch (Throwable throwable) {
                LOGGER.error("Steam session-request callback failed", throwable);
            }
        }
    }

    private void handleSessionFailed(Pointer failure) {
        if (closed || failure == null) {
            return;
        }
        long remoteSteamId = readSteamId(failure);
        if (remoteSteamId == 0) {
            return;
        }
        int endReason = failure.getInt(CONNECTION_INFO_END_REASON_OFFSET);
        String detail = readFixedString(
                failure,
                CONNECTION_INFO_END_DEBUG_OFFSET,
                CONNECTION_INFO_END_DEBUG_SIZE
        );
        try {
            listener.onSessionFailed(remoteSteamId, endReason, detail);
        } catch (Throwable throwable) {
            LOGGER.error("Steam session-failure callback failed", throwable);
        }
    }

    private void handleDebugOutput(int type, String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        String clean = message.trim();
        if (type <= 2) {
            LOGGER.warn("Steam Networking Sockets: {}", clean);
        } else {
            LOGGER.debug("Steam Networking Sockets: {}", clean);
        }
    }

    private Memory identity(long remoteSteamId) {
        return identities.computeIfAbsent(remoteSteamId, SteamNetworkingMessagesTransport::newIdentity);
    }

    static Memory newIdentity(long remoteSteamId) {
        Memory identity = new Memory(STEAM_IDENTITY_SIZE);
        identity.clear();
        identity.setInt(IDENTITY_TYPE_OFFSET, STEAM_IDENTITY_TYPE);
        identity.setInt(IDENTITY_SIZE_OFFSET, STEAM_IDENTITY_VALUE_SIZE);
        identity.setLong(IDENTITY_VALUE_OFFSET, remoteSteamId);
        return identity;
    }

    static long readSteamId(Pointer identity) {
        if (identity == null
                || identity.getInt(IDENTITY_TYPE_OFFSET) != STEAM_IDENTITY_TYPE
                || identity.getInt(IDENTITY_SIZE_OFFSET) != STEAM_IDENTITY_VALUE_SIZE) {
            return 0;
        }
        return identity.getLong(IDENTITY_VALUE_OFFSET);
    }

    private static String readFixedString(Pointer source, long offset, int maximumSize) {
        byte[] bytes = source.getByteArray(offset, maximumSize);
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) {
            length++;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Steam Networking Messages transport is closed");
        }
    }

    private void ensureOpenUnchecked() {
        if (closed) {
            throw new IllegalStateException("Steam Networking Messages transport is closed");
        }
    }

    private interface FlatApi extends Library {
        Pointer SteamAPI_SteamNetworkingMessages_SteamAPI_v002();


        Pointer SteamAPI_SteamNetworkingUtils_SteamAPI_v004();

        int SteamAPI_ISteamNetworkingMessages_SendMessageToUser(
                Pointer self,
                Pointer identity,
                Pointer data,
                int size,
                int flags,
                int channel
        );

        int SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel(
                Pointer self,
                int channel,
                Pointer[] messages,
                int maxMessages
        );

        byte SteamAPI_ISteamNetworkingMessages_AcceptSessionWithUser(Pointer self, Pointer identity);

        byte SteamAPI_ISteamNetworkingMessages_CloseSessionWithUser(Pointer self, Pointer identity);

        void SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(Pointer self);

        void SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionRequest(
                Pointer self,
                SessionRequestCallback callback
        );

        void SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionFailed(
                Pointer self,
                SessionFailedCallback callback
        );

        void SteamAPI_ISteamNetworkingUtils_SetDebugOutputFunction(
                Pointer self,
                int detailLevel,
                DebugOutputCallback callback
        );

        void SteamAPI_SteamNetworkingMessage_t_Release(Pointer message);
    }

    private static final class JnaNativeAccess implements NativeAccess {
        private final FlatApi api;
        private final Pointer messages;
        private final Pointer utils;

        private JnaNativeAccess(Path steamApiLibrary) throws IOException {
            Objects.requireNonNull(steamApiLibrary, "steamApiLibrary");
            try {
                api = Native.load(steamApiLibrary.toAbsolutePath().normalize().toString(), FlatApi.class);
                messages = api.SteamAPI_SteamNetworkingMessages_SteamAPI_v002();
                utils = api.SteamAPI_SteamNetworkingUtils_SteamAPI_v004();
            } catch (UnsatisfiedLinkError | RuntimeException exception) {
                throw new IOException("Could not bind Steam Networking Messages", exception);
            }
            if (messages == null || utils == null) {
                throw new IOException("Steam Networking Messages is unavailable after SteamAPI initialization");
            }
        }

        @Override
        public int send(Pointer identity, Pointer data, int size, int flags, int channel) {
            return api.SteamAPI_ISteamNetworkingMessages_SendMessageToUser(
                    messages,
                    identity,
                    data,
                    size,
                    flags,
                    channel
            );
        }

        @Override
        public int receive(int channel, Pointer[] output, int maxMessages) {
            return api.SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel(
                    messages,
                    channel,
                    output,
                    maxMessages
            );
        }

        @Override
        public boolean accept(Pointer identity) {
            return api.SteamAPI_ISteamNetworkingMessages_AcceptSessionWithUser(messages, identity) != 0;
        }

        @Override
        public void closeSession(Pointer identity) {
            api.SteamAPI_ISteamNetworkingMessages_CloseSessionWithUser(messages, identity);
        }

        @Override
        public void initializeRelayNetworkAccess() {
            api.SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(utils);
        }

        @Override
        public void releaseMessage(Pointer message) {
            api.SteamAPI_SteamNetworkingMessage_t_Release(message);
        }

        @Override
        public void setCallbacks(SessionRequestCallback request, SessionFailedCallback failure) {
            api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionRequest(utils, request);
            api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionFailed(utils, failure);
        }

        @Override
        public void setDebugOutput(int detailLevel, DebugOutputCallback callback) {
            api.SteamAPI_ISteamNetworkingUtils_SetDebugOutputFunction(utils, detailLevel, callback);
        }
    }
}
