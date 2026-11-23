package com.leon.blockbot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Маленькая карта на панели: что бот видит на поле и в лотке. */
final class MiniMap extends View {

    private volatile State state;
    private final Paint on = new Paint(), off = new Paint(), piece = new Paint();

    MiniMap(Context c) {
        super(c);
        on.setColor(Color.rgb(255, 190, 60));
        off.setColor(Color.argb(90, 255, 255, 255));
        piece.setColor(Color.rgb(90, 200, 255));
    }

    void setState(State s) {
        state = s;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        State s = state;
        float h = getHeight();
        float cs = h / 8f;
        float gap = Math.max(1f, cs * 0.12f);
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                boolean f = s != null && (s.board & (1L << (r * 8 + c))) != 0;
                cv.drawRect(c * cs, r * cs, (c + 1) * cs - gap, (r + 1) * cs - gap, f ? on : off);
            }
        }
        if (s == null) return;
        float pcs = h / 5f;
        float x0 = 8 * cs + cs;
        for (int i = 0; i < 3; i++) {
            Piece p = s.pieces[i];
            float bx = x0 + i * (5 * pcs + cs);
            if (p == null) continue;
            for (int r = 0; r < 5; r++)
                for (int c = 0; c < 5; c++)
                    if ((p.pattern & (1L << (r * 8 + c))) != 0)
                        cv.drawRect(bx + c * pcs, r * pcs, bx + (c + 1) * pcs - gap, (r + 1) * pcs - gap, piece);
        }
    }
}
