package peergos.server.tests.slow;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.tests.*;
import peergos.server.util.Args;
import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.net.*;
import java.util.*;
import java.util.stream.*;

@RunWith(Parameterized.class)
public class MkdirSpeed {

    private static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Crypto.initJava();

    private static Random random = new Random(RANDOM_SEED);

    public MkdirSpeed(String useIPFS, Random r) throws Exception {
        this.network = buildHttpNetworkAccess(useIPFS.equals("IPFS"), r);
    }

    private static NetworkAccess buildHttpNetworkAccess(boolean useIpfs, Random r) throws Exception {
        Args args = UserTests.buildArgs().with("useIPFS", "" + useIpfs);
        Main.PKI_INIT.main(args);
        return NetworkAccess.buildJava(new URL("http://localhost:" + args.getInt("port"))).get();
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
//                {"IPFS", new Random(0)}
                {"NOTIPFS", new Random(0)}
        });
    }

    private String generateUsername() {
        return "test" + (random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return UserContext.ensureSignedUp(username, password, network, crypto).get();
    }

    // (0 - 100 mkdirs)
    // All ram, not http =>  40 -   54 ms
    // All ram, http     => 400 -  416 ms
    // IPFS, http        => 660 -  840 ms
    //
    // current baseline: MKDIR(99) duration: 1106 mS, best: 867 mS, worst: 1467 mS, av: 1063 mS
    @Test
    public void hugeFolder() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();
        List<String> names = new ArrayList<>();
        IntStream.range(0, 100).forEach(i -> names.add(randomString()));

        long worst = 0, best = Long.MAX_VALUE, start = System.currentTimeMillis();
        for (int i=0; i < names.size(); i++) {
            String filename = names.get(i);
            long t1 = System.currentTimeMillis();
            userRoot = userRoot.mkdir(filename, context.network, false, crypto.random, crypto.hasher).join();
            long duration = System.currentTimeMillis() - t1;
            worst = Math.max(worst, duration);
            best = Math.min(best, duration);
            System.err.printf("MKDIR(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i, duration, best, worst, (t1 + duration - start) / (i + 1));
        }
    }

    private static String randomString() {
        return UUID.randomUUID().toString();
    }
}
