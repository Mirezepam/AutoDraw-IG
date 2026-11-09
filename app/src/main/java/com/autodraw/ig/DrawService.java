package com.autodraw.ig;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Servicio de accesibilidad: muestra el botón flotante y dibuja los trazos con gestos. */
public class DrawService extends AccessibilityService {

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout panel;
    private WindowManager.LayoutParams plp;
    private TextView status;
    private View frame;

    private boolean running;
    private int idx;
    private List<float[]> strokes;
    private float scale, left, top, boxW, boxH, pxPerMs;
    private long gapMs;

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildPanel();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView btn(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC222222);
        bg.setCornerRadius(dp(22));
        panel.setBackground(bg);

        TextView handle = btn("✥", null);
        panel.addView(handle);
        panel.addView(btn("▶", v -> start()));
        panel.addView(btn("⏸", v -> pause("Pausado")));
        panel.addView(btn("⟲", v -> { pause("Reiniciado"); idx = 0; }));
        panel.addView(btn("▢", v -> toggleFrame()));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setPadding(dp(4), 0, dp(12), 0);
        status.setText("AutoDraw");
        panel.addView(status);

        plp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        plp.gravity = Gravity.TOP | Gravity.START;
        plp.x = dp(8);
        plp.y = dp(90);

        handle.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = plp.x; oy = plp.y; return true;
                    case MotionEvent.ACTION_MOVE:
                        plp.x = ox + (int) (e.getRawX() - sx); plp.y = oy + (int) (e.getRawY() - sy);
                        wm.updateViewLayout(panel, plp); return true;
                }
                return false;
            }
        });
        wm.addView(panel, plp);
    }

    private void setStatus(String s) { if (status != null) status.setText(s); }

    /** Calcula dónde y a qué escala se dibuja en la pantalla. */
    private boolean layout() {
        PathExtractor.Result r = DrawData.get(this);
        if (r == null || r.strokes.isEmpty()) {
            Toast.makeText(this, "Primero elige una imagen en la app AutoDraw", Toast.LENGTH_LONG).show();
            return false;
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        SharedPreferences p = getSharedPreferences(DrawData.PREFS, MODE_PRIVATE);
        int size = p.getInt("size", 80), vpos = p.getInt("vpos", 50), hpos = p.getInt("hpos", 50);
        int speed = p.getInt("speed", 8), maxLen = p.getInt("maxlen", 0), repeat = p.getInt("repeat", 1);
        boolean mirror = p.getBoolean("mirror", false);
        float sw = dm.widthPixels, sh = dm.heightPixels;
        scale = sw * size / 100f / r.w;
        if (r.h * scale > sh * 0.8f) scale = sh * 0.8f / r.h;
        boxW = r.w * scale; boxH = r.h * scale;
        left = Math.max(0, Math.min(sw - boxW, sw * hpos / 100f - boxW / 2f));
        top = Math.max(0, Math.min(sh - boxH, sh * vpos / 100f - boxH / 2f));
        pxPerMs = 0.25f * speed;              // velocidad del trazo (px por ms)
        gapMs = p.getInt("gap", 30);          // pausa entre trazos
        countdown = p.getInt("countdown", 3); // segundos antes de empezar
        strokes = prepare(r, mirror, maxLen > 0 ? maxLen * 10 / scale : 0, Math.max(1, repeat));
        return true;
    }

    private int countdown = 3;

    /** Aplica espejo, parte trazos largos y repite trazos (coordenadas de imagen). */
    private static List<float[]> prepare(PathExtractor.Result r, boolean mirror, float maxLen, int repeat) {
        List<float[]> out = new java.util.ArrayList<>();
        for (float[] s0 : r.strokes) {
            float[] s = s0;
            if (mirror) { s = s0.clone(); for (int i = 0; i < s.length; i += 2) s[i] = r.w - s[i]; }
            List<float[]> pieces = new java.util.ArrayList<>();
            if (maxLen <= 0) pieces.add(s);
            else {
                java.util.ArrayList<Float> cur = new java.util.ArrayList<>();
                cur.add(s[0]); cur.add(s[1]);
                float acc = 0;
                for (int i = 2; i < s.length; i += 2) {
                    float px = s[i - 2], py = s[i - 1], x = s[i], y = s[i + 1];
                    float seg = (float) Math.hypot(x - px, y - py);
                    while (acc + seg > maxLen && seg > 0) { // cortar dentro del segmento
                        float t = (maxLen - acc) / seg;
                        float cx = px + (x - px) * t, cy = py + (y - py) * t;
                        cur.add(cx); cur.add(cy);
                        pieces.add(toArr(cur));
                        cur.clear(); cur.add(cx); cur.add(cy);
                        seg -= maxLen - acc; px = cx; py = cy; acc = 0;
                    }
                    cur.add(x); cur.add(y); acc += seg;
                }
                if (cur.size() >= 4) pieces.add(toArr(cur));
            }
            for (float[] pc : pieces) for (int k = 0; k < repeat; k++) out.add(pc);
        }
        return out;
    }

    private static float[] toArr(List<Float> l) {
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private void toggleFrame() {
        if (frame != null) { wm.removeView(frame); frame = null; return; }
        if (!layout()) return;
        frame = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setStroke(dp(2), 0xFFFF2D55, dp(8), dp(6));
        frame.setBackground(d);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                (int) boxW, (int) boxH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) left; lp.y = (int) top;
        wm.addView(frame, lp);
    }

    private void start() {
        if (running) return;
        if (!layout()) return;
        if (idx >= strokes.size()) idx = 0;
        running = true;
        final int[] count = {countdown};
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                if (count[0] > 0) { setStatus("Empieza en " + count[0]); count[0]--; h.postDelayed(this, 1000); }
                else next();
            }
        };
        h.post(tick);
    }

    private void pause(String msg) {
        running = false;
        h.removeCallbacksAndMessages(null);
        setStatus(msg + (strokes != null ? " " + idx + "/" + strokes.size() : ""));
    }

    private void next() {
        if (!running) return;
        if (idx >= strokes.size()) { running = false; idx = 0; setStatus("¡Terminado! ✔"); return; }
        float[] s = strokes.get(idx);
        Path path = new Path();
        float x0 = Math.max(0, left + s[0] * scale), y0 = Math.max(0, top + s[1] * scale);
        path.moveTo(x0, y0);
        double len = 0;
        float px = x0, py = y0;
        for (int i = 2; i < s.length; i += 2) {
            float x = Math.max(0, left + s[i] * scale), y = Math.max(0, top + s[i + 1] * scale);
            path.lineTo(x, y);
            len += Math.hypot(x - px, y - py);
            px = x; py = y;
        }
        if (s.length == 2) path.lineTo(x0 + 1, y0);
        long dur = Math.max(12, Math.min(GestureDescription.getMaxGestureDuration() - 100, (long) (len / pxPerMs)));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, dur)).build();
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gd) {
                idx++;
                if (idx % 5 == 0 || idx == strokes.size()) setStatus(idx + "/" + strokes.size());
                h.postDelayed(DrawService.this::next, gapMs);
            }
            @Override public void onCancelled(GestureDescription gd) {
                pause("Interrumpido");
            }
        }, h);
        if (!ok) pause("Error al dibujar");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (running && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) pause("Pausado");
            return true;
        }
        return false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { pause("Pausado"); }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        try { if (panel != null) wm.removeView(panel); } catch (Exception ignored) { }
        try { if (frame != null) wm.removeView(frame); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
