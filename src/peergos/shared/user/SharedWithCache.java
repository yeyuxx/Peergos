package peergos.shared.user;

import peergos.shared.user.fs.AbsoluteCapability;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SharedWithCache {

    public enum Access { READ, WRITE }

    private Map<byte[], Set<String>> sharedWithReadAccessCache = new ConcurrentHashMap<>(); //path to friends
    private Map<byte[], Set<String>> sharedWithWriteAccessCache = new ConcurrentHashMap<>();

    public SharedWithCache() {

    }

    private byte[] generateKey(AbsoluteCapability cap) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            os.write(cap.owner.toBytes());
            os.write(cap.getMapKey());
        }catch (Exception e) {
            e.printStackTrace();
        }
        return os.toByteArray();
    }

    public Set<String> getSharedWith(Access access, AbsoluteCapability cap) {
        return access == Access.READ ?
            getSharedWith(sharedWithReadAccessCache, cap) : getSharedWith(sharedWithWriteAccessCache, cap);
    }

    private synchronized Set<String> getSharedWith(Map<byte[], Set<String>> cache, AbsoluteCapability cap) {

        return new HashSet<>(cache.computeIfAbsent(generateKey(cap), k -> new HashSet<>()));
    }

    public void addSharedWith(Access access, AbsoluteCapability cap, String name) {
        Set<String> names = new HashSet<>();
        names.add(name);
        addSharedWith(access, cap, names);
    }

    public void addSharedWith(Access access, AbsoluteCapability cap, Set<String> names) {
        if(access == Access.READ) {
            addCacheEntry(sharedWithReadAccessCache, cap, names);
        } else if(access == Access.WRITE){
            addCacheEntry(sharedWithWriteAccessCache, cap, names);
        }
    }

    private synchronized void addCacheEntry(Map<byte[], Set<String>> cache, AbsoluteCapability cap, Set<String> names) {
        cache.computeIfAbsent(generateKey(cap), k -> new HashSet<>()).addAll(names);
    }

    public void clearSharedWith(AbsoluteCapability cap) {
        sharedWithReadAccessCache.computeIfPresent(generateKey(cap), (k, v) -> new HashSet<>());
        sharedWithWriteAccessCache.computeIfPresent(generateKey(cap), (k, v) -> new HashSet<>());
    }

    public void removeSharedWith(Access access, AbsoluteCapability cap, Set<String> names) {
        if(access == Access.READ) {
            removeCacheEntry(sharedWithReadAccessCache, cap, names);
        } else if(access == Access.WRITE){
            removeCacheEntry(sharedWithWriteAccessCache, cap, names);
        }
    }

    private synchronized void removeCacheEntry(Map<byte[], Set<String>> cache, AbsoluteCapability cap, Set<String> names) {
        cache.computeIfAbsent(generateKey(cap), k -> new HashSet<>()).removeAll(names);
    }

}
