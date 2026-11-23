package com.leon.blockbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Служба спец. возможностей: панель поверх игры, калибровка и сам бот. */
public class BotService extends AccessibilityService {

    static volatile BotService instance;

    private static final int[] SPEEDS = {1, 2, 5, 10};
    // варианты «насколько фигура выше пальца» (в клетках), пока бот не выучил точно
    private static final float[] PROBES = {-2f, -3f, -1f, -4f, 0f, -5f, -6f, 1f};

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private Config cfg;

    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView statusTv;
    private Button playBtn, speedBtn;
    private MiniMap map;
    private View calibView;

    private volatile boolean running;
    private Thread worker;

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = getSystemService(WindowManager.class);
        cfg = Config.load(this);
        showPanel();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        removeView(panel);
        removeView(calibView);
        super.onDestroy();
    }

    private void removeView(View v) {
        if (v == null) return;
        try { wm.removeView(v); } catch (Exception ignored) { }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ================= панель =================

    private Button mkBtn(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void showPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6), dp(4), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xDD14142A);
        bg.setCornerRadius(dp(10));
        panel.setBackground(bg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView handle = new TextView(this);
        handle.setText(" ≡ ");
        handle.setTextSize(22);
        handle.setTextColor(Color.WHITE);
        row.addView(handle);

        playBtn = mkBtn("▶");
        playBtn.setOnClickListener(v -> toggle());
        row.addView(playBtn);

        speedBtn = mkBtn(SPEEDS[cfg.speedIdx % SPEEDS.length] + "x");
        speedBtn.setOnClickListener(v -> {
            cfg.speedIdx = (cfg.speedIdx + 1) % SPEEDS.length;
            speedBtn.setText(SPEEDS[cfg.speedIdx] + "x");
            cfg.save(this);
        });
        row.addView(speedBtn);

        Button cal = mkBtn("⚙");
        cal.setOnClickListener(v -> startCalibration());
        row.addView(cal);

        panel.addView(row);

        statusTv = new TextView(this);
        statusTv.setTextColor(Color.WHITE);
        statusTv.setTextSize(11);
        statusTv.setMaxWidth(dp(220));
        statusTv.setPadding(dp(2), dp(4), dp(2), dp(4));
        statusTv.setText(cfg.calibrated() ? "Готов. Жми ▶" : "Сначала калибровка: жми ⚙");
        panel.addView(statusTv);

        map = new MiniMap(this);
        panel.addView(map, new LinearLayout.LayoutParams(dp(216), dp(48)));

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = cfg.panelX;
        panelLp.y = cfg.panelY;

        handle.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int px, py;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        px = panelLp.x;
                        py = panelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = px + (int) (e.getRawX() - sx);
                        panelLp.y = py + (int) (e.getRawY() - sy);
                        try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        cfg.panelX = panelLp.x;
                        cfg.panelY = panelLp.y;
                        cfg.save(BotService.this);
                        return true;
                }
                return false;
            }
        });

        wm.addView(panel, panelLp);
    }

    private void status(String s) {
        main.post(() -> {
            if (statusTv != null) statusTv.setText(s);
        });
    }

    // ================= калибровка =================

    private void startCalibration() {
        stopBot();
        if (calibView != null) return;
        panel.setVisibility(View.GONE);

        FrameLayout v = new FrameLayout(this);
        v.setBackgroundColor(0x22000000);
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(0xCC000000);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP;
        tlp.topMargin = dp(30);
        v.addView(t, tlp);

        final String[] texts = {
                "Калибровка 1/3\nТапни в центр ЛЕВОЙ ВЕРХНЕЙ клетки поля",
                "Калибровка 2/3\nТапни в центр ПРАВОЙ НИЖНЕЙ клетки поля",
                "Калибровка 3/3\nТапни чуть НИЖЕ фигур внизу экрана"
        };
        t.setText(texts[0]);
        final List<float[]> pts = new ArrayList<>();
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                pts.add(new float[]{e.getRawX(), e.getRawY()});
                if (pts.size() < 3) {
                    t.setText(texts[pts.size()]);
                } else {
                    finishCalibration(pts);
                }
            }
            return true;
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        calibView = v;
        wm.addView(v, lp);
    }

    private void finishCalibration(List<float[]> pts) {
        float[] a = pts.get(0), b = pts.get(1), t = pts.get(2);
        float cell = ((b[0] - a[0]) + (b[1] - a[1])) / 14f;
        removeView(calibView);
        calibView = null;
        panel.setVisibility(View.VISIBLE);
        if (cell < 5) {
            status("Калибровка не получилась: тапай сначала левый верхний угол, потом правый нижний. Жми ⚙ ещё раз");
            return;
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        cfg.x0 = a[0];
        cfg.y0 = a[1];
        cfg.cell = cell;
        cfg.trayTop = b[1] + cell * 0.7f;
        cfg.trayBottom = Math.max(cfg.trayTop + cell, t[1]);
        cfg.trayLeft = Math.max(0, a[0] - cell);
        cfg.trayRight = Math.min(dm.widthPixels - 1, b[0] + cell);
        cfg.emptyRef = -1;
        cfg.trayCell = 0;
        cfg.dragMeasured = false;
        cfg.offKnown = false;
        cfg.save(this);
        status("Калибровка сохранена ✔ Жми ▶");
    }

    // ================= бот =================

    private void toggle() {
        if (running) stopBot();
        else startBot();
    }

    private void startBot() {
        if (running) return;
        running = true;
        playBtn.setText("⏸");
        worker = new Thread(this::loop, "bot");
        worker.start();
    }

    private void stopBot() {
        running = false;
        if (playBtn != null) playBtn.setText("▶");
        status("Пауза");
    }

    private int speed() {
        return SPEEDS[cfg.speedIdx % SPEEDS.length];
    }

    private static void sleep(long ms) {
        try { Thread.sleep(Math.max(1, ms)); } catch (InterruptedException ignored) { }
    }

    private State readStable(CaptureService cap, long minSeq) {
        long start = System.currentTimeMillis();
        State prev = null;
        int sp = speed();
        long gap = Math.max(30, 140 / sp);
        for (int i = 0; i < 60 && running; i++) {
            Frame f = cap.grab();
            if (f == null) { sleep(50); continue; }
            if (f.seq <= minSeq && System.currentTimeMillis() - start < 500) { sleep(25); continue; }
            State s = Vision.read(f, cfg);
            if (s != null && prev != null && s.sameAs(prev)) return s;
            prev = s;
            sleep(gap);
        }
        return null;
    }

    private void loop() {
        boolean hasPending = false;
        long pendingBoard = 0;
        Piece pendingPiece = null;
        int pr = 0, pc = 0;
        int probeIdx = 0, misses = 0, measureFails = 0;
        long minSeq = 0;
        int moves = 0;

        status("Старт…");
        while (running) {
            CaptureService cap = CaptureService.instance;
            if (cap == null) {
                status("Бот не видит экран: открой приложение бота → кнопка 2");
                sleep(1000);
                continue;
            }
            if (!cfg.calibrated()) {
                status("Сначала калибровка: жми ⚙");
                sleep(1000);
                continue;
            }

            State st = readStable(cap, minSeq);
            if (!running) break;
            if (st == null) {
                status("Не вижу поле… (проверь калибровку ⚙)");
                sleep(300);
                continue;
            }
            map.setState(st);

            // --- проверяем, куда встала прошлая фигура, и учимся ---
            if (hasPending) {
                hasPending = false;
                if (st.board == pendingBoard) {
                    misses++;
                    if (cfg.dragMeasured && misses >= 2) {
                        cfg.dragMeasured = false; // перемерим
                        misses = 0;
                    } else if (!cfg.dragMeasured) {
                        probeIdx = (probeIdx + 1) % PROBES.length;
                        cfg.kx = 1; cfg.ky = 1; cfg.bx = 0;
                        cfg.by = PROBES[probeIdx] * cfg.cell;
                    }
                } else {
                    int[] err = landingError(pendingBoard, st.board, pendingPiece, pr, pc);
                    if (err != null) {
                        misses = 0;
                        cfg.by += err[0] * cfg.cell;
                        cfg.bx += err[1] * cfg.cell;
                        cfg.offKnown = true;
                        cfg.save(this);
                    }
                }
            }

            if (!cfg.dragMeasured && measureFails < 2) {
                status("Замеряю, как игра двигает фигуру…");
                if (measureDrag(cap, st)) {
                    measureFails = 0;
                    cfg.dragMeasured = true;
                    cfg.offKnown = false;
                    cfg.save(this);
                } else {
                    measureFails++;
                    cfg.kx = 1; cfg.ky = 1; cfg.bx = 0;
                    cfg.by = PROBES[probeIdx] * cfg.cell;
                }
                minSeq = cap.currentSeq();
                sleep(400);
                continue;
            }

            List<Piece> ps = new ArrayList<>();
            for (Piece p : st.pieces) if (p != null) ps.add(p);
            if (ps.isEmpty()) {
                status("Жду фигуры…");
                sleep(Math.max(80, 400 / speed()));
                continue;
            }

            Solver.Move m = Solver.best(st.board, ps);
            if (m == null) {
                status("Ходов нет 😢 (жду)");
                sleep(1000);
                continue;
            }
            Piece p = ps.get(m.idx);
            float tx = cfg.x0 + (m.c + (p.cols - 1) / 2f) * cfg.cell;
            float ty = cfg.y0 + (m.r + (p.rows - 1) / 2f) * cfg.cell;
            float fx = (tx - cfg.bx) / cfg.kx;
            float fy = (ty - cfg.by) / cfg.ky;

            moves++;
            status((cfg.offKnown ? "Ход " + moves : "Пристреливаюсь…")
                    + " · " + speed() + "x"
                    + (st.uncertain > 0 ? " · неуверенных клеток: " + st.uncertain : ""));

            int sp = speed();
            boolean ok = drag(p.cx, p.cy, fx, fy,
                    Math.max(20, 120 / sp), Math.max(40, 380 / sp), Math.max(20, 90 / sp));
            minSeq = cap.currentSeq();
            if (ok) {
                hasPending = true;
                pendingBoard = st.board;
                pendingPiece = p;
                pr = m.r;
                pc = m.c;
            }
            sleep(Math.max(60, 650 / sp));
        }
        main.post(() -> { if (playBtn != null) playBtn.setText("▶"); });
    }

    /** Где реально встала фигура относительно задуманного места (dr, dc), или null. */
    private static int[] landingError(long prev, long now, Piece p, int pr, int pc) {
        int bestD = Integer.MAX_VALUE, br = 0, bc = 0;
        for (int r = 0; r + p.rows <= 8; r++) {
            for (int c = 0; c + p.cols <= 8; c++) {
                if (!Solver.fits(prev, p.pattern, p.rows, p.cols, r, c)) continue;
                if (Solver.place(prev, p.pattern, r, c) != now) continue;
                int d = Math.abs(r - pr) + Math.abs(c - pc);
                if (d < bestD) { bestD = d; br = r; bc = c; }
            }
        }
        if (bestD != Integer.MAX_VALUE) return new int[]{br - pr, bc - pc};
        // запасной способ: линии не сгорели — смотрим рамку новых клеток
        if ((prev & ~now) == 0) {
            long added = now & ~prev;
            if (added == 0) return null;
            int minR = 9, minC = 9;
            for (int i = 0; i < 64; i++) {
                if ((added & (1L << i)) != 0) {
                    minR = Math.min(minR, i / 8);
                    minC = Math.min(minC, i % 8);
                }
            }
            return new int[]{minR - pr, minC - pc};
        }
        return null;
    }

    // ================= замер перетаскивания =================

    private boolean strokeTo(GestureDescription.StrokeDescription[] cur, float x0, float y0, float x1, float y1,
                             long ms, boolean cont) {
        Path p = new Path();
        p.moveTo(x0, y0);
        p.lineTo(x1, y1);
        GestureDescription.StrokeDescription s = cur[0] == null
                ? new GestureDescription.StrokeDescription(p, 0, ms, cont)
                : cur[0].continueStroke(p, 0, ms, cont);
        cur[0] = s;
        return dispatchSync(new GestureDescription.Builder().addStroke(s).build());
    }

    private Frame freshFrame(CaptureService cap, long afterSeq) {
        long t = System.currentTimeMillis();
        Frame f = cap.grab();
        while (f != null && f.seq <= afterSeq && System.currentTimeMillis() - t < 400) {
            sleep(20);
            f = cap.grab();
        }
        return f;
    }

    /** Ищет поднятую фигуру ниже поля: пиксели, которые стали «блоком». Возвращает её центр. */
    private float[] findHeld(Frame f, int[] base, int yTop, Piece p) {
        int minX = Integer.MAX_VALUE, maxX = -1, maxY = -1, count = 0;
        for (int y = yTop; y < f.h; y += 2) {
            for (int x = 0; x < f.w; x += 2) {
                int now = f.get(x, y);
                int was = base[(y - yTop) * f.w + x];
                if (Vision.dist(now, was) > 60 && Vision.isTrayBlock(now)) {
                    count++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (count < 8) return null;
        float w = maxX - minX + 2;
        if (w < p.cols * cfg.cell * 0.6f || w > p.cols * cfg.cell * 1.4f) return null;
        return new float[]{(minX + maxX + 1) / 2f, maxY + 1 - p.rows * cfg.cell / 2f};
    }

    /** Берём фигуру, двигаем палец внизу экрана и смотрим, где фигура. Потом кладём обратно. */
    private boolean measureDrag(CaptureService cap, State st) {
        Piece p = null;
        for (Piece q : st.pieces) {
            if (q == null) continue;
            if (p == null || q.rows < p.rows || (q.rows == p.rows && q.cellCount() < p.cellCount())) p = q;
        }
        if (p == null) return false;
        Frame f0 = cap.grab();
        if (f0 == null) return false;
        int W = f0.w, H = f0.h;
        int yTop = (int) (cfg.y0 + 7.5f * cfg.cell) + 6;
        if (yTop >= H - 10) return false;
        int[] base = new int[(H - yTop) * W];
        for (int y = yTop; y < H; y++)
            for (int x = 0; x < W; x += 2)
                base[(y - yTop) * W + x] = f0.get(x, y);

        float f1x = W * 0.3f, f1y = H - 30;
        float f2x = W * 0.7f, f2y = H - 30 - cfg.cell;
        GestureDescription.StrokeDescription[] cur = {null};
        float[] c1 = null, c2 = null;
        try {
            if (!strokeTo(cur, p.cx, p.cy, p.cx + 1, p.cy, 150, true)) return false;
            if (!strokeTo(cur, p.cx + 1, p.cy, f1x, f1y, 250, true)) return false;
            sleep(250);
            c1 = findHeld(freshFrame(cap, cap.currentSeq()), base, yTop, p);
            if (strokeTo(cur, f1x, f1y, f2x, f2y, 250, true)) {
                sleep(250);
                c2 = findHeld(freshFrame(cap, cap.currentSeq()), base, yTop, p);
                // назад в лоток и отпустить
                if (strokeTo(cur, f2x, f2y, p.cx, p.cy, 250, true)) {
                    strokeTo(cur, p.cx, p.cy, p.cx + 1, p.cy, 60, false);
                }
            }
        } catch (Exception e) {
            return false;
        }
        if (c1 == null) return false;
        float kx = 1f, ky = 1f;
        if (c2 != null) {
            kx = (c2[0] - c1[0]) / (f2x - f1x);
            ky = (c2[1] - c1[1]) / (f2y - f1y);
            if (kx < 0.5f || kx > 3f) kx = 1f;
            if (ky < 0.5f || ky > 3f) ky = kx;
        }
        cfg.kx = kx;
        cfg.ky = ky;
        cfg.bx = c1[0] - kx * f1x;
        cfg.by = c1[1] - ky * f1y;
        status("Замер: k=" + String.format("%.2f", kx) + "/" + String.format("%.2f", ky)
                + " сдвиг=" + Math.round(cfg.by) + "px");
        return true;
    }

    // ================= жесты =================

    private boolean dispatchSync(GestureDescription g) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        main.post(() -> {
            boolean sent = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) {
                    ok[0] = true;
                    latch.countDown();
                }

                @Override
                public void onCancelled(GestureDescription d) {
                    latch.countDown();
                }
            }, null);
            if (!sent) latch.countDown();
        });
        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return ok[0];
    }

    private float clampX(float x) {
        DisplayMetrics real = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(real);
        return Math.max(1, Math.min(real.widthPixels - 2, x));
    }

    private float clampY(float y) {
        DisplayMetrics real = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(real);
        return Math.max(1, Math.min(real.heightPixels - 2, y));
    }

    /** Нажать, подержать, перетащить, отпустить. */
    private boolean drag(float sx, float sy, float ex, float ey, long hold, long move, long endHold) {
        sx = clampX(sx);
        sy = clampY(sy);
        ex = clampX(ex);
        ey = clampY(ey);
        try {
            Path p1 = new Path();
            p1.moveTo(sx, sy);
            p1.lineTo(sx + 1, sy);
            GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(p1, 0, hold, true);
            if (dispatchSync(new GestureDescription.Builder().addStroke(s1).build())) {
                Path p2 = new Path();
                p2.moveTo(sx + 1, sy);
                p2.lineTo(ex, ey);
                GestureDescription.StrokeDescription s2 = s1.continueStroke(p2, 0, move, true);
                if (dispatchSync(new GestureDescription.Builder().addStroke(s2).build())) {
                    Path p3 = new Path();
                    p3.moveTo(ex, ey);
                    p3.lineTo(ex + 1, ey);
                    GestureDescription.StrokeDescription s3 = s2.continueStroke(p3, 0, endHold, false);
                    if (dispatchSync(new GestureDescription.Builder().addStroke(s3).build())) return true;
                }
            }
        } catch (Exception ignored) { }

        // запасной вариант: один простой свайп
        try {
            Path p = new Path();
            p.moveTo(sx, sy);
            p.lineTo(ex, ey);
            GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, hold + move + endHold);
            return dispatchSync(new GestureDescription.Builder().addStroke(s).build());
        } catch (Exception e) {
            return false;
        }
    }
}
