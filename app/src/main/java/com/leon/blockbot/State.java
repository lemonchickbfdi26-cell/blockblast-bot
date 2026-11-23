package com.leon.blockbot;

/** Что бот видит на экране: поле 8x8 и три слота с фигурами. */
final class State {
    long board;
    Piece[] pieces = new Piece[3];
    int uncertain;

    boolean sameAs(State o) {
        if (o == null || o.board != board) return false;
        for (int i = 0; i < 3; i++) {
            Piece a = pieces[i], b = o.pieces[i];
            if (a == null && b == null) continue;
            if (a == null || !a.sameAs(b)) return false;
        }
        return true;
    }
}
