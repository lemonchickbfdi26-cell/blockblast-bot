package com.leon.blockbot;

import java.util.ArrayList;
import java.util.List;

/** Ищет лучший порядок и места для фигур. */
final class Solver {

    static final class Move {
        int idx, r, c;
        Move(int idx, int r, int c) { this.idx = idx; this.r = r; this.c = c; }
    }

    private static final long COL0 = 0x0101010101010101L;

    static long rowMask(int r) { return 0xFFL << (r * 8); }
    static long colMask(int c) { return COL0 << c; }

    /** Убирает заполненные строки/столбцы. lines[0] = сколько линий сгорело. */
    static long clear(long b, int[] lines) {
        long rm = 0;
        int n = 0;
        for (int i = 0; i < 8; i++) {
            long rmk = rowMask(i);
            if ((b & rmk) == rmk) { rm |= rmk; n++; }
            long cmk = colMask(i);
            if ((b & cmk) == cmk) { rm |= cmk; n++; }
        }
        lines[0] = n;
        return b & ~rm;
    }

    /** Простая доска после установки фигуры (для проверки, куда она реально встала). */
    static long place(long board, long pat, int r, int c) {
        int[] l = new int[1];
        return clear(board | (pat << (r * 8 + c)), l);
    }

    static boolean fits(long board, long pat, int rows, int cols, int r, int c) {
        if (r < 0 || c < 0 || r + rows > 8 || c + cols > 8) return false;
        return (board & (pat << (r * 8 + c))) == 0;
    }

    // ---- оценка позиции ----

    private static final long SQ3 = 0x070707L;      // 3x3
    private static final long H5 = 0x1FL;           // 1x5
    private static final long V5 = 0x0101010101L;   // 5x1

    private static boolean canFit(long b, long pat, int rows, int cols) {
        for (int r = 0; r + rows <= 8; r++)
            for (int c = 0; c + cols <= 8; c++)
                if ((b & (pat << (r * 8 + c))) == 0) return true;
        return false;
    }

    private static boolean at(long b, int r, int c) {
        if (r < 0 || r > 7 || c < 0 || c > 7) return true;
        return (b & (1L << (r * 8 + c))) != 0;
    }

    static double eval(long b) {
        int filled = Long.bitCount(b);
        int holes = 0, trans = 0;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (!at(b, r, c) && at(b, r - 1, c) && at(b, r + 1, c) && at(b, r, c - 1) && at(b, r, c + 1))
                    holes++;
            }
        }
        for (int r = 0; r < 8; r++) {
            boolean prev = true;
            for (int c = 0; c < 8; c++) {
                boolean cur = at(b, r, c);
                if (cur != prev) trans++;
                prev = cur;
            }
            if (!prev) trans++;
        }
        for (int c = 0; c < 8; c++) {
            boolean prev = true;
            for (int r = 0; r < 8; r++) {
                boolean cur = at(b, r, c);
                if (cur != prev) trans++;
                prev = cur;
            }
            if (!prev) trans++;
        }
        double s = -filled * 1.0 - holes * 10.0 - trans * 1.5;
        if (canFit(b, SQ3, 3, 3)) s += 12;
        if (canFit(b, H5, 1, 5)) s += 5;
        if (canFit(b, V5, 5, 1)) s += 5;
        return s;
    }

    static double clearReward(int lines) {
        if (lines == 0) return 0;
        return 30.0 * lines + 20.0 * (lines - 1);
    }

    // ---- перебор ----

    private static final int[] BEAM = {16, 10, 64};

    private static final class Child {
        int idx, r, c;
        long board;
        double acc, score;
    }

    private List<Piece> pieces;
    private double bestScore;
    private Move bestFirst;

    static Move best(long board, List<Piece> pieces) {
        Solver s = new Solver();
        s.pieces = pieces;
        s.bestScore = Double.NEGATIVE_INFINITY;
        s.dfs(board, 0, 0, 0.0, null);
        return s.bestFirst;
    }

    private void dfs(long board, int used, int depth, double acc, Move first) {
        int n = pieces.size();
        List<Child> kids = new ArrayList<>();
        if (depth < n) {
            int[] lines = new int[1];
            for (int i = 0; i < n; i++) {
                if ((used & (1 << i)) != 0) continue;
                Piece p = pieces.get(i);
                // одинаковые фигуры перебираем один раз
                boolean dup = false;
                for (int j = 0; j < i; j++)
                    if ((used & (1 << j)) == 0 && pieces.get(j).pattern == p.pattern) { dup = true; break; }
                if (dup) continue;
                for (int r = 0; r + p.rows <= 8; r++) {
                    for (int c = 0; c + p.cols <= 8; c++) {
                        long m = p.pattern << (r * 8 + c);
                        if ((board & m) != 0) continue;
                        Child k = new Child();
                        k.idx = i; k.r = r; k.c = c;
                        k.board = clear(board | m, lines);
                        k.acc = acc + clearReward(lines[0]);
                        k.score = k.acc + eval(k.board);
                        kids.add(k);
                    }
                }
            }
        }
        if (kids.isEmpty()) {
            double total = acc + eval(board) - 1000.0 * (n - depth);
            if (first != null && total > bestScore) {
                bestScore = total;
                bestFirst = first;
            }
            return;
        }
        kids.sort((a, b) -> Double.compare(b.score, a.score));
        int lim = Math.min(kids.size(), BEAM[Math.min(depth, BEAM.length - 1)]);
        for (int k = 0; k < lim; k++) {
            Child ch = kids.get(k);
            Move f = first != null ? first : new Move(ch.idx, ch.r, ch.c);
            dfs(ch.board, used | (1 << ch.idx), depth + 1, ch.acc, f);
        }
    }
}
