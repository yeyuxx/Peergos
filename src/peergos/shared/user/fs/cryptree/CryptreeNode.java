package peergos.shared.user.fs.cryptree;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** A cryptree node controls read and write access to a directory or file.
 *
 * A directory contains the following distinct keys {base, parent}, and file contains {base == parent, data}
 *
 * The serialized encrypted form stores a link from the base key to the other key.
 * For a directory, the base key encrypts the links to child directories and files. For a file the datakey encrypts the
 * file's data. The parent key encrypts the link to the parent directory's parent key and the metadata (FileProperties).
 *
 * There are three network visible components to the serialization:
 * 1) A fixed size block encrypted with the base key, containing the second key (parent or data key), the location of
 *    the next chunk, and an optional symmetric link to a signing pair
 */
public class CryptreeNode implements Cborable {
    private static final int CURRENT_VERSION = 1;
    private static final int META_DATA_PADDING_BLOCKSIZE = 16;
    private static final int BASE_BLOCK_PADDING_BLOCKSIZE = 64;
    private static final int MIN_FRAGMENT_SIZE = 4096;
    private static int MAX_CHILD_LINKS_PER_BLOB = 500;

    public static synchronized void setMaxChildLinkPerBlob(int newValue) {
        MAX_CHILD_LINKS_PER_BLOB = newValue;
    }

    public static synchronized int getMaxChildLinksPerBlob() {
        return MAX_CHILD_LINKS_PER_BLOB;
    }

    private transient final MaybeMultihash lastCommittedHash;
    private transient final boolean isDirectory;
    protected final PaddedCipherText fromBaseKey;
    protected final FragmentedPaddedCipherText childrenOrData;
    protected final PaddedCipherText fromParentKey;

    public CryptreeNode(MaybeMultihash lastCommittedHash,
                        boolean isDirectory,
                        PaddedCipherText fromBaseKey,
                        FragmentedPaddedCipherText childrenOrData,
                        PaddedCipherText fromParentKey) {
        this.lastCommittedHash = lastCommittedHash;
        this.isDirectory = isDirectory;
        this.fromBaseKey = fromBaseKey;
        this.childrenOrData = childrenOrData;
        this.fromParentKey = fromParentKey;
    }

    public int getVersion() {
        return CURRENT_VERSION;
    }

    private static class FromBase implements Cborable {
        public final SymmetricKey parentOrData;
        public final Optional<SymmetricLinkToSigner> signer;
        public final RelativeCapability nextChunk;

        public FromBase(SymmetricKey parentOrData,
                        Optional<SymmetricLinkToSigner> signer,
                        RelativeCapability nextChunk) {
            this.parentOrData = parentOrData;
            this.signer = signer;
            this.nextChunk = nextChunk;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("k", parentOrData);
            signer.ifPresent(w -> state.put("w", w));
            state.put("n", nextChunk);
            return CborObject.CborMap.build(state);
        }

        public static FromBase fromCbor(CborObject cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Incorrect cbor for FromBase: " + cbor);

            CborObject.CborMap m = (CborObject.CborMap) cbor;
            SymmetricKey k = m.get("k", SymmetricKey::fromCbor);
            Optional<SymmetricLinkToSigner> w = m.getOptional("w", SymmetricLinkToSigner::fromCbor);
            RelativeCapability nextChunk = m.get("n", RelativeCapability::fromCbor);
            return new FromBase(k, w, nextChunk);
        }
    }

    private FromBase getBaseBlock(SymmetricKey baseKey) {
        return fromBaseKey.decrypt(baseKey, FromBase::fromCbor);
    }

    private static class FromParent implements Cborable {
        public final Optional<RelativeCapability> parentLink;
        public final FileProperties properties;

        public FromParent(Optional<RelativeCapability> parentLink, FileProperties properties) {
            this.parentLink = parentLink;
            this.properties = properties;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            parentLink.ifPresent(p -> state.put("p", p));
            state.put("s", properties);
            return CborObject.CborMap.build(state);
        }

        public static FromParent fromCbor(CborObject cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Incorrect cbor for FromParent: " + cbor);

            CborObject.CborMap m = (CborObject.CborMap) cbor;
            Optional<RelativeCapability> parentLink = m.getOptional("p", RelativeCapability::fromCbor);
            FileProperties properties = m.get("s", FileProperties::fromCbor);
            return new FromParent(parentLink, properties);
        }
    }

    private FromParent getParentBlock(SymmetricKey parentKey) {
        return fromParentKey.decrypt(parentKey, FromParent::fromCbor);
    }

    public static class DirAndChildren {
        public final CryptreeNode dir;
        public final List<FragmentWithHash> childData;

        public DirAndChildren(CryptreeNode dir, List<FragmentWithHash> childData) {
            this.dir = dir;
            this.childData = childData;
        }

        public CompletableFuture<CryptreeNode> commit(WritableAbsoluteCapability us,
                                                      Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                      NetworkAccess network,
                                                      TransactionId tid) {
            return commitChildrenLinks(us, entryWriter, network, tid)
                    .thenCompose(hashes -> dir.commit(us, entryWriter, network, tid));
        }

        public CompletableFuture<Snapshot> commit(Snapshot current,
                                                  WriteSynchronizer.Committer committer,
                                                  WritableAbsoluteCapability us,
                                                  Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                  NetworkAccess network,
                                                  TransactionId tid) {
            return commitChildrenLinks(us, entryWriter, network, tid)
                    .thenCompose(hashes -> dir.commit(current, committer, us, entryWriter, network, tid));
        }

