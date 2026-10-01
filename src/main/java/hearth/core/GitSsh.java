package hearth.core;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** A revocable SSH key for one GitHub world; never an owner's GitHub account credential. */
public final class GitSsh {
    private GitSsh() {}

    public record Access(String privateKey, String publicKey, long deployKeyId) {
        @Override
        public String toString() {
            return "Access[deployKeyId=" + deployKeyId + ", keys=<redacted>]";
        }

        public KeyPair keyPair() throws GeneralSecurityException {
            if (privateKey == null || publicKey == null || privateKey.length() > 8192 || publicKey.length() > 2048) {
                throw new GeneralSecurityException("Invalid world access key");
            }
            KeyFactory factory = KeyFactory.getInstance("RSA");
            try {
                var pair = new KeyPair(factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))),
                        factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey))));
                if (!(pair.getPublic() instanceof java.security.interfaces.RSAPublicKey rsa)
                        || rsa.getModulus().bitLength() < 3072) {
                    throw new GeneralSecurityException("The world access key is too weak");
                }
                var signature = java.security.Signature.getInstance("SHA256withRSA");
                signature.initSign(pair.getPrivate());
                signature.update(new byte[]{1});
                byte[] proof = signature.sign();
                signature.initVerify(pair.getPublic());
                signature.update(new byte[]{1});
                if (!signature.verify(proof)) throw new GeneralSecurityException("World access keys do not match");
                return pair;
            } catch (IllegalArgumentException e) {
                throw new GeneralSecurityException("Invalid world access key encoding", e);
            }
        }

        public String authorizedKey() throws GeneralSecurityException {
            return PublicKeyEntry.toString(keyPair().getPublic());
        }
    }

    public static Access generate() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        var pair = generator.generateKeyPair();
        return new Access(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), 0);
    }

    public static String authorizedKey(String publicKey) throws GeneralSecurityException {
        if (publicKey == null || publicKey.length() > 2048) throw new GeneralSecurityException("Invalid world public key");
        var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
        if (!(key instanceof java.security.interfaces.RSAPublicKey rsa) || rsa.getModulus().bitLength() < 3072) {
            throw new GeneralSecurityException("The world public key is too weak");
        }
        return PublicKeyEntry.toString(key);
    }

    static String repositoryPath(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid GitHub world address");
        }
        if (!"ssh".equals(uri.getScheme()) || !"ssh.github.com".equals(uri.getHost())
                || uri.getPort() != 443 || !"git".equals(uri.getUserInfo())
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPath() == null
                || !uri.getPath().matches("/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+\\.git")) {
            throw new IllegalArgumentException("World access keys can only connect to GitHub over its verified SSH endpoint");
        }
        return uri.getPath().substring(1, uri.getPath().length() - 4);
    }

    // Published by GitHub. A changed server key requires a verified Hearth update, never an accept-any fallback.
    private static final Set<String> HOST_KEYS = Set.of(
            "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s",
            "SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM",
            "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU");

    static boolean trustedHost(String address, PublicKey key) {
        return "[ssh.github.com]:443".equals(address) && HOST_KEYS.contains(KeyUtils.getFingerPrint(key));
    }

    static SshdSessionFactory factory(SharedWorld.Remote remote, Path gitDir) throws IOException {
        if (remote.ssh() == null) return null;
        repositoryPath(remote.url());
        KeyPair pair;
        try {
            pair = remote.ssh().keyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot use the world access key", e);
        }
        return new SshdSessionFactoryBuilder()
                .setHomeDirectory(gitDir.toAbsolutePath().toFile())
                .setSshDirectory(gitDir.toAbsolutePath().toFile())
                .setConfigStoreFactory((home, config, user) -> null)
                .setDefaultIdentities(ignored -> List.of())
                .setDefaultKnownHostsFiles(ignored -> List.of())
                .setDefaultKeysProvider(ignored -> List.of(pair))
                .setPreferredAuthentications("publickey")
                .setConnectorFactory(null)
                .setServerKeyDatabase((home, sshDir) -> new ServerKeyDatabase() {
                    public List<PublicKey> lookup(String address, InetSocketAddress peer, Configuration config) {
                        return List.of();
                    }
                    public boolean accept(String address, InetSocketAddress peer, PublicKey key,
                                          Configuration config, CredentialsProvider provider) {
                        return trustedHost(address, key);
                    }
                }).build(null);
    }
}
