
package peergos.corenode;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.util.*;
import java.net.*;
import java.io.*;
import static peergos.corenode.HTTPCoreNodeServer.*;


public class HTTPCoreNode extends AbstractCoreNode
{
    private final URL coreNodeURL;

    public HTTPCoreNode(URL coreNodeURL)
    {
        this.coreNodeURL = coreNodeURL;
    }

    public URL getCoreNodeURL(){return coreNodeURL;}

    @Override public UserPublicKey getPublicKey(String username) 
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataInputStream din = new DataInputStream(conn.getInputStream());
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("getPublicKey", dout);
            Serialize.serialize(username, dout);
            dout.flush();
            
            byte[] publicKey = deserializeByteArray(din); 
            return new UserPublicKey(publicKey);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    @Override public String getUsername(byte[] publicKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("getUsername", dout);
            Serialize.serialize(publicKey, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            byte[] name = deserializeByteArray(din);
            return new String(name);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    @Override public boolean addUsername(String username, byte[] encodedUserKey, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            Serialize.serialize("addUsername", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedUserKey, dout);
            Serialize.serialize(signedHash, dout);
            dout.flush();
            
            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

   @Override public boolean followRequest(String target, byte[] encodedSharingPublicKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);

            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
        
            Serialize.serialize("followRequest", dout);
            Serialize.serialize(target, dout);
            Serialize.serialize(encodedSharingPublicKey, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public boolean removeFollowRequest(String target, byte[] data, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            Serialize.serialize("removeFollowRequest", dout);
            Serialize.serialize(target, dout);
            Serialize.serialize(data, dout);
            Serialize.serialize(signedHash, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   
   @Override public boolean allowSharingKey(String username, byte[] encodedSharingPublicKey, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("allowSharingKey", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedSharingPublicKey, dout);
            Serialize.serialize(signedHash, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public boolean banSharingKey(String username, byte[] encodedSharingPublicKey, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            Serialize.serialize("banSharingKey", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedSharingPublicKey, dout);
            Serialize.serialize(signedHash, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readBoolean();

        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public boolean addFragment(String username, byte[] encodedSharingPublicKey, byte[] mapKey, byte[] fragmentData, byte[] sharingKeySignedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("addFragment", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedSharingPublicKey, dout);
            Serialize.serialize(mapKey, dout);
            Serialize.serialize(fragmentData, dout);
            Serialize.serialize(sharingKeySignedHash, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public boolean removeFragment(String username, byte[] encodedSharingKey, byte[] mapKey, byte[] sharingKeySignedMapKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            Serialize.serialize("removeFragment", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedSharingKey, dout);
            Serialize.serialize(mapKey, dout);
            Serialize.serialize(sharingKeySignedMapKey, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public boolean removeUsername(String username, byte[] userKey, byte[] signedHash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            Serialize.serialize("removeUsername", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(userKey, dout);
            Serialize.serialize(signedHash, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public Iterator<UserPublicKey> getSharingKeys(String username)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());
            
            Serialize.serialize("getSharingKeys", dout);
            Serialize.serialize(username, dout);
            dout.flush();

            ArrayList<UserPublicKey> sharingKeys = new ArrayList<UserPublicKey>();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            int l = 0;
            while((l = din.readInt()) >=0)
            {
                byte[] b = getByteArray(l);
                din.readFully(b); 
                sharingKeys.add(new UserPublicKey(b));
            }
            return sharingKeys.iterator();

        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public ByteArrayWrapper getFragment(String username, byte[] encodedSharingKey, byte[] mapKey)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("getFragment", dout);
            Serialize.serialize(username, dout);
            Serialize.serialize(encodedSharingKey, dout);
            Serialize.serialize(mapKey, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            byte[] b = deserializeByteArray(din);
            return new ByteArrayWrapper(b);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public boolean registerFragment(String recipient, InetSocketAddress node, byte[] hash)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("registerFragment", dout);
            Serialize.serialize(recipient, dout);
            Serialize.serialize(node.getAddress().getAddress(), dout);
            dout.writeInt(node.getPort());
            Serialize.serialize(hash, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readBoolean();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public long getQuota(String user) 
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("getQuota", dout);
            Serialize.serialize(user, dout);
            dout.flush();
            
            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readLong();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return -1l;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public long getUsage(String username)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) coreNodeURL.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            Serialize.serialize("getUsage", dout);
            Serialize.serialize(username, dout);
            dout.flush();

            DataInputStream din = new DataInputStream(conn.getInputStream());
            return din.readLong();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return -1l;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
   @Override public void close()     
    {}
}
