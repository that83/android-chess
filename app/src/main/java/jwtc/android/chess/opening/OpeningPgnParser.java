package jwtc.android.chess.opening;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
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

    private static final Pattern RESULT_PATTERN = Pattern.compile("^(1-0|0-1|1/2-1/2|\\*)$");
    private static final Pattern MOVE_NUM_PATTERN = Pattern.compile("^(\\d+)\\.{1,3}$");
    private static final Pattern NAG_PATTERN = Pattern.compile("^\\$\\d+$");
    /** After mapping letter-O castles to 0-0 / 0-0-0. */
    private static final Pattern CASTLING_SAN_CORE = Pattern.compile("^(0-0-0|0-0)$");

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

    /**
     * Reads the first {@code [TagName "…"]} header in a chunk (e.g. {@code Guide}, {@code Event}).
     * Unescapes {@code \\} and {@code \"} per PGN header rules.
     */
    public static String extractBracketTagValue(String pgnChunk, String tagName) {
        if (pgnChunk == null || tagName == null || tagName.isEmpty()) {
            return null;
        }
        try {
            Pattern p = Pattern.compile(
                    "\\[" + Pattern.quote(tagName) + "\\s+\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\]",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
            Matcher m = p.matcher(pgnChunk);
            if (!m.find()) {
                return null;
            }
            return unescapePgnHeaderString(m.group(1));
        } catch (Exception e) {
            Log.w(TAG, "extractBracketTagValue failed for " + tagName, e);
            return null;
        }
    }

    private static String unescapePgnHeaderString(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(i + 1);
                if (n == '"' || n == '\\') {
                    sb.append(n);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Remove well-formed {@code [TagName "value"]} headers only, respecting quoted strings
     * (so long {@code [Guide "…html…"]} blocks are not truncated at the first {@code ]}).
     */
    public static String removeTagBlocks(String pgn) {
        if (pgn == null || pgn.isEmpty()) {
            return pgn;
        }
        StringBuilder out = new StringBuilder(pgn.length());
        int i = 0;
        final int n = pgn.length();
        while (i < n) {
            if (pgn.charAt(i) != '[') {
                out.append(pgn.charAt(i));
                i++;
                continue;
            }
            int consumed = tryConsumeBracketQuotedTag(pgn, i);
            if (consumed > 0) {
                out.append(' ');
                i += consumed;
                continue;
            }
            out.append(pgn.charAt(i));
            i++;
        }
        return out.toString();
    }

    /**
     * True if {@code pgnChunk} contains at least one move in the main line from the root.
     */
    public static boolean chunkHasMoves(String pgnChunk) {
        if (pgnChunk == null || pgnChunk.trim().isEmpty()) {
            return false;
        }
        OpeningMoveTree t = new OpeningMoveTree();
        parseIntoTreeFromBodyOnly(pgnChunk, t);
        return t.getRoot().hasChildren();
    }

    private static void parseIntoTreeFromBodyOnly(String pgn, OpeningMoveTree tree) {
        if (pgn == null || tree == null) {
            return;
        }
        String s = removeTagBlocks(pgn);
        s = s.replace("\r", " ").replace("\n", " ").replace("\t", " ");
        while (s.contains("  ")) {
            s = s.replace("  ", " ");
        }
        s = s.trim();
        if (s.isEmpty()) {
            return;
        }
        parseTokenListIntoTree(s, tree);
    }

    /** @return length consumed from {@code start} (inclusive of {@code [}), or 0 if not a matching tag. */
    private static int tryConsumeBracketQuotedTag(String s, int start) {
        if (start >= s.length() || s.charAt(start) != '[') {
            return 0;
        }
        int i = start + 1;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        int nameStart = i;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
        if (i == nameStart) {
            return 0;
        }
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i >= s.length() || s.charAt(i) != '"') {
            return 0;
        }
        i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += Math.min(2, s.length() - i);
                continue;
            }
            if (c == '"') {
                i++;
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                if (i < s.length() && s.charAt(i) == ']') {
                    return (i + 1) - start;
                }
                return 0;
            }
            i++;
        }
        return 0;
    }

    public static void parseIntoTree(String pgn, OpeningMoveTree tree) {
        if (pgn == null) {
            return;
        }
        parseIntoTreeFromBodyOnly(pgn, tree);
    }

    private static void parseTokenListIntoTree(String s, OpeningMoveTree tree) {
        if (s == null || tree == null) {
            return;
        }
        s = s.replace("\r", " ").replace("\n", " ").replace("\t", " ");
        while (s.contains("  ")) {
            s = s.replace("  ", " ");
        }
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
                    if (!isPlausibleSanToken(san)) {
                        break;
                    }
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

    /**
     * True if the token looks like algebraic notation. Prose inside {@code [Guide]} or broken headers
     * (e.g. {@code Draw, as long as...}) must not become pseudo-moves or training expects the wrong SAN.
     */
    public static boolean isPlausibleSanToken(String raw) {
        if (raw == null) {
            return false;
        }
        String t = normalizeSan(raw.trim());
        if (t.isEmpty()) {
            return false;
        }
        String castles = t.replace('O', '0').replace('o', '0');
        if (CASTLING_SAN_CORE.matcher(castles).matches()) {
            return true;
        }
        String core = t;
        if (core.length() >= 2) {
            char e1 = core.charAt(core.length() - 1);
            char e0 = core.charAt(core.length() - 2);
            if (e0 == '=' && "QRBNqrbn".indexOf(e1) >= 0) {
                core = core.substring(0, core.length() - 2);
            }
        }
        int n = core.length();
        if (n < 2) {
            return false;
        }
        char rank = core.charAt(n - 1);
        char file = core.charAt(n - 2);
        if (rank < '1' || rank > '8') {
            return false;
        }
        return file >= 'a' && file <= 'h';
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