        public CompletableFuture<List<Multihash>> commitChildrenLinks(WritableAbsoluteCapability us,
                                                                      Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                                      NetworkAccess network,
                                                                      TransactionId tid) {
            List<Fragment> frags = childData.stream()
                    .filter(f -> ! f.hash.isIdentity())
                    .map(f -> f.fragment)
                    .collect(Collectors.toList());
            SigningPrivateKeyAndPublicHash signer = dir.getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter);
            return network.uploadFragments(frags, us.owner, signer, l -> {}, tid);
        }
    }

    public static class ChildrenLinks implements Cborable {
        public final List<RelativeCapability> children;

        public ChildrenLinks(List<RelativeCapability> children) {
            this.children = children;
        }

        @Override
        public CborObject toCbor() {
            return new CborObject.CborList(children);
        }

        public static ChildrenLinks fromCbor(CborObject cbor) {
            if (! (cbor instanceof CborObject.CborList))
                throw new IllegalStateException("Incorrect cbor for ChildrenLinks: " + cbor);

            return new ChildrenLinks(((CborObject.CborList) cbor).value
                    .stream()
                    .map(RelativeCapability::fromCbor)
                    .collect(Collectors.toList()));
        }

        public static ChildrenLinks empty() {
            return new ChildrenLinks(Collections.emptyList());
        }
    }

    public MaybeMultihash committedHash() {
        return lastCommittedHash;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public boolean isReadable(SymmetricKey baseKey) {
        try {
            getBaseBlock(baseKey);
            return true;
        } catch (Exception e) {}
        return false;
    }

    public Optional<SymmetricLinkToSigner> getWriterLink(SymmetricKey baseKey) {
        return getBaseBlock(baseKey).signer;
    }

    public SymmetricKey getParentKey(SymmetricKey baseKey) {
        if (isDirectory())
            try {
                return getBaseBlock(baseKey).parentOrData;
            } catch (Exception e) {
                // if we don't have read access to this folder, then we must just have the parent key already
            }
        return baseKey;
    }

    public SymmetricKey getDataKey(SymmetricKey baseKey) {
        if (isDirectory())
            throw new IllegalStateException("Directories don't have a data key!");
        return getBaseBlock(baseKey).parentOrData;
    }

    public FileProperties getProperties(SymmetricKey parentKey) {
        return getParentBlock(parentKey).properties;
    }

    public SigningPrivateKeyAndPublicHash getSigner(SymmetricKey rBaseKey,
                                                    SymmetricKey wBaseKey,
                                                    Optional<SigningPrivateKeyAndPublicHash> entrySigner) {
        return getBaseBlock(rBaseKey).signer
                .map(link -> link.target(wBaseKey))
                .orElseGet(() -> entrySigner.orElseThrow(() ->
                        new IllegalStateException("No link to private signing key present on directory!")));
    }

    public FileRetriever retriever(SymmetricKey baseKey) {
        return new EncryptedChunkRetriever(childrenOrData, getNextChunkLocation(baseKey), getDataKey(baseKey));
    }

    public CompletableFuture<List<RelativeCapability>> getDirectChildren(SymmetricKey baseKey, NetworkAccess network) {
        if (! isDirectory)
            return CompletableFuture.completedFuture(Collections.emptyList());
        return getLinkedData(baseKey, ChildrenLinks::fromCbor, network, x -> {})
                .thenApply(c -> c.children);
    }

    public CompletableFuture<Set<AbsoluteCapability>> getDirectChildrenCapabilities(AbsoluteCapability us, NetworkAccess network) {
        return getDirectChildren(us.rBaseKey, network)
                .thenApply(c ->c.stream()
                        .map(cap -> cap.toAbsolute(us))
                        .collect(Collectors.toSet()));
    }

    public CompletableFuture<Set<AbsoluteCapability>> getAllChildrenCapabilities(AbsoluteCapability us, NetworkAccess network) {
        CompletableFuture<Set<AbsoluteCapability>> childrenFuture = getDirectChildrenCapabilities(us, network);

        CompletableFuture<Optional<RetrievedCapability>> moreChildrenFuture = getNextChunk(us, network);

        return childrenFuture.thenCompose(children -> moreChildrenFuture.thenCompose(moreChildrenSource -> {
                    CompletableFuture<Set<AbsoluteCapability>> moreChildren = moreChildrenSource
                            .map(d -> d.fileAccess.getAllChildrenCapabilities(d.capability, network))
                            .orElse(CompletableFuture.completedFuture(Collections.emptySet()));
                    return moreChildren.thenApply(moreRetrievedChildren -> {
                        Set<AbsoluteCapability> results = Stream.concat(
                                children.stream(),
                                moreRetrievedChildren.stream())
                                .collect(Collectors.toSet());
                        return results;
                    });
                })
        );
    }

    public CompletableFuture<Set<RetrievedCapability>> getDirectChildren(AbsoluteCapability us, NetworkAccess network) {
        return getDirectChildrenCapabilities(us, network)
                .thenCompose(c -> network.retrieveAllMetadata(c.stream()
                        .collect(Collectors.toList()))
                        .thenApply(HashSet::new));
    }

    public CompletableFuture<Set<RetrievedCapability>> getDirectChildren(WriterData base, AbsoluteCapability us, NetworkAccess network) {
        Map<PublicKeyHash, WriterData> existing = new HashMap<>();
        existing.put(us.writer, base);
        return getDirectChildrenCapabilities(us, network)
                .thenCompose(c -> network.retrieveAllMetadata(c.stream()
                        .collect(Collectors.toList()), existing)
                        .thenApply(HashSet::new));
    }

    public CompletableFuture<Set<RetrievedCapability>> getChildren(NetworkAccess network,
                                                                   AbsoluteCapability us) {
        CompletableFuture<Set<RetrievedCapability>> childrenFuture = getDirectChildren(us, network);

        CompletableFuture<Optional<RetrievedCapability>> moreChildrenFuture = getNextChunk(us, network);

        return childrenFuture.thenCompose(children -> moreChildrenFuture.thenCompose(moreChildrenSource -> {
                    CompletableFuture<Set<RetrievedCapability>> moreChildren = moreChildrenSource
                            .map(d -> d.fileAccess.getChildren(network, d.capability))
                            .orElse(CompletableFuture.completedFuture(Collections.emptySet()));
                    return moreChildren.thenApply(moreRetrievedChildren -> {
                        Set<RetrievedCapability> results = Stream.concat(
                                children.stream(),
                                moreRetrievedChildren.stream())
                                .collect(Collectors.toSet());
                        return results;
                    });
                })
        );
    }

    public CompletableFuture<Set<RetrievedCapability>> getChildren(WriterData base,
                                                                   NetworkAccess network,
                                                                   AbsoluteCapability us) {
        CompletableFuture<Set<RetrievedCapability>> childrenFuture = getDirectChildren(base, us, network);

        CompletableFuture<Optional<RetrievedCapability>> moreChildrenFuture = getNextChunk(base, us, network);

        return childrenFuture.thenCompose(children -> moreChildrenFuture.thenCompose(moreChildrenSource -> {
                    CompletableFuture<Set<RetrievedCapability>> moreChildren = moreChildrenSource
                            .map(d -> d.fileAccess.getChildren(base, network, d.capability))
                            .orElse(CompletableFuture.completedFuture(Collections.emptySet()));
                    return moreChildren.thenApply(moreRetrievedChildren -> {
                        Set<RetrievedCapability> results = Stream.concat(
                                children.stream(),
                                moreRetrievedChildren.stream())
                                .collect(Collectors.toSet());
                        return results;
                    });
                })
        );
    }

    public CompletableFuture<? extends CryptreeNode> updateProperties(WritableAbsoluteCapability us,
                                                                      Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                                      FileProperties newProps,
                                                                      NetworkAccess network) {

        SymmetricKey parentKey = getParentKey(us.rBaseKey);
        FromParent parentBlock = getParentBlock(parentKey);
        FromParent newParentBlock = new FromParent(parentBlock.parentLink, newProps);
        CryptreeNode updated = new CryptreeNode(lastCommittedHash, isDirectory, fromBaseKey, childrenOrData,
                PaddedCipherText.build(parentKey, newParentBlock, META_DATA_PADDING_BLOCKSIZE));
        return IpfsTransaction.call(us.owner,
                tid -> network.uploadChunk(updated, us.owner, us.getMapKey(), getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter), tid)
                        .thenApply(b -> updated),
                network.dhtClient);

    }

    public CompletableFuture<Snapshot> updateProperties(Snapshot base,
                                                        WriteSynchronizer.Committer committer,
                                                        WritableAbsoluteCapability us,
                                                        Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                        FileProperties newProps,
                                                        NetworkAccess network) {

        SymmetricKey parentKey = getParentKey(us.rBaseKey);
        FromParent parentBlock = getParentBlock(parentKey);
        FromParent newParentBlock = new FromParent(parentBlock.parentLink, newProps);
        CryptreeNode updated = new CryptreeNode(lastCommittedHash, isDirectory, fromBaseKey, childrenOrData,
                PaddedCipherText.build(parentKey, newParentBlock, META_DATA_PADDING_BLOCKSIZE));
        return IpfsTransaction.call(us.owner,
                tid -> network.uploadChunk(base, committer, updated, us.owner, us.getMapKey(), getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter), tid),
                network.dhtClient);
    }

    public boolean isDirty(SymmetricKey baseKey) {
        if (isDirectory())
            return false;
        return getBaseBlock(baseKey).parentOrData.isDirty();
    }

    public CryptreeNode withHash(Multihash hash) {
        return new CryptreeNode(MaybeMultihash.of(hash), isDirectory, fromBaseKey, childrenOrData, fromParentKey);
    }

    public CryptreeNode withWriterLink(SymmetricKey baseKey, SymmetricLinkToSigner newWriterLink) {
        return withWriterLink(baseKey, Optional.of(newWriterLink));
    }

    public CryptreeNode withWriterLink(SymmetricKey baseKey, Optional<SymmetricLinkToSigner> newWriterLink) {
        FromBase baseBlock = getBaseBlock(baseKey);
        FromBase newBaseBlock = new FromBase(baseBlock.parentOrData, newWriterLink, baseBlock.nextChunk);
        PaddedCipherText encryptedBaseBlock = PaddedCipherText.build(baseKey, newBaseBlock, BASE_BLOCK_PADDING_BLOCKSIZE);
        return new CryptreeNode(lastCommittedHash, isDirectory, encryptedBaseBlock, childrenOrData, fromParentKey);
    }

    public DirAndChildren withChildren(SymmetricKey baseKey, ChildrenLinks children, Hasher hasher) {
        Pair<FragmentedPaddedCipherText, List<FragmentWithHash>> encryptedChildren = buildChildren(children, baseKey, hasher);
        CryptreeNode cryptreeNode = new CryptreeNode(lastCommittedHash, isDirectory, fromBaseKey, encryptedChildren.left, fromParentKey);
        return new DirAndChildren(cryptreeNode, encryptedChildren.right);
    }

    private static Pair<FragmentedPaddedCipherText, List<FragmentWithHash>> buildChildren(ChildrenLinks children, SymmetricKey rBaseKey, Hasher hasher) {
        return FragmentedPaddedCipherText.build(rBaseKey, children, MIN_FRAGMENT_SIZE, Fragment.MAX_LENGTH, hasher);
    }

    public <T> CompletableFuture<T> getLinkedData(SymmetricKey baseOrDataKey,
                                                  Function<CborObject, T> fromCbor,
                                                  NetworkAccess network,
                                                  ProgressConsumer<Long> progress) {
        return childrenOrData.getAndDecrypt(baseOrDataKey, fromCbor, network, progress);
    }

    public CryptreeNode withParentLink(SymmetricKey parentKey, RelativeCapability newParentLink) {
        FromParent parentBlock = getParentBlock(parentKey);
        FromParent newParentBlock = new FromParent(Optional.of(newParentLink), parentBlock.properties);
        return new CryptreeNode(lastCommittedHash, isDirectory, fromBaseKey, childrenOrData,
                PaddedCipherText.build(parentKey, newParentBlock, META_DATA_PADDING_BLOCKSIZE));
    }

    /**
     *
     * @param rBaseKey
     * @return the mapkey of the next chunk of this file or folder if present
     */
    public byte[] getNextChunkLocation(SymmetricKey rBaseKey) {
        return getBaseBlock(rBaseKey).nextChunk.getMapKey();
    }

    public CompletableFuture<Optional<RetrievedCapability>> getNextChunk(AbsoluteCapability us, NetworkAccess network) {
        return network.retrieveMetadata(us.withMapKey(getNextChunkLocation(us.rBaseKey)));
    }

    public CompletableFuture<Optional<RetrievedCapability>> getNextChunk(WriterData base, AbsoluteCapability us, NetworkAccess network) {
        AbsoluteCapability nextChunkCap = us.withMapKey(getNextChunkLocation(us.rBaseKey));
        return network.getMetadata(base, nextChunkCap)
                .thenApply(faOpt -> faOpt.map(fa -> new RetrievedCapability(nextChunkCap, fa)));
    }

    public CompletableFuture<CryptreeNode> rotateBaseReadKey(WritableAbsoluteCapability us,
                                                             Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                             RelativeCapability toParent,
                                                             SymmetricKey newBaseKey,
                                                             NetworkAccess network) {
        if (isDirectory())
            throw new IllegalStateException("Invalid operation for directory!");
        // keep the same data key, just marked as dirty
        SymmetricKey dataKey = this.getDataKey(us.rBaseKey).makeDirty();

        RelativeCapability nextChunk = RelativeCapability.buildSubsequentChunk(getNextChunkLocation(us.rBaseKey), newBaseKey);
        Optional<SymmetricLinkToSigner> linkToSigner = getBaseBlock(us.rBaseKey).signer;
        CryptreeNode fa = CryptreeNode.createFile(committedHash(), linkToSigner, newBaseKey, dataKey, getProperties(us.rBaseKey),
                childrenOrData, toParent, nextChunk);
        SigningPrivateKeyAndPublicHash signer = getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter);
        return IpfsTransaction.call(us.owner, tid -> network.uploadChunk(fa, us.owner, us.getMapKey(), signer, tid)
                        .thenApply(x -> fa),
                network.dhtClient);
    }

    public CompletableFuture<CryptreeNode> rotateBaseWriteKey(WritableAbsoluteCapability us,
                                                              Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                              SymmetricKey newBaseWriteKey,
                                                              NetworkAccess network) {
        FromBase baseBlock = getBaseBlock(us.rBaseKey);
        if (! baseBlock.signer.isPresent())
            return CompletableFuture.completedFuture(this);

        SigningPrivateKeyAndPublicHash signer = baseBlock.signer.get().target(us.wBaseKey.get());
        CryptreeNode fa = this.withWriterLink(us.rBaseKey, SymmetricLinkToSigner.fromPair(newBaseWriteKey, signer));
        return IpfsTransaction.call(us.owner, tid ->
                        network.uploadChunk(fa, us.owner, us.getMapKey(), getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter), tid)
                                .thenApply(x -> fa),
                network.dhtClient);
    }

    public CompletableFuture<Snapshot> cleanAndCommit(Snapshot current,
                                                      WriteSynchronizer.Committer committer,
                                                      WritableAbsoluteCapability cap,
                                                      SigningPrivateKeyAndPublicHash writer,
                                                      SymmetricKey newDataKey,
                                                      Location parentLocation,
                                                      SymmetricKey parentParentKey,
                                                      NetworkAccess network,
                                                      SafeRandom random,
                                                      Hasher hasher) {
        FileProperties props = getProperties(cap.rBaseKey);
        AbsoluteCapability nextCap = cap.withMapKey(getNextChunkLocation(cap.rBaseKey));
        return retriever(cap.rBaseKey).getFile(current.get(writer).props, network, random, cap, props.size, committedHash(), x -> {})
                .thenCompose(data -> {
                    int chunkSize = (int) Math.min(props.size, Chunk.MAX_SIZE);
                    byte[] chunkData = new byte[chunkSize];
                    return data.readIntoArray(chunkData, 0, chunkSize)
                            .thenCompose(read -> {
                                byte[] nonce = cap.rBaseKey.createNonce();
                                byte[] mapKey = cap.getMapKey();

                                Chunk chunk = new Chunk(chunkData, newDataKey, mapKey, nonce);
                                LocatedChunk locatedChunk = new LocatedChunk(cap.getLocation(), lastCommittedHash, chunk);
                                return FileUploader.uploadChunk(current, committer, writer, props, parentLocation,
                                        parentParentKey, cap.rBaseKey, locatedChunk,
                                        nextCap.getLocation(), getWriterLink(cap.rBaseKey), hasher, network, x -> {
                                        });
                            });
                }).thenCompose(updated -> network.getMetadata(updated.get(nextCap.writer).props, nextCap)
                        .thenCompose(mOpt -> {
                            if (! mOpt.isPresent())
                                return CompletableFuture.completedFuture(updated);
                            return mOpt.get().cleanAndCommit(updated, committer, cap.withMapKey(nextCap.getMapKey()),
                                    writer, newDataKey, parentLocation, parentParentKey, network, random, hasher);
                        }));
    }

    public CompletableFuture<CryptreeNode> addChildAndCommit(RelativeCapability targetCAP,
                                                          WritableAbsoluteCapability us,
                                                          Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                          NetworkAccess network,
                                                          SafeRandom random,
                                                          Hasher hasher) {
        return addChildrenAndCommit(Arrays.asList(targetCAP), us, entryWriter, network, random, hasher);
    }

    public CompletableFuture<Snapshot> addChildAndCommit(Snapshot current,
                                                         WriteSynchronizer.Committer committer,
                                                         RelativeCapability targetCAP,
                                                         WritableAbsoluteCapability us,
                                                         Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                         NetworkAccess network,
                                                         SafeRandom random,
                                                         Hasher hasher) {
        return addChildrenAndCommit(current, committer, Arrays.asList(targetCAP), us, entryWriter, network, random, hasher);
    }

    public CompletableFuture<CryptreeNode> addChildrenAndCommit(List<RelativeCapability> targetCAPs,
                                                                WritableAbsoluteCapability us,
                                                                Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                                NetworkAccess network,
                                                                SafeRandom random,
                                                                Hasher hasher) {
        // Make sure subsequent blobs use a different transaction to obscure linkage of different parts of this dir
        return getDirectChildren(us.rBaseKey, network).thenCompose(children -> {
            if (children.size() + targetCAPs.size() > getMaxChildLinksPerBlob()) {
                return getNextChunk(us, network).thenCompose(nextMetablob -> {
                    if (nextMetablob.isPresent()) {
                        AbsoluteCapability nextPointer = nextMetablob.get().capability;
                        CryptreeNode nextBlob = nextMetablob.get().fileAccess;
                        return nextBlob.addChildrenAndCommit(targetCAPs,
                                nextPointer.toWritable(us.wBaseKey.get()), entryWriter, network, random, hasher);
                    } else {
                        // first fill this directory, then overflow into a new one
                        int freeSlots = getMaxChildLinksPerBlob() - children.size();
                        List<RelativeCapability> addToUs = targetCAPs.subList(0, freeSlots);
                        List<RelativeCapability> addToNext = targetCAPs.subList(freeSlots, targetCAPs.size());
                        return addChildrenAndCommit(addToUs, us, entryWriter, network, random, hasher)
                                .thenCompose(newUs -> {
                                    // create and upload new metadata blob
                                    SymmetricKey nextSubfoldersKey = us.rBaseKey;
                                    SymmetricKey ourParentKey = getParentKey(us.rBaseKey);
                                    Optional<RelativeCapability> parentCap = getParentBlock(ourParentKey).parentLink;
                                    RelativeCapability nextChunk = RelativeCapability.buildSubsequentChunk(random.randomBytes(32), nextSubfoldersKey);
                                    List<RelativeCapability> addToNextChunk = addToNext.stream()
                                            .limit(getMaxChildLinksPerBlob())
                                            .collect(Collectors.toList());
                                    List<RelativeCapability> remaining = addToNext.stream()
                                            .skip(getMaxChildLinksPerBlob())
                                            .collect(Collectors.toList());
                                    DirAndChildren next = CryptreeNode.createDir(MaybeMultihash.empty(), nextSubfoldersKey,
                                            null, Optional.empty(), FileProperties.EMPTY, parentCap,
                                            ourParentKey, nextChunk, new ChildrenLinks(addToNextChunk), hasher);
                                    byte[] nextMapKey = getNextChunkLocation(us.rBaseKey);
                                    WritableAbsoluteCapability nextPointer = new WritableAbsoluteCapability(us.owner,
                                            us.writer, nextMapKey, nextSubfoldersKey, us.wBaseKey.get());
                                    return IpfsTransaction.call(us.owner,
                                            tid -> next.commit(nextPointer, entryWriter, network, tid)
                                                    .thenCompose(nextBlob -> nextBlob.addChildrenAndCommit(remaining,
                                                            nextPointer, entryWriter, network, random, hasher))
                                                    .thenApply(nextBlob -> newUs), network.dhtClient);
                                });
                    }
                });
            } else {
                ArrayList<RelativeCapability> newFiles = new ArrayList<>(children);
                newFiles.addAll(targetCAPs);

                return IpfsTransaction.call(us.owner,
                        tid -> withChildren(us.rBaseKey, new ChildrenLinks(newFiles), hasher)
                                .commit(us, entryWriter, network, tid), network.dhtClient);
            }
        });
    }

    public CompletableFuture<Snapshot> addChildrenAndCommit(Snapshot current,
                                                            WriteSynchronizer.Committer committer,
                                                            List<RelativeCapability> targetCAPs,
                                                            WritableAbsoluteCapability us,
                                                            Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                            NetworkAccess network,
                                                            SafeRandom random,
                                                            Hasher hasher) {
        // Make sure subsequent blobs use a different transaction to obscure linkage of different parts of this dir
        return getDirectChildren(us.rBaseKey, network).thenCompose(children -> {
            if (children.size() + targetCAPs.size() > getMaxChildLinksPerBlob()) {
                return getNextChunk(current.get(us.writer).props, us, network).thenCompose(nextMetablob -> {
                    if (nextMetablob.isPresent()) {
                        AbsoluteCapability nextPointer = nextMetablob.get().capability;
                        CryptreeNode nextBlob = nextMetablob.get().fileAccess;
                        return nextBlob.addChildrenAndCommit(current, committer, targetCAPs,
                                nextPointer.toWritable(us.wBaseKey.get()), entryWriter, network, random, hasher);
                    } else {
                        // first fill this directory, then overflow into a new one
                        int freeSlots = getMaxChildLinksPerBlob() - children.size();
                        List<RelativeCapability> addToUs = targetCAPs.subList(0, freeSlots);
                        List<RelativeCapability> addToNext = targetCAPs.subList(freeSlots, targetCAPs.size());
                        return (addToUs.isEmpty() ?
                                CompletableFuture.completedFuture(current) :
                                addChildrenAndCommit(current, committer, addToUs, us, entryWriter, network, random, hasher))
                                .thenCompose(newBase -> {
                                    // create and upload new metadata blob
                                    SymmetricKey nextSubfoldersKey = us.rBaseKey;
                                    SymmetricKey ourParentKey = getParentKey(us.rBaseKey);
                                    Optional<RelativeCapability> parentCap = getParentBlock(ourParentKey).parentLink;
                                    RelativeCapability nextChunk = RelativeCapability.buildSubsequentChunk(random.randomBytes(32), nextSubfoldersKey);
                                    List<RelativeCapability> addToNextChunk = addToNext.stream()
                                            .limit(getMaxChildLinksPerBlob())
                                            .collect(Collectors.toList());
                                    List<RelativeCapability> remaining = addToNext.stream()
                                            .skip(getMaxChildLinksPerBlob())
                                            .collect(Collectors.toList());
                                    DirAndChildren next = CryptreeNode.createDir(MaybeMultihash.empty(), nextSubfoldersKey,
                                            null, Optional.empty(), FileProperties.EMPTY, parentCap,
                                            ourParentKey, nextChunk, new ChildrenLinks(addToNextChunk), hasher);
                                    byte[] nextMapKey = getNextChunkLocation(us.rBaseKey);
                                    WritableAbsoluteCapability nextPointer = new WritableAbsoluteCapability(us.owner,
                                            us.writer, nextMapKey, nextSubfoldersKey, us.wBaseKey.get());
                                    return IpfsTransaction.call(us.owner,
                                            tid -> next.commit(newBase, committer, nextPointer, entryWriter, network, tid)
                                                    .thenCompose(updatedBase ->
                                                            network.getMetadata(updatedBase.get(nextPointer.writer).props, nextPointer)
                                                            .thenCompose(nextOpt -> nextOpt.get().
                                                                    addChildrenAndCommit(updatedBase, committer, remaining,
                                                                            nextPointer, entryWriter, network, random, hasher)))
                                            , network.dhtClient);
                                });
                    }
                });
            } else {
                ArrayList<RelativeCapability> newFiles = new ArrayList<>(children);
                newFiles.addAll(targetCAPs);

                return IpfsTransaction.call(us.owner,
                        tid -> withChildren(us.rBaseKey, new ChildrenLinks(newFiles), hasher)
                                .commit(current, committer, us, entryWriter, network, tid), network.dhtClient);
            }
        });
    }

    // returns pointer to new child directory
    public CompletableFuture<Snapshot> mkdir(Snapshot base,
                                             WriteSynchronizer.Committer committer,
                                             String name,
                                             NetworkAccess network,
                                             WritableAbsoluteCapability us,
                                             Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                             SymmetricKey optionalBaseKey,
                                             boolean isSystemFolder,
                                             SafeRandom random,
                                             Hasher hasher) {
        SymmetricKey dirReadKey = optionalBaseKey != null ? optionalBaseKey : SymmetricKey.random();
        SymmetricKey dirWriteKey = SymmetricKey.random();
        byte[] dirMapKey = random.randomBytes(32); // root will be stored under this in the tree
        SymmetricKey ourParentKey = this.getParentKey(us.rBaseKey);
        RelativeCapability ourCap = new RelativeCapability(us.getMapKey(), ourParentKey, null);
        RelativeCapability nextChunk = new RelativeCapability(Optional.empty(), random.randomBytes(32), dirReadKey, Optional.empty());
        WritableAbsoluteCapability childCap = us.withBaseKey(dirReadKey).withBaseWriteKey(dirWriteKey).withMapKey(dirMapKey);
        DirAndChildren child = CryptreeNode.createDir(MaybeMultihash.empty(), dirReadKey, dirWriteKey, Optional.empty(),
                new FileProperties(name, true, "", 0, LocalDateTime.now(), isSystemFolder,
                        Optional.empty()), Optional.of(ourCap), SymmetricKey.random(), nextChunk, hasher);

        SymmetricLink toChildWriteKey = SymmetricLink.fromPair(us.wBaseKey.get(), dirWriteKey);
        // Use two transactions to not expose the child linkage
        return IpfsTransaction.call(us.owner,
                tid -> child.commit(base, committer, childCap, entryWriter, network, tid), network.dhtClient)
                .thenCompose(updatedBase -> {
                    RelativeCapability subdirPointer = new RelativeCapability(dirMapKey, dirReadKey, toChildWriteKey);
                    return addChildAndCommit(updatedBase, committer, subdirPointer, us, entryWriter, network, random, hasher);
                });
    }

    public CompletableFuture<Snapshot> copyTo(Snapshot base,
                                              WriteSynchronizer.Committer committer,
                                              AbsoluteCapability us,
                                              SymmetricKey newReadBaseKey,
                                              WritableAbsoluteCapability newParentCap,
                                              Optional<SigningPrivateKeyAndPublicHash> newEntryWriter,
                                              SymmetricKey parentparentKey,
                                              byte[] newMapKey,
                                              NetworkAccess network,
                                              SafeRandom random,
                                              Hasher hasher) {
        if (! isDirectory) {
            throw new IllegalStateException("Copy to only valid for directories!");
        }
        SymmetricKey newWriteBaseKey = SymmetricKey.random();
        SymmetricKey parentKey = getParentKey(us.rBaseKey);
        FileProperties props = getProperties(parentKey);
        Optional<SigningPrivateKeyAndPublicHash> newSigner = Optional.empty();
        RelativeCapability nextChunk = new RelativeCapability(Optional.empty(), random.randomBytes(32), newReadBaseKey, Optional.empty());
        RelativeCapability parentLink = new RelativeCapability(Optional.empty(), newParentCap.getMapKey(), parentparentKey, Optional.empty());
        DirAndChildren dirWithLinked = CryptreeNode.createDir(MaybeMultihash.empty(), newReadBaseKey, newWriteBaseKey, newSigner, props,
                Optional.of(parentLink), parentKey, nextChunk, hasher);
        CryptreeNode da = dirWithLinked.dir;
        SymmetricKey ourNewParentKey = da.getParentKey(newReadBaseKey);
        WritableAbsoluteCapability ourNewCap = new WritableAbsoluteCapability(newParentCap.owner, newParentCap.writer,
                newMapKey, newReadBaseKey, newWriteBaseKey);

        return base.withWriter(us.owner, us.writer, network)
                .thenCompose(snapshot -> this.getChildren(snapshot.get(us.writer).props, network, us).thenCompose(RFPs -> {
                    // upload new metadata blob for each child and re-add child
                    CompletableFuture<Pair<CryptreeNode, Snapshot>> reduce = RFPs.stream()
                            .reduce(CompletableFuture.completedFuture(new Pair<>(da, snapshot)),
                                    (dirFuture, rfp) -> {
                                        return dirFuture.thenCompose(pair -> {
                                            SymmetricKey newChildReadKey = rfp.fileAccess.isDirectory() ?
                                                    SymmetricKey.random() :
                                                    rfp.capability.rBaseKey;
                                            SymmetricKey newChildWriteKey = SymmetricKey.random();
                                            byte[] newChildMapKey = random.randomBytes(32);
                                            WritableAbsoluteCapability newChildCap = new WritableAbsoluteCapability(ourNewCap.owner,
                                                    ourNewCap.writer, newChildMapKey, newChildReadKey, newChildWriteKey);
                                            return rfp.fileAccess.copyTo(pair.right, committer, rfp.capability, newChildReadKey,
                                                    ourNewCap, newEntryWriter, ourNewParentKey, newChildMapKey, network, random, hasher)
                                                    .thenCompose(updatedBase -> pair.left.addChildAndCommit(updatedBase, committer,
                                                            ourNewCap.relativise(newChildCap), ourNewCap, newEntryWriter,
                                                            network, random, hasher)
                                                            .thenCompose(state -> network.getMetadata(state.get(ourNewCap.writer).props, ourNewCap)
                                                                    .thenApply(updatedDir -> new Pair<>(updatedDir.get(), state)))
                                                    );
                                        });
                                    }, (a, b) -> a.thenCompose(x -> b));
                    return reduce;
                })).thenCompose(finalPair -> IpfsTransaction.call(newParentCap.owner,
                        tid -> finalPair.left.commit(finalPair.right, committer, ourNewCap, newEntryWriter, network, tid),
                        network.dhtClient));
    }

    public CompletableFuture<CryptreeNode> updateChildLink(WritableAbsoluteCapability ourPointer,
                                                           Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                           RetrievedCapability original,
                                                           RetrievedCapability modified,
                                                           NetworkAccess network,
                                                           SafeRandom random,
                                                           Hasher hasher) {
        return removeChildren(Arrays.asList(original), ourPointer, entryWriter, network, hasher)
                .thenCompose(res ->
                        res.addChildAndCommit(ourPointer.relativise(modified.capability), ourPointer, entryWriter,
                                network, random, hasher));
    }

    public CompletableFuture<CryptreeNode> updateChildLinks(WritableAbsoluteCapability ourPointer,
                                                            Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                            Collection<Pair<RetrievedCapability, RetrievedCapability>> childCasPairs,
                                                            NetworkAccess network,
                                                            SafeRandom random,
                                                            Hasher hasher) {
        return removeChildren(childCasPairs.stream()
                .map(p -> p.left)
                .collect(Collectors.toList()), ourPointer, entryWriter, network, hasher)
                .thenCompose(res -> res.addChildrenAndCommit(childCasPairs.stream()
                        .map(p -> ourPointer.relativise(p.right.capability))
                        .collect(Collectors.toList()), ourPointer, entryWriter, network, random, hasher));
    }

    public CompletableFuture<CryptreeNode> removeChildren(List<RetrievedCapability> childrenToRemove,
                                                          WritableAbsoluteCapability ourPointer,
                                                          Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                          NetworkAccess network,
                                                          Hasher hasher) {
        Set<Location> locsToRemove = childrenToRemove.stream()
                .map(r -> r.capability.getLocation())
                .collect(Collectors.toSet());
        return getDirectChildren(ourPointer.rBaseKey, network).thenCompose(newSubfolders -> {
            List<RelativeCapability> withRemoval = newSubfolders.stream()
                    .filter(e -> !locsToRemove.contains(e.toAbsolute(ourPointer).getLocation()))
                    .collect(Collectors.toList());

            return IpfsTransaction.call(ourPointer.owner,
                    tid -> withChildren(ourPointer.rBaseKey, new ChildrenLinks(withRemoval), hasher)
                            .commit(ourPointer, entryWriter, network, tid),
                    network.dhtClient);
        });
    }

    public CompletableFuture<Snapshot> removeChildren(Snapshot current,
                                                      WriteSynchronizer.Committer committer,
                                                      List<RetrievedCapability> childrenToRemove,
                                                      WritableAbsoluteCapability ourPointer,
                                                      Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                      NetworkAccess network,
                                                      Hasher hasher) {
        Set<Location> locsToRemove = childrenToRemove.stream()
                .map(r -> r.capability.getLocation())
                .collect(Collectors.toSet());
        return getDirectChildren(current.get(ourPointer.writer).props, ourPointer, network).thenCompose(children -> {
            List<RelativeCapability> withRemoval = children.stream()
                    .filter(e -> ! locsToRemove.contains(e.capability.getLocation()))
                    .map(c -> ourPointer.relativise(c.capability))
                    .collect(Collectors.toList());

            return IpfsTransaction.call(ourPointer.owner,
                    tid -> withChildren(ourPointer.rBaseKey, new ChildrenLinks(withRemoval), hasher)
                            .commit(current, committer, ourPointer, entryWriter, network, tid),
                    network.dhtClient);
        });
    }

    public CompletableFuture<CryptreeNode> commit(WritableAbsoluteCapability us,
                                                  Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                  NetworkAccess network,
                                                  TransactionId tid) {
        return network.uploadChunk(this, us.owner, us.getMapKey(), getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter), tid)
                .thenApply(this::withHash);
    }

    public CompletableFuture<Snapshot> commit(Snapshot current,
                                              WriteSynchronizer.Committer committer,
                                              WritableAbsoluteCapability us,
                                              Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                              NetworkAccess network,
                                              TransactionId tid) {
        return network.uploadChunk(current, committer, this, us.owner, us.getMapKey(), getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter), tid);
    }

    public boolean hasParentLink(SymmetricKey baseKey) {
        SymmetricKey parentKey = getParentKey(baseKey);
        return getParentBlock(parentKey).parentLink.isPresent();
    }

    public Optional<RelativeCapability> getParentCapability(SymmetricKey baseKey) {
        SymmetricKey parentKey = getParentKey(baseKey);
        return getParentBlock(parentKey).parentLink;
    }

    public CompletableFuture<RetrievedCapability> getParent(PublicKeyHash owner,
                                                            PublicKeyHash writer,
                                                            SymmetricKey baseKey,
                                                            NetworkAccess network) {
        SymmetricKey parentKey = getParentKey(baseKey);
        Optional<RelativeCapability> parentLink = getParentBlock(parentKey).parentLink;
        if (! parentLink.isPresent())
            return CompletableFuture.completedFuture(null);

        RelativeCapability relCap = parentLink.get();
        return network.retrieveMetadata(new AbsoluteCapability(owner, relCap.writer.orElse(writer), relCap.getMapKey(),
                relCap.rBaseKey, Optional.empty())).thenApply(res -> {
            RetrievedCapability retrievedCapability = res.get();
            return retrievedCapability;
        });
    }

    public static Pair<CryptreeNode, List<FragmentWithHash>> createFile(MaybeMultihash existingHash,
                                                                        SymmetricKey parentKey,
                                                                        SymmetricKey dataKey,
                                                                        FileProperties props,
                                                                        byte[] chunkData,
                                                                        Location parentLocation,
                                                                        SymmetricKey parentparentKey,
                                                                        RelativeCapability nextChunk,
                                                                        Hasher hasher) {
        Pair<FragmentedPaddedCipherText, List<FragmentWithHash>> linksAndData =
                FragmentedPaddedCipherText.build(dataKey, new CborObject.CborByteArray(chunkData),
                        MIN_FRAGMENT_SIZE, Fragment.MAX_LENGTH, hasher);
        RelativeCapability toParent = new RelativeCapability(Optional.empty(), parentLocation.getMapKey(),
                parentparentKey, Optional.empty());
        CryptreeNode cryptree = createFile(existingHash, Optional.empty(), parentKey, dataKey, props,
                linksAndData.left, toParent, nextChunk);
        return new Pair<>(cryptree, linksAndData.right);
    }

    public static CryptreeNode createFile(MaybeMultihash existingHash,
                                          Optional<SymmetricLinkToSigner> signerLink,
                                          SymmetricKey parentKey,
                                          SymmetricKey dataKey,
                                          FileProperties props,
                                          FragmentedPaddedCipherText data,
                                          RelativeCapability toParentDir,
                                          RelativeCapability nextChunk) {
        if (parentKey.equals(dataKey))
            throw new IllegalStateException("A file's base key and data key must be different!");
        FromBase fromBase = new FromBase(dataKey, signerLink, nextChunk);
        FromParent fromParent = new FromParent(Optional.of(toParentDir), props);

        PaddedCipherText encryptedBaseBlock = PaddedCipherText.build(parentKey, fromBase, BASE_BLOCK_PADDING_BLOCKSIZE);
        PaddedCipherText encryptedParentBlock = PaddedCipherText.build(parentKey, fromParent, META_DATA_PADDING_BLOCKSIZE);
        return new CryptreeNode(existingHash, false, encryptedBaseBlock, data, encryptedParentBlock);
    }

    public static DirAndChildren createDir(MaybeMultihash lastCommittedHash,
                                           SymmetricKey rBaseKey,
                                           SymmetricKey wBaseKey,
                                           Optional<SigningPrivateKeyAndPublicHash> signingPair,
                                           FileProperties props,
                                           Optional<RelativeCapability> parentCap,
                                           SymmetricKey parentKey,
                                           RelativeCapability nextChunk,
                                           Hasher hasher) {
        return createDir(lastCommittedHash, rBaseKey, wBaseKey, signingPair, props, parentCap, parentKey, nextChunk,
                new ChildrenLinks(Collections.emptyList()), hasher);
    }

    public static DirAndChildren createDir(MaybeMultihash lastCommittedHash,
                                           SymmetricKey rBaseKey,
                                           SymmetricKey wBaseKey,
                                           Optional<SigningPrivateKeyAndPublicHash> signingPair,
                                           FileProperties props,
                                           Optional<RelativeCapability> parentCap,
                                           SymmetricKey parentKey,
                                           RelativeCapability nextChunk,
                                           ChildrenLinks children,
                                           Hasher hasher) {
        if (rBaseKey.equals(parentKey))
            throw new IllegalStateException("A directory's base key and parent key must be different!");
        Optional<SymmetricLinkToSigner> writerLink = signingPair.map(pair -> SymmetricLinkToSigner.fromPair(wBaseKey, pair));
        FromBase fromBase = new FromBase(parentKey, writerLink, nextChunk);
        FromParent fromParent = new FromParent(parentCap, props);

        PaddedCipherText encryptedBaseBlock = PaddedCipherText.build(rBaseKey, fromBase, BASE_BLOCK_PADDING_BLOCKSIZE);
        PaddedCipherText encryptedParentBlock = PaddedCipherText.build(parentKey, fromParent, META_DATA_PADDING_BLOCKSIZE);
        Pair<FragmentedPaddedCipherText, List<FragmentWithHash>> linksAndData =
                FragmentedPaddedCipherText.build(rBaseKey, children, MIN_FRAGMENT_SIZE, Fragment.MAX_LENGTH, hasher);
        CryptreeNode metadata = new CryptreeNode(lastCommittedHash, true, encryptedBaseBlock, linksAndData.left, encryptedParentBlock);
        return new DirAndChildren(metadata, linksAndData.right);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("v", new CborObject.CborLong(getVersion()));
        state.put("b", fromBaseKey);
        state.put("p", fromParentKey);
        state.put("d", childrenOrData);
        return CborObject.CborMap.build(state);
    }

    public static CryptreeNode fromCbor(CborObject cbor, SymmetricKey base, Multihash hash) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for CryptreeNode: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        int version = (int) m.getLong("v");
        if (version != CURRENT_VERSION)
            throw new IllegalStateException("Unknown cryptree version: " + version);

        PaddedCipherText fromBaseKey = m.get("b", PaddedCipherText::fromCbor);
        PaddedCipherText fromParentKey = m.get("p", PaddedCipherText::fromCbor);
        FragmentedPaddedCipherText childrenOrData = m.get("d", FragmentedPaddedCipherText::fromCbor);

        boolean isDirectory;
        try {
            // For a file the base key is the parent key
            isDirectory = fromParentKey.decrypt(base, FromParent::fromCbor).properties.isDirectory;
        } catch (Throwable t) {
            isDirectory = true;
        }
        return new CryptreeNode(MaybeMultihash.of(hash), isDirectory, fromBaseKey, childrenOrData, fromParentKey);
    }
}
