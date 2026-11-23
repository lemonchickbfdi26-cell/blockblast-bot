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

    // все фигуры Block Blast: {pattern, rows, cols}
    static final long[][] SHAPES = {
        {0x1L,1,1},{0x3L,1,2},{0x101L,2,1},{0x7L,1,3},{0x10101L,3,1},{0xFL,1,4},{0x1010101L,4,1},{0x1FL,1,5},{0x101010101L,5,1},
        {0x303L,2,2},{0x70707L,3,3},{0x707L,2,3},{0x30303L,3,2},
        {0x103L,2,2},{0x203L,2,2},{0x301L,2,2},{0x302L,2,2},
        {0x10107L,3,3},{0x40407L,3,3},{0x70101L,3,3},{0x70404L,3,3},
        {0x30101L,3,2},{0x30202L,3,2},{0x10103L,3,2},{0x20203L,3,2},{0x107L,2,3},{0x407L,2,3},{0x701L,2,3},{0x704L,2,3},
        {0x207L,2,3},{0x702L,2,3},{0x10301L,3,2},{0x20302L,3,2},
        {0x603L,2,3},{0x306L,2,3},{0x20301L,3,2},{0x10302L,3,2},
        {0x201L,2,2},{0x102L,2,2},{0x40201L,3,3},{0x10204L,3,3}
    };
    // насколько часто игра выдаёт каждую фигуру (1 = как в среднем); учится во время игры
    static final double[] SHAPE_W = new double[SHAPES.length];
    static { java.util.Arrays.fill(SHAPE_W, 1.0); }

    static double W_CLEAR = 1.0, W_FILLED = 1.0, W_HOLE = 10.0, W_TRANS = 5.0, W_FIT = 2.0, W_BIG = 12.0, W_LINE5 = 5.0, W_REGION = 0.0;

    private static boolean canFit(long b, long[] sh) {
        long pat = sh[0];
        int rows = (int) sh[1], cols = (int) sh[2];
        for (int r = 0; r + rows <= 8; r++)
            for (int c = 0; c + cols <= 8; c++)
                if ((b & (pat << (r * 8 + c))) == 0) return true;
        return false;
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
        double s = -filled * W_FILLED - holes * W_HOLE - trans * W_TRANS;
        if (canFit(b, SQ3, 3, 3)) s += W_BIG;
        if (canFit(b, H5, 1, 5)) s += W_LINE5;
        if (canFit(b, V5, 5, 1)) s += W_LINE5;
        if (W_FIT > 0) {
            double miss = 0;
            for (int i = 0; i < SHAPES.length; i++) if (!canFit(b, SHAPES[i])) miss += SHAPE_W[i];
            s -= miss * W_FIT;
        }
        if (W_REGION > 0) s -= smallRegions(b) * W_REGION;
        return s;
    }

    /** Сколько пустых клеток в маленьких замкнутых областях (1–3 клетки). */
    static int smallRegions(long b) {
        long empty = ~b, seen = 0;
        int bad = 0;
        for (int i = 0; i < 64; i++) {
            long bit = 1L << i;
            if ((empty & bit) == 0 || (seen & bit) != 0) continue;
            long comp = bit, frontier = bit;
            while (frontier != 0) {
                long n = ((frontier << 8) | (frontier >>> 8)
                        | ((frontier << 1) & ~COL0) | ((frontier >>> 1) & ~(COL0 << 7))) & empty & ~comp;
                comp |= n;
                frontier = n;
            }
            seen |= comp;
            int sz = Long.bitCount(comp);
            if (sz <= 3) bad += sz;
        }
        return bad;
    }

    static double W_COMBO = 5, W_KEEP = 10;

    /** Очки за очистку с учётом комбо (как в Block Blast: серия, пока чистишь хотя бы раз в 3 хода). */
    static double clearReward(int lines, int combo) {
        if (lines == 0) return 0;
        return W_CLEAR * (30.0 * lines + 20.0 * (lines - 1)) + W_COMBO * lines * combo;
    }

    static double clearReward(int lines) { return clearReward(lines, 0); }

    // ---- перебор ----

    static int[] BEAM = {16, 10, 64};

    private static final class Child {
        int idx, r, c, msc, combo;
        long board;
        double acc, score;
    }

    private List<Piece> pieces;
    private double bestScore;
    private Move bestFirst;

    static Move best(long board, List<Piece> pieces) { return best(board, pieces, 3, 0); }

    /** msc — ходов без очистки, combo — длина текущей серии. */
    static Move best(long board, List<Piece> pieces, int msc, int combo) {
        Solver s = new Solver();
        s.pieces = pieces;
        s.bestScore = Double.NEGATIVE_INFINITY;
        s.dfs(board, 0, 0, 0.0, null, msc, combo);
        return s.bestFirst;
    }

    private double leafValue(long board, double acc, int depth, int msc, int combo) {
        int n = pieces.size();
        double v = acc + eval(board) - 1000.0 * (n - depth);
        if (msc < 3) v += W_KEEP * Math.min(combo, 20) * (3 - msc) / 3.0;
        return v;
    }

    private void dfs(long board, int used, int depth, double acc, Move first, int msc, int combo) {
        int n = pieces.size();
        List<Child> kids = new ArrayList<>();
        if (depth < n) {
            int[] lines = new int[1];
            for (int i = 0; i < n; i++) {
                if ((used & (1 << i)) != 0) continue;
                Piece p = pieces.get(i);
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
                        if (lines[0] > 0) {
                            k.combo = (msc < 3 ? combo : 0) + 1;
                            k.msc = 0;
                        } else {
                            k.msc = msc + 1;
                            k.combo = k.msc >= 3 ? 0 : combo;
                        }
                        k.acc = acc + clearReward(lines[0], k.combo);
                        k.score = leafValue(k.board, k.acc, depth + 1, k.msc, k.combo);
                        kids.add(k);
                    }
                }
            }
        }
        if (kids.isEmpty()) {
            double total = leafValue(board, acc, depth, msc, combo);
            if (first != null && total > bestScore) {
                bestScore = total;
                bestFirst = first;
            }
            return;
        }
        kids.sort((x, y) -> Double.compare(y.score, x.score));
        int lim = Math.min(kids.size(), BEAM[Math.min(depth, BEAM.length - 1)]);
        for (int k = 0; k < lim; k++) {
            Child ch = kids.get(k);
            Move f = first != null ? first : new Move(ch.idx, ch.r, ch.c);
            dfs(ch.board, used | (1 << ch.idx), depth + 1, ch.acc, f, ch.msc, ch.combo);
        }
    }
}
