package com.autodraw.ig;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/** Convierte una imagen en trazos (polilíneas) listos para dibujar. */
public class PathExtractor {

    public static class Result {
        public final List<float[]> strokes; // cada trazo: x0,y0,x1,y1,... en píxeles de la imagen reducida
        public final int w, h;
        Result(List<float[]> s, int w, int h) { this.strokes = s; this.w = w; this.h = h; }

        public double totalLength() {
            double t = 0;
            for (float[] s : strokes)
                for (int i = 2; i < s.length; i += 2)
                    t += Math.hypot(s[i] - s[i - 2], s[i + 1] - s[i - 1]);
            return t;
        }
    }

    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};

    /**
     * @param maxDim        resolución de trabajo (lado mayor)
     * @param edgeThreshold umbral de borde (menor = más detalle)
     * @param hatch         añadir sombreado con líneas en zonas oscuras
     * @param hatchSpacing  separación entre líneas de sombreado
     * @param hatchDark     nivel de gris (0-255) por debajo del cual se sombrea
     */
    public static class Options {
        public int maxDim = 200, edgeThreshold = 200, hatchSpacing = 4, hatchDark = 110, minLen = 3;
        public int hatchMode = 0; // 0 horizontal, 1 vertical, 2 cruzado
        public boolean contours = true, hatch = false, invert = false;
    }

    public static Result extract(Bitmap src, Options op) {
        int maxDim = op.maxDim, edgeThreshold = op.edgeThreshold, hatchSpacing = op.hatchSpacing, hatchDark = op.hatchDark;
        float s = Math.min(1f, (float) maxDim / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(4, Math.round(src.getWidth() * s));
        int h = Math.max(4, Math.round(src.getHeight() * s));
        Bitmap b = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        if (b != src) b.recycle();

        // escala de grises (transparencia = blanco)
        int[] g = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i], a = c >>> 24;
            int y = (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
            g[i] = (y * a + 255 * (255 - a)) / 255;
            if (op.invert) g[i] = 255 - g[i];
        }
        // desenfoque 3x3 para reducir ruido
        int[] bl = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int sum = 0, n = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx >= 0 && yy >= 0 && xx < w && yy < h) { sum += g[yy * w + xx]; n++; }
                    }
                bl[y * w + x] = sum / n;
            }
        // Sobel
        boolean[] e = new boolean[w * h];
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                int tl = bl[(y - 1) * w + x - 1], t = bl[(y - 1) * w + x], tr = bl[(y - 1) * w + x + 1];
                int l = bl[y * w + x - 1], r = bl[y * w + x + 1];
                int dl = bl[(y + 1) * w + x - 1], d = bl[(y + 1) * w + x], dr = bl[(y + 1) * w + x + 1];
                int gx = (tr + 2 * r + dr) - (tl + 2 * l + dl);
                int gy = (dl + 2 * d + dr) - (tl + 2 * t + tr);
                e[y * w + x] = Math.abs(gx) + Math.abs(gy) > edgeThreshold;
            }
        thin(e, w, h);

        List<float[]> strokes = new ArrayList<>();
        if (op.contours)
            for (float[] st : trace(e, w, h)) {
                if (st.length / 2 < Math.max(3, op.minLen)) continue;
                float[] sim = simplify(st, 0.8f);
                if (sim.length >= 4) strokes.add(sim);
            }
        if (op.hatch) {
            int sp = Math.max(2, hatchSpacing);
            if (op.hatchMode == 0 || op.hatchMode == 2) hatchLines(strokes, bl, w, h, sp, hatchDark, false, op.minLen);
            if (op.hatchMode == 1 || op.hatchMode == 2) hatchLines(strokes, bl, w, h, sp, hatchDark, true, op.minLen);
        }
        return new Result(order(strokes), w, h);
    }

    /** Líneas de sombreado en zonas más oscuras que hatchDark (horizontales o verticales). */
    private static void hatchLines(List<float[]> out, int[] bl, int w, int h, int sp, int dark, boolean vertical, int minLen) {
        int lines = vertical ? w : h, len = vertical ? h : w;
        boolean flip = false;
        for (int a = sp / 2; a < lines; a += sp) {
            List<float[]> row = new ArrayList<>();
            int b = 0;
            while (b < len) {
                while (b < len && px(bl, w, vertical, a, b) >= dark) b++;
                int b0 = b;
                while (b < len && px(bl, w, vertical, a, b) < dark) b++;
                if (b - b0 >= Math.max(3, minLen))
                    row.add(vertical ? new float[]{a, b0, a, b - 1} : new float[]{b0, a, b - 1, a});
            }
            if (flip) for (int i = row.size() - 1; i >= 0; i--) { float[] r = row.get(i); out.add(new float[]{r[2], r[3], r[0], r[1]}); }
            else out.addAll(row);
            flip = !flip;
        }
    }

    private static int px(int[] bl, int w, boolean vertical, int a, int b) {
        return vertical ? bl[b * w + a] : bl[a * w + b];
    }

    /** Adelgazamiento Zhang-Suen: deja los bordes de 1 píxel de grosor. */
    private static void thin(boolean[] img, int w, int h) {
        boolean changed = true;
        List<Integer> del = new ArrayList<>();
        while (changed) {
            changed = false;
            for (int step = 0; step < 2; step++) {
                del.clear();
                for (int y = 1; y < h - 1; y++)
                    for (int x = 1; x < w - 1; x++) {
                        int i = y * w + x;
                        if (!img[i]) continue;
                        boolean p2 = img[i - w], p3 = img[i - w + 1], p4 = img[i + 1], p5 = img[i + w + 1],
                                p6 = img[i + w], p7 = img[i + w - 1], p8 = img[i - 1], p9 = img[i - w - 1];
                        boolean[] nb = {p2, p3, p4, p5, p6, p7, p8, p9, p2};
                        int bCount = 0, aCount = 0;
                        for (int k = 0; k < 8; k++) { if (nb[k]) bCount++; if (!nb[k] && nb[k + 1]) aCount++; }
                        if (bCount < 2 || bCount > 6 || aCount != 1) continue;
                        if (step == 0) { if (p2 && p4 && p6) continue; if (p4 && p6 && p8) continue; }
                        else { if (p2 && p4 && p8) continue; if (p2 && p6 && p8) continue; }
                        del.add(i);
                    }
                for (int i : del) img[i] = false;
                if (!del.isEmpty()) changed = true;
            }
        }
    }

    private static int neighbors(boolean[] e, int w, int h, int x, int y) {
        int n = 0;
        for (int k = 0; k < 8; k++) {
            int xx = x + DX[k], yy = y + DY[k];
            if (xx >= 0 && yy >= 0 && xx < w && yy < h && e[yy * w + xx]) n++;
        }
        return n;
    }

    /** Recorre los píxeles de borde y los convierte en polilíneas continuas. */
    private static List<float[]> trace(boolean[] e, int w, int h) {
        List<float[]> out = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++)
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    if (!e[y * w + x]) continue;
                    if (pass == 0 && neighbors(e, w, h, x, y) != 1) continue; // primero desde extremos
                    List<Integer> pts = new ArrayList<>();
                    int cx = x, cy = y, dir = 0;
                    e[cy * w + cx] = false;
                    pts.add(cx); pts.add(cy);
                    while (true) {
                        int found = -1;
                        for (int o = 0; o < 8 && found < 0; o++) {
                            int k = (dir + ((o % 2 == 0) ? o / 2 : -(o / 2 + 1)) + 8) % 8;
                            int xx = cx + DX[k], yy = cy + DY[k];
                            if (xx >= 0 && yy >= 0 && xx < w && yy < h && e[yy * w + xx]) found = k;
                        }
                        if (found < 0) break;
                        cx += DX[found]; cy += DY[found]; dir = found;
                        e[cy * w + cx] = false;
                        pts.add(cx); pts.add(cy);
                    }
                    if (pts.size() >= 6) { // al menos 3 puntos: descarta ruido
                        float[] f = new float[pts.size()];
                        for (int i = 0; i < f.length; i++) f[i] = pts.get(i);
                        out.add(f);
                    }
                }
        return out;
    }

    /** Ramer-Douglas-Peucker. */
    private static float[] simplify(float[] p, float eps) {
        int n = p.length / 2;
        if (n < 3) return p;
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        int[] stack = new int[n * 2];
        int sp = 0;
        stack[sp++] = 0; stack[sp++] = n - 1;
        while (sp > 0) {
            int b = stack[--sp], a = stack[--sp];
            float ax = p[a * 2], ay = p[a * 2 + 1], bx = p[b * 2], by = p[b * 2 + 1];
            double len = Math.hypot(bx - ax, by - ay);
            double best = -1; int bi = -1;
            for (int i = a + 1; i < b; i++) {
                double d = len < 1e-6 ? Math.hypot(p[i * 2] - ax, p[i * 2 + 1] - ay)
                        : Math.abs((bx - ax) * (ay - p[i * 2 + 1]) - (ax - p[i * 2]) * (by - ay)) / len;
                if (d > best) { best = d; bi = i; }
            }
            if (best > eps) { keep[bi] = true; stack[sp++] = a; stack[sp++] = bi; stack[sp++] = bi; stack[sp++] = b; }
        }
        int c = 0;
        for (boolean k : keep) if (k) c++;
        float[] r = new float[c * 2];
        int j = 0;
        for (int i = 0; i < n; i++) if (keep[i]) { r[j++] = p[i * 2]; r[j++] = p[i * 2 + 1]; }
        return r;
    }

    /** Ordena los trazos (vecino más cercano) para reducir saltos. */
    private static List<float[]> order(List<float[]> in) {
        List<float[]> rest = new ArrayList<>(in), out = new ArrayList<>(in.size());
        float cx = 0, cy = 0;
        while (!rest.isEmpty()) {
            int bi = 0; boolean rev = false; double best = Double.MAX_VALUE;
            for (int i = 0; i < rest.size(); i++) {
                float[] s = rest.get(i);
                double ds = sq(s[0] - cx, s[1] - cy), de = sq(s[s.length - 2] - cx, s[s.length - 1] - cy);
                if (ds < best) { best = ds; bi = i; rev = false; }
                if (de < best) { best = de; bi = i; rev = true; }
            }
            float[] s = rest.remove(bi);
            if (rev) {
                float[] r = new float[s.length];
                for (int i = 0; i < s.length; i += 2) { r[i] = s[s.length - 2 - i]; r[i + 1] = s[s.length - 1 - i]; }
                s = r;
            }
            out.add(s);
            cx = s[s.length - 2]; cy = s[s.length - 1];
        }
        return out;
    }

    private static double sq(double a, double b) { return a * a + b * b; }

    /** Dibuja la vista previa. */
    public static Bitmap render(Result r, int scale) {
        Bitmap bmp = Bitmap.createBitmap(r.w * scale, r.h * scale, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setStrokeWidth(Math.max(1f, scale * 0.8f));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (float[] s : r.strokes)
            for (int i = 2; i < s.length; i += 2)
                c.drawLine(s[i - 2] * scale, s[i - 1] * scale, s[i] * scale, s[i + 1] * scale, p);
        return bmp;
    }
}
