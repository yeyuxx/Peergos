package defiance.dht;

import defiance.util.*;
import defiance.crypto.PublicKey;

import java.util.*;
import java.io.*;
import java.net.*;

abstract class AbstractCoreNode
{
    /**
     * Maintains meta-data about fragments stored in the DHT,
     * the relationship between users and with whom user fragments
     * are shared.      
     */ 

    class FragmentData 
    {
        private final String owner;
        private final byte[] key;
        private final InetSocketAddress address;

        FragmentData(String owner, byte[] key, InetSocketAddress address)
        {
            this.owner = owner;
            this.key = key;
            this.address = address;
        }

        public int hashCode(){return key.hashCode() + address.hashCode() * 31 + owner.hashCode()*31*31;}

        public boolean equals(Object other)
        {
            if (! (other instanceof FragmentData))
                return false;
            FragmentData f = (FragmentData) other;
            return this.key.equals(f.key) && this.address.equals(f.address) && this.owner.equals(f.owner);
        }
    }

    private final AdjacencyList<String> friendshipGraph;
    private final Map<String, HashSet<FragmentData> > userFragments;

    AbstractCoreNode() 
    {
        this.friendshipGraph = new AdjacencyList<String>();
        this.userFragments = new HashMap<String, HashSet<FragmentData> >();
    }

    public abstract void close() throws IOException;

    public synchronized boolean claimUserName(String user)
    {
        if (userFragments.containsKey(user))
            return false;

        friendshipGraph.addVertex(user);
        userFragments.put(user,new HashSet<FragmentData>());
        return true;
    } 

    public synchronized boolean addFriendship(String userA, String userB)
    {
        if (! friendshipGraph.containsVertex(userA) || ! friendshipGraph.containsVertex(userB))
            return false;

        return friendshipGraph.addEdge(userA, userB);   
    }

    public synchronized boolean registerFragment(String user, byte[] key, InetSocketAddress address)
    {
        HashSet<FragmentData> set = userFragments.get(user);
        if (set == null)
            return false;

        FragmentData data = new FragmentData(user, key, address);
        
        return set.add(data);
    }

    public synchronized boolean removeFragment(String user, byte[] key, InetSocketAddress address)
    {
        HashSet<FragmentData> set = userFragments.get(user);
        if (set == null)
            return false;
        
        FragmentData data = new FragmentData(user, key, address);

        boolean isRemoved = set.remove(data);
        if (! isRemoved)
            return false;

        for (HashSet<FragmentData> s: userFragments.values())
            s.remove(data);

        return true;
    }

    public synchronized boolean shareWith(String owner, String sharee, byte[] key, InetSocketAddress address)
    {

        if (! friendshipGraph.containsVertex(owner) || ! friendshipGraph.containsVertex(sharee))
            return false;

        FragmentData data = new FragmentData(owner, key, address);
        
        if (! userFragments.get(owner).contains(data))
            return false;
        return userFragments.get(sharee).add(data);    
    }
    

    public synchronized String[] getFriends(String forUser)
    {
        return friendshipGraph.getEdges(forUser).toArray(new String[0]);
    }

    public synchronized FragmentData[] getFragmentsData(String forUser)
    {
        if (! userFragments.containsKey(forUser))
            return null;

        return userFragments.get(forUser).toArray(new FragmentData[0]); 
    }


}
