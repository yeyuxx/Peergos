
package defiance.tests;

import static org.junit.Assert.*;

import defiance.crypto.*;
import defiance.corenode.*;
import defiance.util.ByteArrayWrapper;

import org.junit.Test;

import java.util.*;
import java.io.*;

import java.security.*;
import java.security.cert.*;

public class CoreNode
{
    class MockCoreNode extends AbstractCoreNode
    {
        MockCoreNode() throws Exception {}
    }

    @Test public void test() throws Exception 
    {
        Random random = new Random(666);

        MockCoreNode coreNode = new MockCoreNode();

        //
        //generate key-pair & signed cert
        //
        KeyPair keyPair = SSL.generateKeyPair();
        UserPublicKey userPublicKey = new UserPublicKey(keyPair.getPublic());
        User user = new User(keyPair);
        String username = "USER";

        UserPublicKey userPrivateKey = new UserPublicKey(keyPair.getPrivate());
        byte[] hash = userPrivateKey.hash(userPublicKey.getPublicKey());
        byte[] signedHash = userPrivateKey.encryptMessageFor(userPrivateKey.hash(username.getBytes())); 
        //
        //add to coreNode
        //
        boolean userAdded = coreNode.addUsername(userPublicKey.getPublicKey(), signedHash,username);

        assertTrue("successfully added user", userAdded);

        //
        //generate some test data
        //
        byte[] fragmentData = new byte[500];
        random.nextBytes(fragmentData);
        byte[] cipherText = userPublicKey.encryptMessageFor(fragmentData);

        //
        //add to user 
        //
        boolean fragmentAdded = coreNode.addFragment(userPublicKey.getPublicKey(), userPrivateKey.encryptMessageFor(userPublicKey.hash(cipherText)), cipherText);
        assertTrue("successfully added fragment", fragmentAdded);

        byte[] encoded = userPublicKey.getPublicKey();
        Iterator<ByteArrayWrapper> userFragments = coreNode.getFragments(encoded, userPrivateKey.encryptMessageFor(userPublicKey.hash(encoded)));
        
        assertTrue("found fragments", userFragments != null);
        //
        //get back message
        //
        boolean foundFragment = false;
        if (userFragments != null)
            while(userFragments.hasNext())
            {
                byte[] plainText = user.decryptMessage(userFragments.next().data);
                if (Arrays.equals(plainText, fragmentData))
                    foundFragment = true;
            }
        assertTrue("successfully found fragment", foundFragment);

        //
        //create a friend
        //
        KeyPair friendKeyPair = SSL.generateKeyPair();
        UserPublicKey friendPublicKey = new UserPublicKey(friendKeyPair.getPublic());
        UserPublicKey friendPrivateKey = new UserPublicKey(friendKeyPair.getPrivate());
        User friend = new User(friendKeyPair);
        String friendname = "FRIEND";

        //
        //add friend to corenode
        //
        boolean friendAdded = coreNode.addUsername(friendPublicKey.getPublicKey(), new byte[0],friendname);

        assertFalse("successfully failed validation test", friendAdded);

        friendAdded = coreNode.addUsername(friendPublicKey.getPublicKey(), friendPrivateKey.encryptMessageFor(friendPublicKey.hash(friendname)),friendname);
        assertTrue("successfully added friend", friendAdded);

        //
        //user adds friend to his friend list
        //
        byte[] encodedFriend = friendPublicKey.getPublicKey();
        boolean userAddedFriend = coreNode.addFriend(userPublicKey.getPublicKey(),  userPrivateKey.encryptMessageFor(userPublicKey.hash(encodedFriend)), encodedFriend);
        assertTrue("userA successfully added friend to friend-list", userAddedFriend);
        
        boolean hasFriend = coreNode.hasFriend(username, friendname);
        assertTrue("has friend", hasFriend);
        System.out.println("MADE IT");

    }



}
