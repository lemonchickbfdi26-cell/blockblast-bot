package com.leon.blockbot;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.Random;

/**
 * Обучение во время игры:
 * 1) считает, какие фигуры реально выдаёт игра, и бережёт место под частые;
 * 2) подбирает веса стратегии: каждую вторую партию пробует слегка изменённые
 *    настройки и оставляет их, если они сыграли лучше.
 */
final class Learner {

    // какие веса учим: ровность, запас под фигуры, дырки, место под 3x3, комбо, удержание комбо
    static final String[] NAMES = {"ровн", "запас", "дыры", "3x3", "комбо", "серия"};
    private static final double[] DEFAULT = {5.0, 2.0, 10.0, 12.0, 5.0, 10.0};

    final long[] shapeCounts = new long[Solver.SHAPES.length];
    double[] best = DEFAULT.clone();
    double[] cand = DEFAULT.clone();
    double bestAvg = 0;   // средний результат лучших настроек
    int bestGames = 0;
    int games = 0;        // сколько партий сыграно с обучением
    int accepted = 0;     // сколько раз нашлись настройки лучше
    long record = 0;      // лучший результат бота (его собственный счёт)
    boolean candTurn = false;

    private final Random rnd = new Random();

    // ---------- фигуры ----------

    void onNewSet(Piece[] pieces) {
        for (Piece p : pieces) {
            if (p == null) continue;
            for (int i = 0; i < Solver.SHAPES.length; i++) {
                if (Solver.SHAPES[i][0] == p.pattern) {
                    shapeCounts[i]++;
                    break;
                }
            }
        }
        applyShapeWeights();
    }

    void applyShapeWeights() {
        int n = Solver.SHAPES.length;
        double total = 0;
        for (long c : shapeCounts) total += c + 1; // +1 — чтобы редкие не обнулялись
        for (int i = 0; i < n; i++) Solver.SHAPE_W[i] = n * (shapeCounts[i] + 1) / total;
    }

    // ---------- веса стратегии ----------

    /** Какие веса играть в новой партии. */
    void startGame() {
        if (bestGames >= 2) {
            candTurn = !candTurn;
        } else {
            candTurn = false; // сначала узнаём, как играют текущие лучшие
        }
        if (candTurn) {
            for (int i = 0; i < best.length; i++) {
                double v = best[i] * Math.exp(rnd.nextGaussian() * 0.2);
                cand[i] = Math.max(0.1, Math.min(60, v));
            }
            apply(cand);
        } else {
            apply(best);
        }
    }

    void endGame(long score) {
        games++;
        if (score > record) record = score;
        if (!candTurn) {
            bestGames++;
            bestAvg = bestGames == 1 ? score : bestAvg * 0.7 + score * 0.3;
        } else if (score > bestAvg * 1.1) {
            // новые настройки сыграли заметно лучше — берём их
            best = cand.clone();
            bestAvg = (bestAvg + score) / 2.0; // осторожно: одна партия может быть везением
            accepted++;
        }
    }

    static void apply(double[] w) {
        Solver.W_TRANS = w[0];
        Solver.W_FIT = w[1];
        Solver.W_HOLE = w[2];
        Solver.W_BIG = w[3];
        Solver.W_COMBO = w[4];
        Solver.W_KEEP = w[5];
    }

    String summary() {
        return String.format(Locale.US, "Обучение: партий %d, улучшений %d, рекорд %d", games, accepted, record);
    }

    void reset() {
        java.util.Arrays.fill(shapeCounts, 0);
        best = DEFAULT.clone();
        cand = DEFAULT.clone();
        bestAvg = 0;
        bestGames = 0;
        games = 0;
        accepted = 0;
        record = 0;
        candTurn = false;
        applyShapeWeights();
        apply(best);
    }

    // ---------- сохранение ----------

    static Learner load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("learn", Context.MODE_PRIVATE);
        Learner l = new Learner();
        String sc = p.getString("shapes", "");
        if (!sc.isEmpty()) {
            String[] parts = sc.split(",");
            for (int i = 0; i < parts.length && i < l.shapeCounts.length; i++) {
                try { l.shapeCounts[i] = Long.parseLong(parts[i]); } catch (Exception ignored) { }
            }
        }
        String bw = p.getString("best", "");
        if (!bw.isEmpty()) {
            String[] parts = bw.split(",");
            if (parts.length == l.best.length) {
                for (int i = 0; i < parts.length; i++) {
                    try { l.best[i] = Double.parseDouble(parts[i]); } catch (Exception ignored) { }
                }
            }
        }
        l.bestAvg = p.getFloat("bestAvg", 0f);
        l.bestGames = p.getInt("bestGames", 0);
        l.games = p.getInt("games", 0);
        l.accepted = p.getInt("accepted", 0);
        l.record = p.getLong("record", 0);
        l.applyShapeWeights();
        apply(l.best);
        return l;
    }

    void save(Context ctx) {
        StringBuilder sc = new StringBuilder();
        for (int i = 0; i < shapeCounts.length; i++) {
            if (i > 0) sc.append(',');
            sc.append(shapeCounts[i]);
        }
        StringBuilder bw = new StringBuilder();
        for (int i = 0; i < best.length; i++) {
            if (i > 0) bw.append(',');
            bw.append(String.format(Locale.US, "%.4f", best[i]));
        }
        ctx.getSharedPreferences("learn", Context.MODE_PRIVATE).edit()
                .putString("shapes", sc.toString())
                .putString("best", bw.toString())
                .putFloat("bestAvg", (float) bestAvg)
                .putInt("bestGames", bestGames)
                .putInt("games", games)
                .putInt("accepted", accepted)
                .putLong("record", record)
                .apply();
    }
}
