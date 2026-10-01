package hearth.core;

import com.google.gson.Gson;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Separate repository: creator writes, guests have GitHub read-only keys. No Minecraft files here. */
public final class PolicyStore implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private static final String ORIGIN = "refs/remotes/origin/main";
    private static final String LOCAL = "refs/hearth/policy";
    private static final String PIN = "refs/hearth/verified-policy";
    private final Git git;
    private final SharedWorld.Remote remote;
    private final SshdSessionFactory ssh;

    public PolicyStore(Path gitDir, String url, GitSsh.Access access) throws Exception {
        remote = new SharedWorld.Remote(url, "git", null, access);
        ssh = GitSsh.factory(remote, gitDir);
        try { git = Git.init().setBare(true).setDirectory(gitDir.toFile()).call(); }
        catch (Exception e) { if (ssh != null) ssh.close(); throw e; }
    }

    private void transport(Transport transport) {
        if (transport instanceof SshTransport sshTransport && ssh != null) sshTransport.setSshSessionFactory(ssh);
    }

    private void fetch() throws Exception {
        git.fetch().setRemote(remote.url()).setRefSpecs(new RefSpec("+refs/heads/main:" + ORIGIN))
                .setTransportConfigCallback(this::transport).setTimeout(45).call();
    }

    public WorldPolicy load(WorldPolicy.Trust trust) throws Exception {
        fetch();
        return verified(trust);
    }

    private WorldPolicy verified(WorldPolicy.Trust trust) throws Exception {
        Repository repo = git.getRepository();
        ObjectId current = repo.resolve(ORIGIN);
        if (current == null) throw new IOException("The world creator's rules are missing");
        try (RevWalk walk = new RevWalk(repo)) {
            var commit = walk.parseCommit(current);
            ObjectId pinned = repo.resolve(PIN);
            if (pinned != null && !walk.isMergedInto(walk.parseCommit(pinned), commit)) {
                throw new IOException("The creator's rule history was replaced; the world was kept locally");
            }
            try (TreeWalk tree = TreeWalk.forPath(repo, "rules.json", commit.getTree())) {
                if (tree == null || tree.getFileMode(0) != FileMode.REGULAR_FILE) throw new IOException("Invalid world rules repository");
                WorldPolicy.Signed signed = JSON.fromJson(new String(repo.open(tree.getObjectId(0)).getBytes(1_000_000),
                        StandardCharsets.UTF_8), WorldPolicy.Signed.class);
                WorldPolicy policy = WorldPolicy.verify(signed, trust);
                var update = repo.updateRef(PIN);
                update.setNewObjectId(current);
                update.setForceUpdate(true);
                if (update.update() == RefUpdate.Result.LOCK_FAILURE) throw new IOException("Could not remember the verified rules");
                return policy;
            }
        }
    }

    public void publish(WorldPolicy policy, GitSsh.Access owner) throws Exception {
        WorldPolicy.Signed signed = policy.sign(owner);
        Repository repo = git.getRepository();
        // The first repository is empty; later writes fetch the exact parent and use a fast-forward push.
        boolean exists = !git.lsRemote().setRemote(remote.url()).setHeads(true)
                .setTransportConfigCallback(this::transport).setTimeout(45).call().isEmpty();
        ObjectId parent = null;
        if (exists) {
            fetch();
            WorldPolicy previous = verified(policy.trust());
            if (policy.equals(previous)) return; // A completed upload can be retried after a lost response.
            if (policy.revision() != previous.revision() + 1) throw new IOException("World rules changed; reload before saving");
            parent = repo.resolve(ORIGIN);
        } else if (policy.revision() != 1) throw new IOException("Missing initial world rules");
        ObjectId id;
        try (ObjectInserter inserter = repo.newObjectInserter()) {
            TreeFormatter tree = new TreeFormatter();
            tree.append("rules.json", FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, JSON.toJson(signed).getBytes(StandardCharsets.UTF_8)));
            CommitBuilder builder = new CommitBuilder();
            builder.setTreeId(inserter.insert(tree));
            if (parent != null) builder.setParentId(parent);
            builder.setAuthor(new PersonIdent("Hearth creator", "hearth@localhost"));
            builder.setCommitter(builder.getAuthor());
            builder.setMessage("World rules " + policy.revision());
            id = inserter.insert(builder);
            inserter.flush();
        }
        var update = repo.updateRef(LOCAL);
        update.setNewObjectId(id);
        update.setForceUpdate(true);
        if (update.update() == RefUpdate.Result.LOCK_FAILURE) throw new IOException("Could not save world rules locally");
        boolean confirmed = false;
        for (var result : git.push().setRemote(remote.url()).setRefSpecs(new RefSpec(LOCAL + ":refs/heads/main"))
                .setTransportConfigCallback(this::transport).setTimeout(45).call()) {
            var pushed = result.getRemoteUpdate("refs/heads/main");
            if (pushed == null || (pushed.getStatus() != RemoteRefUpdate.Status.OK
                    && pushed.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE)) throw new IOException("World rules changed; reload before saving");
            confirmed = true;
        }
        if (!confirmed) throw new IOException("GitHub did not confirm the updated world rules");
    }

    @Override public void close() { git.close(); if (ssh != null) ssh.close(); }
}
