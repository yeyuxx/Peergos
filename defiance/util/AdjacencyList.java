package defiance.util;

import java.util.*;


public class AdjacencyList<T>
{
    /** Represents a directed graph  G({T},{edges(T_i, T_j)}) i != j
     * as an adjacency list. This implementation uses a HashSet for the 
     * edge lookup purely as an optimisation.
     */

    private final Map<T, HashSet<T> > vertices;

    public AdjacencyList()
    {
        this.vertices = new HashMap<T, HashSet<T> >();
    }

    public boolean addEdge(T from, T to)
    {
        if (! vertices.containsKey(from))
            vertices.put(from, new HashSet<T>());

        HashSet<T> set = vertices.get(from);
        if (set.contains(to))
            return false;

        set.add(to);
        return true;
    }

    public boolean removeEdge(T from, T to)
    {
        if (! vertices.containsKey(from))
            return false;
        HashSet<T> set = vertices.get(from);

        return set.remove(to);
    }

    public boolean addVertex(T vert)
    {
        if (vertices.containsKey(vert))
            return false;
        vertices.put(vert, new HashSet<T>());
        return true;
    }

    public boolean containsVertex(T vert)
    {
        return vertices.get(vert) != null;
    }

    public boolean containsEdge(T from, T to)
    {
        HashSet<T> set = vertices.get(from);

        return set == null ? false : set.contains(to);
    }

    public boolean removeVertex(T vert)
    {
        boolean isRemoved = false;

        isRemoved =  vertices.remove(vert) != null;

        for (HashSet<T> edges: vertices.values())
            edges.remove(vert);
        
        return isRemoved;
    } 

    public ArrayList<T> getEdges(T forVert)
    {
        HashSet<T> edgeNodes = vertices.get(forVert);

        if (edgeNodes == null)
            return null;

        return new ArrayList<T>(edgeNodes);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        for (T vert: vertices.keySet())
        {
            sb.append("vertex : "+ vert + " connected to \n[");
            for (T other: vertices.get(vert))
                sb.append(other +" , ");
            sb.append("]");
        }
        return sb.toString();
    }

}
