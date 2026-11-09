package com.autodraw.ig;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Trazos actuales compartidos entre la app y el servicio (y guardados en disco). */
public class DrawData {
    public static final String PREFS = "autodraw";
    private static PathExtractor.Result current;

    public static synchronized void set(Context ctx, PathExtractor.Result r) {
        current = r;
        File f = new File(ctx.getFilesDir(), "strokes.bin");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
            o.writeInt(r.w); o.writeInt(r.h); o.writeInt(r.strokes.size());
            for (float[] s : r.strokes) { o.writeInt(s.length); for (float v : s) o.writeFloat(v); }
        } catch (Exception ignored) { }
    }

    public static synchronized PathExtractor.Result get(Context ctx) {
        if (current != null) return current;
        File f = new File(ctx.getFilesDir(), "strokes.bin");
        if (!f.exists()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            int w = in.readInt(), h = in.readInt(), n = in.readInt();
            List<float[]> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                float[] s = new float[in.readInt()];
                for (int j = 0; j < s.length; j++) s[j] = in.readFloat();
                list.add(s);
            }
            current = new PathExtractor.Result(list, w, h);
            return current;
        } catch (Exception e) {
            return null;
        }
    }
}
