
package defiance.tests;

import static org.junit.Assert.*;

import defiance.crypto.*;
import defiance.corenode.*;

import org.junit.Test;

import java.util.*;

import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;


public class CoreNode
{
    class MockCoreNode extends AbstractCoreNode{}
    
    @Test public void test() 
    {
        Random random = new Random(666);
        MockCoreNode coreNode = new MockCoreNode();

        //
        //generate key-pair
        //
        KeyPair keyPair = SSL.generateKeyPair();
        UserPublicKey userPublicKey = new UserPublicKey(keyPair.getPublic());
        User user = new User(keyPair);
        
        //
        //add to coreNode
        //
        boolean userAdded = coreNode.addUsername(userPublicKey.getPublicKey());
        
        assertTrue("successfully added user", userAdded);
        
        //
        //generate some test data
        //
        byte[] fragmentData = new byte[500];
        byte[] cipherText = userPublicKey.encryptMessageFor(fragmentData);
        
        //
        //add to user 
        //
        boolean fragmentAdded = coreNode.addFragment(userPublicKey.getPublicKey(), cipherText);
        assertTrue("successfully added fragment", fragmentAdded);

        byte[][] userFragments = coreNode.getUserFragments(userPublicKey.getPublicKey());
        //
        //get back message
        //
        boolean foundFragment = false;
        if (userFragments != null)
            for (byte[] fragmentDataCipherText: userFragments)
                {
                    byte[] plainText = user.decryptMessage(fragmentDataCipherText);
                    if (Arrays.hashCode(plainText) == Arrays.hashCode(fragmentData))
                        foundFragment = true;
                }
        assertTrue("successfully found fragment", foundFragment);
    }



}
