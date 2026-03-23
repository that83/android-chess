package jwtc.android.chess.opening;

import android.util.Log;

import java.util.regex.Pattern;

/**
 * Very small PGN-to-move-tree parser that understands:
 * - multiple games in a single string
 * - variations in parentheses
 * It does not validate moves against the engine; it only builds a SAN move tree.
 */
public class OpeningPgnParser {

    private static final String TAG = "OpeningPgnParser";

    private static final Pattern TAG_BLOCK_PATTERN = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern BRACE_COMMENT_PATTERN = Pattern.compile("\\{[^\\}]*\\}");
    private static final Pattern RESULT_PATTERN = Pattern.compile("^(1-0|0-1|1/2-1/2|\\*)$");
    private static final Pattern MOVE_NUM_PATTERN = Pattern.compile("^(\\d+)\\.{1,3}$");
    private static final Pattern NAG_PATTERN = Pattern.compile("^\\$\\d+$");

    public static void parseIntoTree(String pgn, OpeningMoveTree tree) {
        if (pgn == null) {
            return;
        }
        // Remove headers and brace comments first (headers can contain spaces).
        String s = pgn;
        s = TAG_BLOCK_PATTERN.matcher(s).replaceAll(" ");
        s = BRACE_COMMENT_PATTERN.matcher(s).replaceAll(" ");
        s = s.replace("\r", " ").replace("\n", " ").replace("\t", " ");
        // Make parentheses standalone tokens.
        s = s.replace("(", " ( ").replace(")", " ) ");
        while (s.contains("  ")) s = s.replace("  ", " ");
        s = s.trim();
        if (s.isEmpty()) {
            return;
        }

        OpeningMoveTree.Node root = tree.getRoot();
        OpeningMoveTree.Node current = root;
        OpeningMoveTree.Node lastNodeBeforeMove = root;

        class VarFrame {
            OpeningMoveTree.Node anchor;
            OpeningMoveTree.Node returnNode;
            VarFrame(OpeningMoveTree.Node a, OpeningMoveTree.Node r) { anchor = a; returnNode = r; }
        }
        java.util.Stack<VarFrame> variationStack = new java.util.Stack<>();

        String[] tokens = s.split(" ");
        for (String raw : tokens) {
            if (raw == null) continue;
            String t = raw.trim();
            if (t.isEmpty()) continue;

            if ("(".equals(t)) {
                // Variation is typically an alternative from the position BEFORE the last played move.
                // Return to the node where we were when encountering '(' (current).
                variationStack.push(new VarFrame(lastNodeBeforeMove, current));
                current = lastNodeBeforeMove;
                continue;
            }
            if (")".equals(t)) {
                if (!variationStack.isEmpty()) {
                    VarFrame frame = variationStack.pop();
                    current = frame.returnNode;
                } else {
                    Log.w(TAG, "Unbalanced ) in PGN");
                }
                continue;
            }

            if (RESULT_PATTERN.matcher(t).matches()) {
                current = root;
                lastNodeBeforeMove = root;
                continue;
            }
            if (MOVE_NUM_PATTERN.matcher(t).matches()) {
                continue;
            }
            if (NAG_PATTERN.matcher(t).matches()) {
                continue;
            }

            // SAN move
            String san = t;
            String sanNorm = normalizeSan(san);
            if (!sanNorm.isEmpty()) {
                lastNodeBeforeMove = current;
                OpeningMoveTree.Node next = new OpeningMoveTree.Node();
                current.addChild(san, sanNorm, next);
                current = next;
            }
        }
    }

    public static String normalizeSan(String san) {
        if (san == null) {
            return "";
        }
        String s = san.trim();
        // common PGN suffixes: e.p. or annotation glyphs are handled elsewhere; keep core token.
        // strip trailing check/mate/annotation symbols +, #, !, ?
        while (!s.isEmpty()) {
            char last = s.charAt(s.length() - 1);
            if (last == '+' || last == '#' || last == '!' || last == '?') {
                s = s.substring(0, s.length() - 1);
            } else {
                break;
            }
        }
        return s;
    }
}

