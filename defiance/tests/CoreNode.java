
package defiance.tests;

import static org.junit.Assert.*;

import defiance.crypto.*;
import defiance.corenode.*;

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
        byte[] signedHash = userPrivateKey.encryptMessageFor(hash); 
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
        boolean fragmentAdded = coreNode.addFragment(userPublicKey.getPublicKey(), signedHash, cipherText);
        assertTrue("successfully added fragment", fragmentAdded);

        Iterator<byte[]> userFragments = coreNode.getFragments(userPublicKey.getPublicKey(), signedHash);
        //
        //get back message
        //
        boolean foundFragment = false;
        if (userFragments != null)
            while(userFragments.hasNext())
            {
                byte[] plainText = user.decryptMessage(userFragments.next());
                if (Arrays.equals(plainText, fragmentData))
                    foundFragment = true;
            }
        assertTrue("successfully found fragment", foundFragment);

        //
        //create a friend
        //
        KeyPair friendKeyPair = SSL.generateKeyPair();
        UserPublicKey friendPublicKey = new UserPublicKey(friendKeyPair.getPublic());
        User friend = new User(friendKeyPair);
        String friendname = "FRIEND";

        UserPublicKey friendPrivateKey = new UserPublicKey(friendKeyPair.getPrivate());
        byte[] friendHash = friendPrivateKey.hash(friendPublicKey.getPublicKey());
        byte[] friendSignedHash = friendPrivateKey.encryptMessageFor(friendHash); 
        //
        //add friend to corenode
        //
        boolean friendAdded = coreNode.addUsername(friendPublicKey.getPublicKey(), signedHash,friendname);

        assertFalse("successfully failed validation test", friendAdded);

        friendAdded = coreNode.addUsername(friendPublicKey.getPublicKey(), friendSignedHash,friendname);
        assertTrue("successfully added friend", friendAdded);

        //
        //user adds friend to his friend list
        //
        boolean userAddedFriend = coreNode.addFriend(userPublicKey.getPublicKey(), signedHash, friendPublicKey.getPublicKey());
        assertTrue("userA successfully added friend to friend-list", userAddedFriend);
        
        boolean hasFriend = coreNode.hasFriend(username, friendname);
        assertTrue("has friend", hasFriend);


    }



}
