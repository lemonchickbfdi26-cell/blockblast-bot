package com.leon.blockbot;

import java.util.ArrayList;
import java.util.List;

/** Распознавание поля и фигур по кадру. */
final class Vision {

    static int r(int c) { return (c >> 16) & 255; }
    static int g(int c) { return (c >> 8) & 255; }
    static int b(int c) { return c & 255; }

    static int val(int c) {
        return Math.max(r(c), Math.max(g(c), b(c)));
    }

    static int dist(int a, int c) {
        return Math.abs(r(a) - r(c)) + Math.abs(g(a) - g(c)) + Math.abs(b(a) - b(c));
    }

    /** Средний цвет 3x3 точек вокруг центра. */
    static int avg(Frame f, float cx, float cy, float rad) {
        int sr = 0, sg = 0, sb = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int p = f.get(cx + dx * rad, cy + dy * rad);
                sr += r(p);
                sg += g(p);
                sb += b(p);
            }
        }
        return ((sr / 9) << 16) | ((sg / 9) << 8) | (sb / 9);
    }

    static State read(Frame f, Config c) {
        State s = new State();
        int[] cols = new int[64];
        int minV = 999, minI = -1;
        float rad = c.cell * 0.15f;
        for (int i = 0; i < 64; i++) {
            cols[i] = avg(f, c.x0 + (i % 8) * c.cell, c.y0 + (i / 8) * c.cell, rad);
            int v = val(cols[i]);
            if (v < minV) {
                minV = v;
                minI = i;
            }
        }
        // цвет пустой клетки = самая большая группа похожих тёмных клеток
        int bestCnt = 0, bestI = -1;
        for (int i = 0; i < 64; i++) {
            if (val(cols[i]) >= 150) continue;
            int cnt = 0;
            for (int j = 0; j < 64; j++) if (dist(cols[i], cols[j]) < 40) cnt++;
            if (cnt > bestCnt) { bestCnt = cnt; bestI = i; }
        }
        if (bestI >= 0 && (bestCnt >= 3 || c.emptyRef == -1)) c.emptyRef = cols[bestI];
        if (c.emptyRef == -1) return null;

        int ref = c.emptyRef;
        int rv = val(ref);
        long board = 0;
        int unc = 0;
        for (int i = 0; i < 64; i++) {
            int d = dist(cols[i], ref);
            int v = val(cols[i]);
            boolean filled = d > 75 && v > rv + 30;
            if (filled) board |= 1L << i;
            else if (d > 45) unc++;
        }
        s.board = board;
        s.uncertain = unc;
        s.pieces = readTray(f, c);
        return s;
    }

    // ---------- лоток с фигурами ----------

    private static int bg;
    private static int bgV;

    /** Блок фигуры: заметно отличается от фона и это не тень (тень = тот же фон, только темнее). */
    private static boolean block(int p) {
        if (dist(p, bg) <= 55) return false;
        float br = r(bg), bgg = g(bg), bb = b(bg);
        float t = (r(p) * br + g(p) * bgg + b(p) * bb) / (br * br + bgg * bgg + bb * bb + 1f);
        if (t < 1.0f) {
            float res = Math.abs(r(p) - t * br) + Math.abs(g(p) - t * bgg) + Math.abs(b(p) - t * bb);
            if (res < 45) return false; // тень
        }
        return true;
    }

    /** Похож ли пиксель на блок фигуры (по фону лотка из последнего чтения). */
    static boolean isTrayBlock(int p) {
        return bg != 0 && block(p);
    }

    private static boolean colHas(Frame f, int x, int y0, int y1) {
        for (int y = y0; y <= y1; y += 2) if (block(f.get(x, y))) return true;
        return false;
    }

    private static boolean rowHas(Frame f, int y, int x0, int x1) {
        for (int x = x0; x <= x1; x += 2) if (block(f.get(x, y))) return true;
        return false;
    }

    static Piece[] readTray(Frame f, Config c) {
        Piece[] out = new Piece[3];
        int L = clamp((int) c.trayLeft, 0, f.w - 1);
        int R = clamp((int) c.trayRight, 0, f.w - 1);
        int T = clamp((int) c.trayTop, 0, f.h - 1);
        int B = clamp((int) c.trayBottom, 0, f.h - 1);
        if (R - L < 12 || B - T < 12) return out;

        int step = Math.max(2, (int) (c.cell / 10));

        // фон = самый частый цвет в лотке
        int[] hist = new int[4096];
        for (int y = T; y <= B; y += step * 2) {
            for (int x = L; x <= R; x += step * 2) {
                int p = f.get(x, y);
                hist[((r(p) >> 4) << 8) | ((g(p) >> 4) << 4) | (b(p) >> 4)]++;
            }
        }
        int best = 0;
        for (int i = 1; i < 4096; i++) if (hist[i] > hist[best]) best = i;
        // средний цвет пикселей самого частого оттенка
        long sr = 0, sg = 0, sb = 0, n = 0;
        for (int y = T; y <= B; y += step * 2) {
            for (int x = L; x <= R; x += step * 2) {
                int p = f.get(x, y);
                if ((((r(p) >> 4) << 8) | ((g(p) >> 4) << 4) | (b(p) >> 4)) == best) {
                    sr += r(p); sg += g(p); sb += b(p); n++;
                }
            }
        }
        if (n == 0) n = 1;
        bg = (int) (((sr / n) << 16) | ((sg / n) << 8) | (sb / n));
        if (bg == 0) bg = 1;
        bgV = val(bg);

        // рамки фигур в каждом из трёх слотов
        int[][] boxes = new int[3][];
        float sw = (R - L) / 3f;
        for (int i = 0; i < 3; i++) {
            int sx0 = (int) (L + i * sw), sx1 = (int) (L + (i + 1) * sw) - 1;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1, count = 0;
            for (int y = T; y <= B; y += step) {
                for (int x = sx0; x <= sx1; x += step) {
                    if (block(f.get(x, y))) {
                        count++;
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (count < 4) continue;
            while (minX - 1 >= sx0 && colHas(f, minX - 1, minY, maxY)) minX--;
            while (maxX + 1 <= sx1 && colHas(f, maxX + 1, minY, maxY)) maxX++;
            while (minY - 1 >= T && rowHas(f, minY - 1, minX, maxX)) minY--;
            while (maxY + 1 <= B && rowHas(f, maxY + 1, minX, maxX)) maxY++;
            boxes[i] = new int[]{minX, minY, maxX, maxY};
        }

        float s = estimateCell(boxes, c);
        if (s <= 0) return out;

        for (int i = 0; i < 3; i++) {
            if (boxes[i] == null) continue;
            out[i] = buildPiece(f, boxes[i], s, i);
        }
        return out;
    }

    /** Подбираем размер маленькой клетки, чтобы все рамки делились на целое число клеток. */
    private static float estimateCell(int[][] boxes, Config c) {
        List<int[]> list = new ArrayList<>();
        for (int[] bx : boxes) if (bx != null) list.add(bx);
        if (list.isEmpty()) return 0;

        float lo, hi;
        if (c.trayCell > 0) {
            lo = c.trayCell * 0.92f;
            hi = c.trayCell * 1.08f;
        } else {
            // в Block Blast клетка в лотке ≈ 0.44 клетки поля
            lo = c.cell * 0.37f;
            hi = c.cell * 0.53f;
        }
        float stepS = Math.max(0.2f, c.cell * 0.004f);
        int nSteps = (int) ((hi - lo) / stepS) + 1;
        float[] errs = new float[nSteps];
        float bestErr = Float.MAX_VALUE;
        for (int k = 0; k < nSteps; k++) {
            float s = lo + k * stepS;
            float err = 0;
            for (int[] bx : list) {
                float w = (bx[2] - bx[0] + 1) / s, h = (bx[3] - bx[1] + 1) / s;
                int nw = Math.round(w), nh = Math.round(h);
                if (nw < 1 || nw > 5 || nh < 1 || nh > 5) {
                    err = Float.MAX_VALUE;
                    break;
                }
                err += Math.abs(w - nw) + Math.abs(h - nh);
            }
            errs[k] = err;
            if (err < bestErr) bestErr = err;
        }
        if (bestErr == Float.MAX_VALUE) return 0;
        // при почти равной ошибке берём больший размер клетки (меньше клеток в фигуре)
        float bestS = 0;
        for (int k = nSteps - 1; k >= 0; k--) {
            if (errs[k] <= bestErr + 0.05f) {
                bestS = lo + k * stepS;
                break;
            }
        }
        // учимся, если уверены
        if (c.trayCell == 0 && bestErr / list.size() < 0.15f) {
            c.trayCell = bestS;
        } else if (c.trayCell > 0 && bestErr / list.size() < 0.15f) {
            c.trayCell = c.trayCell * 0.9f + bestS * 0.1f;
        }
        return bestS;
    }

    private static Piece buildPiece(Frame f, int[] bx, float s, int slot) {
        int w = bx[2] - bx[0] + 1, h = bx[3] - bx[1] + 1;
        int nw = Math.max(1, Math.min(5, Math.round(w / s)));
        int nh = Math.max(1, Math.min(5, Math.round(h / s)));
        float cw = w / (float) nw, ch = h / (float) nh;
        long pat = 0;
        for (int rr = 0; rr < nh; rr++) {
            for (int cc = 0; cc < nw; cc++) {
                float cx = bx[0] + (cc + 0.5f) * cw, cy = bx[1] + (rr + 0.5f) * ch;
                int hits = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        if (block(f.get(cx + dx * cw * 0.22f, cy + dy * ch * 0.22f))) hits++;
                if (hits >= 5) pat |= 1L << (rr * 8 + cc);
            }
        }
        if (pat == 0) return null;

        // нормализуем к левому верхнему углу
        int minR = 9, minC = 9, maxR = -1, maxC = -1;
        for (int rr = 0; rr < nh; rr++)
            for (int cc = 0; cc < nw; cc++)
                if ((pat & (1L << (rr * 8 + cc))) != 0) {
                    minR = Math.min(minR, rr);
                    maxR = Math.max(maxR, rr);
                    minC = Math.min(minC, cc);
                    maxC = Math.max(maxC, cc);
                }
        long np = 0;
        for (int rr = minR; rr <= maxR; rr++)
            for (int cc = minC; cc <= maxC; cc++)
                if ((pat & (1L << (rr * 8 + cc))) != 0) np |= 1L << ((rr - minR) * 8 + (cc - minC));

        Piece p = new Piece();
        p.slot = slot;
        p.rows = maxR - minR + 1;
        p.cols = maxC - minC + 1;
        p.pattern = np;
        p.cx = bx[0] + (minC + p.cols / 2f) * cw;
        p.cy = bx[1] + (minR + p.rows / 2f) * ch;
        return p;
    }

    static boolean connected(long pat) {
        int start = Long.numberOfTrailingZeros(pat);
        long seen = 1L << start;
        int[] stack = new int[64];
        int sp = 0;
        stack[sp++] = start;
        while (sp > 0) {
            int i = stack[--sp];
            int rr = i / 8, cc = i % 8;
            int[] nb = {rr > 0 ? i - 8 : -1, rr < 7 ? i + 8 : -1, cc > 0 ? i - 1 : -1, cc < 7 ? i + 1 : -1};
            for (int n : nb) {
                if (n < 0) continue;
                long bit = 1L << n;
                if ((pat & bit) != 0 && (seen & bit) == 0) {
                    seen |= bit;
                    stack[sp++] = n;
                }
            }
        }
        return seen == pat;
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
