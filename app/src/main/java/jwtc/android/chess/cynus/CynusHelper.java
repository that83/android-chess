package jwtc.android.chess.cynus;

import jwtc.chess.Move;
import jwtc.chess.Pos;
import jwtc.chess.board.BoardConstants;

/**
 * CYNUS board protocol helpers: FEN normalization (compatible with Windows BLE script) and UCI move string.
 */
public final class CynusHelper {
    private static final java.util.UUID FFF1 =
            java.util.UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");

    private CynusHelper() {}

    public static java.util.UUID cynusCharacteristicUuid() {
        return FFF1;
    }

    /**
     * Convert native engine move to UCI (e.g. e2e4, e1g1, e7e8q).
     */
    public static String moveToUci(int move) {
        if (move == 0) {
            return "";
        }
        int from = Move.getFrom(move);
        int to = Move.getTo(move);
        StringBuilder sb = new StringBuilder();
        sb.append(Pos.toString(from)).append(Pos.toString(to));
        if (Move.isPromotionMove(move)) {
            int p = Move.getPromotionPiece(move) & 0x7;
            if (p == BoardConstants.KNIGHT) {
                sb.append('n');
            } else if (p == BoardConstants.BISHOP) {
                sb.append('b');
            } else if (p == BoardConstants.ROOK) {
                sb.append('r');
            } else {
                sb.append('q');
            }
        }
        return sb.toString();
    }

    /**
     * If hardware sends only piece placement, append a synthetic side-to-move.
     * If full FEN is sent, keep as-is.
     */
    public static String normalizeIncomingFen(String raw, boolean defaultWhiteToMove) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        String[] p = s.split("\\s+");
        if (p.length >= 6) {
            return s;
        }
        // Typical CYNUS / script: single-field placement only
        return p[0] + (defaultWhiteToMove ? " w - - 0 15" : " b - - 0 15");
    }

    /**
     * Force side-to-move in an already-normalized FEN.
     */
    public static String forceSideToMove(String fen, boolean whiteToMove) {
        if (fen == null) return null;
        String[] p = fen.trim().split("\\s+");
        if (p.length < 6) {
            return normalizeIncomingFen(fen, whiteToMove);
        }
        p[1] = whiteToMove ? "w" : "b";
        return p[0] + " " + p[1] + " " + p[2] + " " + p[3] + " " + p[4] + " " + p[5];
    }
}
