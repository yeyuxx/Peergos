package peergos.server.mutable;
import java.util.logging.*;
import peergos.server.util.Logging;

import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class UserBasedBlacklist implements PublicKeyBlackList {
	private static final Logger LOG = Logging.LOG();

    private static final long RELOAD_PERIOD_MS = 3_600_000;

    private Map<PublicKeyHash, Boolean> banned = new ConcurrentHashMap<>();
    private final CoreNode core;
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    private final Path source;
    private final ForkJoinPool pool = new ForkJoinPool(1);
    private long lastModified, lastReloaded;

    public UserBasedBlacklist(Path source, CoreNode core, MutablePointers mutable, ContentAddressedStorage dht) {
        this.source = source;
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
        pool.submit(() -> {
            while (true) {
                try {
                    updateBlackList();
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, t.getMessage(), t);
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {}
            }
        });
    }

    private void updateBlackList() {
        if (! source.toFile().exists())
            return;
        long modified = source.toFile().lastModified();
        long now = System.currentTimeMillis();
        if (modified != lastModified || (now - lastReloaded > RELOAD_PERIOD_MS)) {
            LOG.info("Updating blacklist...");
            lastModified = modified;
            lastReloaded = now;
            Set<String> usernames = readUsernamesFromFile();
            Set<PublicKeyHash> updated = buildBlackList(usernames);
            banned.clear();
            for (PublicKeyHash hash : updated) {
                banned.put(hash, true);
            }
        }
    }

    private Set<String> readUsernamesFromFile() {
        try {
            if (! source.toFile().exists())
                return Collections.emptySet();
            return Files.lines(source)
                    .map(String::trim)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            return Collections.emptySet();
        }
    }

    private Set<PublicKeyHash> buildBlackList(Set<String> usernames) {
        Set<PublicKeyHash> res = new HashSet<>();
        for (String username : usernames) {
            res.addAll(WriterData.getOwnedKeysRecursive(username, core, mutable, dht).join());
        }
        return res;
    }

    @Override
    public boolean isAllowed(PublicKeyHash keyHash) {
        return ! banned.containsKey(keyHash);
    }
}
