package defiance.corenode;

import defiance.util.*;
import defiance.crypto.*;

import java.util.*;
import java.io.*;
import java.net.*;


public abstract class AbstractCoreNode
{
    /**
     * Maintains meta-data about fragments stored in the DHT,
     * the relationship between users and with whom user fragments
     * are shared.      
     */ 
    class UserData
    {
        private final Map<byte[], byte[]> friends;
        private final Map<byte[], byte[]> fragments;
        //fragments shared with this user
        private final Map<UserPublicKey, Map<byte[], byte[]> > sharedFragments;

        UserData()
        {
            this.friends = new HashMap<byte[], byte[]>();
            this.fragments = new HashMap<byte[], byte[]>();
            this.sharedFragments = new HashMap<UserPublicKey, Map<byte[], byte[]> >();
        }
    }   

    protected final Map<UserPublicKey, UserData> userMap;

    public AbstractCoreNode()
    {
        this.userMap = new HashMap<UserPublicKey, UserData>();
    } 

    /*
     * @param encodedPublicKey X509 encoded public key
     */
    public boolean addUsername(byte[] encodedPublicKey)
    {
        UserPublicKey key = new UserPublicKey(encodedPublicKey);
        synchronized(this)
        {
            if (userMap.containsKey(key))
                return false;
            userMap.put(key, new UserData());
            return true;
        }
    }

    /*
     * @param userKey X509 encoded key of user that wishes to add a friend
     * @param userBencodedkey the X509 encoded key of the new friend user, encoded with userKey
     */
    public boolean addFriend(byte[] userKey, byte[] userBencodedKey)
    {
        UserPublicKey key = new UserPublicKey(userKey);
        byte[] hash = key.hash(userBencodedKey);

        synchronized(this)
        {
            UserData userData = userMap.get(key);

            if (userData == null)
                return false;
            if (userData.friends.containsKey(hash))
                return false;

            userData.friends.put(hash, userBencodedKey);
            return true; 
        }
    }

    /*
     * @param userKey X509 encoded key of user that wishes to add a fragment
     * @param encodedFragmentData fragment meta-data encoded with userKey
     */ 
    public boolean addFragment(byte[] userKey, byte[] encodedFragmentData)
    {
        UserPublicKey key = new UserPublicKey(userKey);
        byte[] hash = key.hash(encodedFragmentData);

        synchronized(this)
        {
            UserData userData = userMap.get(key);

            if (userData == null)
                return false;

            if (userData.fragments.containsKey(hash))
                return false;
            userData.fragments.put(hash, encodedFragmentData);
            return true;
        }
    }

    /*
     * @param userKey X509 encoded key of user that wishes to add a fragment
     * @param hash the hash of the fragment to be removed 
     */ 
    public boolean removeFragment(byte[] userKey, byte[] hash)
    {
        UserPublicKey key = new UserPublicKey(userKey);
        synchronized(this)
        {
            UserData userData = userMap.get(key);

            if (userData == null)
                return false;
            return userData.fragments.remove(hash) != null;
        }
    }

    /*
     * @param userKey X509 encoded key of user that wishes to add a fragment
     */
    public boolean removeUserName(byte[] userKey)
    {
        UserPublicKey key = new UserPublicKey(userKey);
        synchronized(this)
        {
            return userMap.remove(key) != null;
        }
    }

    /*
     * @param sharerEncodedKey X509 encoded key of user that wishes to share a fragment 
     * @param recipientEncodedKey X509 encoded key of user that will receive the shared fragment 
     * @param encodedFragmentData the fragment data encoded with sharerEncodedKey 
     */
    public boolean addSharedFragment(byte[] sharerEncodedKey, byte[] recipientEncodedKey, byte[] encodedFragmentData) 
    {
        UserPublicKey sharerKey = new UserPublicKey(sharerEncodedKey);
        UserPublicKey recipientKey = new UserPublicKey(recipientEncodedKey);
        byte[] hash = recipientKey.hash(encodedFragmentData);

        synchronized(this)
        {
            UserData sharerUserData = userMap.get(sharerKey);
            UserData recipientUserData = userMap.get(recipientKey);

            if (sharerUserData == null || recipientUserData == null)
                return false;

            if (! recipientUserData.sharedFragments.containsKey(sharerKey))
                recipientUserData.sharedFragments.put(sharerKey, new HashMap<byte[], byte[]>());

            Map<byte[], byte[]> sharedFragments = recipientUserData.sharedFragments.get(sharerKey);
            if (sharedFragments.containsKey(hash))
                return false;
            sharedFragments.put(hash, encodedFragmentData);

            return true;
        }
    }



    /*
     * @param key X509 encoded key of user that wishes to share a fragment 
     */
    public byte[][] getFriends(byte[] key)
    {
        UserPublicKey userKey = new UserPublicKey(key);
        synchronized(this)
        {
            UserData userData = userMap.get(userKey);
            if (userData == null)
                return null;
            
            int numFriends = userData.friends.size();
            
            if (numFriends ==0)
                return null;

            byte[][] friends = new byte[numFriends][];
            
            int pos = 0;
            for (Iterator<byte[]> it = userData.friends.values().iterator(); it.hasNext();)
                friends[pos++] = it.next();
            return friends;
        }
    } 

    /*
     * @param key X509 encoded key of user that wishes to share a fragment 
     */
    public byte[][] getUserFragments(byte[] key)
    {
        UserPublicKey userKey = new UserPublicKey(key);
        synchronized(this)
        {
            UserData userData = userMap.get(userKey);
            if (userData == null)
                return null;

            int numFragments = userData.fragments.size();
            
            if (numFragments ==0)
                return null;

            byte[][] fragments = new byte[numFragments][];
            
            int pos = 0;
            for (Iterator<byte[]> it = userData.fragments.values().iterator(); it.hasNext();)
                fragments[pos++] = it.next();
            return fragments;
        }
    }

    /*
     * @param key X509 encoded key of user that wishes to share a fragment 
     */
    public byte[][] getSharedFragments(byte[] key)
    {
        UserPublicKey userKey = new UserPublicKey(key);
        synchronized(this)
        {
            UserData userData = userMap.get(userKey);
            if (userData == null)
                return null;
        }
        return null;
    }
}
