package jwtc.android.chess.opening;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple in-memory move tree for opening training.
 * Nodes are keyed by normalized SAN strings.
 */
public class OpeningMoveTree {

    public static class Edge {
        public final String san;      // original SAN
        public final String sanNorm;  // normalized SAN
        public final Node child;

        public Edge(String san, String sanNorm, Node child) {
            this.san = san;
            this.sanNorm = sanNorm;
            this.child = child;
        }
    }

    public static class Node {
        private final Map<String, Edge> edges = new LinkedHashMap<>();

        public void addChild(String san, String sanNorm, Node child) {
            if (!edges.containsKey(sanNorm)) {
                edges.put(sanNorm, new Edge(san, sanNorm, child));
            }
        }

        public Edge getEdge(String sanNorm) {
            return edges.get(sanNorm);
        }

        public List<Edge> getEdges() {
            return new ArrayList<>(edges.values());
        }

        public boolean hasChildren() {
            return !edges.isEmpty();
        }
    }

    private final Node root = new Node();

    public Node getRoot() {
        return root;
    }
}

