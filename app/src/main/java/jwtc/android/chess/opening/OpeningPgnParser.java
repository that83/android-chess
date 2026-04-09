package jwtc.android.chess.opening;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Very small PGN-to-move-tree parser that understands:
 * - multiple games in a single string
 * - variations in parentheses
 * - brace comments {@code {...}} after a move (stored on the edge for that move)
 * It does not validate moves against the engine; it only builds a SAN move tree.
 */
public class OpeningPgnParser {

    private static final String TAG = "OpeningPgnParser";

    private static final Pattern TAG_BLOCK_PATTERN = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern RESULT_PATTERN = Pattern.compile("^(1-0|0-1|1/2-1/2|\\*)$");
    private static final Pattern MOVE_NUM_PATTERN = Pattern.compile("^(\\d+)\\.{1,3}$");
    private static final Pattern NAG_PATTERN = Pattern.compile("^\\$\\d+$");

    private static final int TOK_WORD = 0;
    private static final int TOK_LP = 1;
    private static final int TOK_RP = 2;
    private static final int TOK_COMMENT = 3;

    private static final class Tok {
        final int kind;
        final String text;

        Tok(int kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private static List<Tok> tokenizeAfterHeaders(String s) {
        List<Tok> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') {
                out.add(new Tok(TOK_LP, "("));
                i++;
                continue;
            }
            if (c == ')') {
                out.add(new Tok(TOK_RP, ")"));
                i++;
                continue;
            }
            if (c == '{') {
                int j = i + 1;
                int depth = 1;
                while (j < n && depth > 0) {
                    char ch = s.charAt(j);
                    if (ch == '{') {
                        depth++;
                    } else if (ch == '}') {
                        depth--;
                    }
                    j++;
                }
                String inner = s.substring(i + 1, j - 1).trim().replaceAll("\\s+", " ");
                out.add(new Tok(TOK_COMMENT, inner));
                i = j;
                continue;
            }
            int j = i;
            while (j < n) {
                char ch = s.charAt(j);
                if (Character.isWhitespace(ch) || ch == '(' || ch == ')' || ch == '{') {
                    break;
                }
                j++;
            }
            out.add(new Tok(TOK_WORD, s.substring(i, j)));
            i = j;
        }
        return out;
    }

    public static void parseIntoTree(String pgn, OpeningMoveTree tree) {
        if (pgn == null) {
            return;
        }
        String s = pgn;
        s = TAG_BLOCK_PATTERN.matcher(s).replaceAll(" ");
        s = s.replace("\r", " ").replace("\n", " ").replace("\t", " ");
        while (s.contains("  ")) s = s.replace("  ", " ");
        s = s.trim();
        if (s.isEmpty()) {
            return;
        }

        List<Tok> tokens = tokenizeAfterHeaders(s);

        OpeningMoveTree.Node root = tree.getRoot();
        OpeningMoveTree.Node current = root;
        OpeningMoveTree.Node lastNodeBeforeMove = root;

        class VarFrame {
            OpeningMoveTree.Node anchor;
            OpeningMoveTree.Node returnNode;

            VarFrame(OpeningMoveTree.Node a, OpeningMoveTree.Node r) {
                anchor = a;
                returnNode = r;
            }
        }
        java.util.Stack<VarFrame> variationStack = new java.util.Stack<>();

        String pendingComment = null;

        for (Tok tok : tokens) {
            switch (tok.kind) {
                case TOK_LP:
                    variationStack.push(new VarFrame(lastNodeBeforeMove, current));
                    current = lastNodeBeforeMove;
                    break;
                case TOK_RP:
                    if (!variationStack.isEmpty()) {
                        VarFrame frame = variationStack.pop();
                        current = frame.returnNode;
                    } else {
                        Log.w(TAG, "Unbalanced ) in PGN");
                    }
                    break;
                case TOK_COMMENT:
                    String cmt = tok.text;
                    if (cmt == null || cmt.isEmpty()) {
                        break;
                    }
                    if (pendingComment == null || pendingComment.isEmpty()) {
                        pendingComment = cmt;
                    } else {
                        pendingComment = pendingComment + " " + cmt;
                    }
                    break;
                case TOK_WORD:
                default:
                    String t = tok.text != null ? tok.text.trim() : "";
                    if (t.isEmpty()) {
                        break;
                    }
                    if (RESULT_PATTERN.matcher(t).matches()) {
                        current = root;
                        lastNodeBeforeMove = root;
                        pendingComment = null;
                        break;
                    }
                    if (MOVE_NUM_PATTERN.matcher(t).matches()) {
                        break;
                    }
                    if (NAG_PATTERN.matcher(t).matches()) {
                        break;
                    }

                    String san = t;
                    String sanNorm = normalizeSan(san);
                    if (!sanNorm.isEmpty()) {
                        lastNodeBeforeMove = current;
                        OpeningMoveTree.Node next = new OpeningMoveTree.Node();
                        current.addChild(san, sanNorm, next, pendingComment);
                        pendingComment = null;
                        current = next;
                    }
                    break;
            }
        }
    }

    public static String normalizeSan(String san) {
        if (san == null) {
            return "";
        }
        String s = san.trim();
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
