package com.leon.blockbot;

/** Фигура в лотке. pattern — биты в сетке 8 шириной: бит (r*8+c). */
final class Piece {
    int slot;
    int rows, cols;
    long pattern;
    float cx, cy; // центр фигуры на экране (куда ставить палец)

    boolean sameAs(Piece o) {
        if (o == null) return false;
        return rows == o.rows && cols == o.cols && pattern == o.pattern
                && Math.abs(cx - o.cx) < 5 && Math.abs(cy - o.cy) < 5;
    }

    int cellCount() {
        return Long.bitCount(pattern);
    }
}
