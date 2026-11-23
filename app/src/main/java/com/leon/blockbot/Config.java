package com.leon.blockbot;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки бота: калибровка поля, смещение пальца, скорость. */
final class Config {
    // центр левой верхней клетки поля и размер клетки (в пикселях экрана)
    float x0, y0, cell;
    // зона с тремя фигурами внизу
    float trayTop, trayBottom, trayLeft, trayRight;
    // где фигура оказывается относительно пальца при перетаскивании (в клетках поля)
    // как фигура едет за пальцем: piece = k * finger + b (в пикселях)
    float kx = 1f, ky = 1f, bx = 0f, by = 0f;
    boolean dragMeasured = false; // замерено, держа фигуру
    boolean offKnown = false;     // подтверждено по реально поставленной фигуре
    // размер клетки у маленьких фигур в лотке (в пикселях), 0 = ещё не выучен
    float trayCell = 0f;
    int speedIdx = 0;
    int panelX = 20, panelY = 80;

    // цвет пустой клетки (не сохраняется)
    int emptyRef = -1;

    boolean calibrated() {
        return cell > 4f;
    }

    static Config load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        Config c = new Config();
        c.x0 = p.getFloat("x0", 0f);
        c.y0 = p.getFloat("y0", 0f);
        c.cell = p.getFloat("cell", 0f);
        c.trayTop = p.getFloat("trayTop", 0f);
        c.trayBottom = p.getFloat("trayBottom", 0f);
        c.trayLeft = p.getFloat("trayLeft", 0f);
        c.trayRight = p.getFloat("trayRight", 0f);
        c.kx = p.getFloat("kx3", 1f);
        c.ky = p.getFloat("ky3", 1f);
        c.bx = p.getFloat("bx3", 0f);
        c.by = p.getFloat("by3", 0f);
        c.dragMeasured = p.getBoolean("dragMeasured3", false);
        c.offKnown = p.getBoolean("offKnown3", false);
        c.trayCell = p.getFloat("trayCell", 0f);
        c.speedIdx = p.getInt("speedIdx", 0);
        c.panelX = p.getInt("panelX", 20);
        c.panelY = p.getInt("panelY", 80);
        return c;
    }

    void save(Context ctx) {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putFloat("x0", x0).putFloat("y0", y0).putFloat("cell", cell)
                .putFloat("trayTop", trayTop).putFloat("trayBottom", trayBottom)
                .putFloat("trayLeft", trayLeft).putFloat("trayRight", trayRight)
                .putFloat("kx3", kx).putFloat("ky3", ky).putFloat("bx3", bx).putFloat("by3", by)
                .putBoolean("dragMeasured3", dragMeasured).putBoolean("offKnown3", offKnown)
                .putFloat("trayCell", trayCell)
                .putInt("speedIdx", speedIdx)
                .putInt("panelX", panelX).putInt("panelY", panelY)
                .apply();
    }
}
