package com.autodraw.ig;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK = 1;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private ImageView preview;
    private TextView info, serviceState;
    private Bitmap source;
    private int job;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(DrawData.PREFS, MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.setBackgroundColor(Color.WHITE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("AutoDraw para Instagram");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        root.addView(button("1. Elegir imagen", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, PICK);
        }));

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setBackgroundColor(0xFFEEEEEE);
        preview.setMinimumHeight(dp(200));
        root.addView(preview, new LinearLayout.LayoutParams(-1, -2));

        info = new TextView(this);
        info.setTextColor(0xFF444444);
        info.setPadding(0, dp(6), 0, dp(6));
        root.addView(info);

        root.addView(section("Imagen"));
        root.addView(check("Dibujar contornos", "contours", true, true));
        root.addView(slider(root, "Detalle de contornos", "detail", 1, 100, 55, true));
        root.addView(slider(root, "Resolución", "res", 80, 400, 200, true));
        root.addView(slider(root, "Ignorar trazos más cortos que (px)", "minlen", 3, 40, 3, true));
        root.addView(check("Invertir colores (imágenes oscuras)", "invert", false, true));

        root.addView(section("Sombreado"));
        root.addView(check("Sombrear zonas oscuras", "hatch", false, true));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        rg.setOrientation(LinearLayout.HORIZONTAL);
        String[] modes = {"Horizontal", "Vertical", "Cruzado"};
        for (int i = 0; i < modes.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(modes[i]); rb.setId(1000 + i);
            rg.addView(rb);
        }
        rg.check(1000 + prefs.getInt("hmode", 0));
        rg.setOnCheckedChangeListener((g, id) -> { prefs.edit().putInt("hmode", id - 1000).apply(); recompute(); });
        root.addView(rg);
        root.addView(slider(root, "Oscuridad a sombrear", "hdark", 30, 230, 110, true));
        root.addView(slider(root, "Separación de líneas", "hspace", 2, 12, 4, true));

        root.addView(section("Posición"));
        root.addView(slider(root, "Tamaño en pantalla (%)", "size", 20, 100, 80, false));
        root.addView(slider(root, "Posición horizontal (%)", "hpos", 0, 100, 50, false));
        root.addView(slider(root, "Posición vertical (%)", "vpos", 0, 100, 50, false));
        root.addView(check("Espejo (voltear horizontal)", "mirror", false, false));

        root.addView(section("Dibujo"));
        root.addView(slider(root, "Velocidad (1 lenta – 20 rápida)", "speed", 1, 20, 8, false));
        root.addView(slider(root, "Pausa entre trazos (ms)", "gap", 0, 300, 30, false));
        root.addView(slider(root, "Cuenta atrás al empezar (s)", "countdown", 0, 10, 3, false));
        root.addView(slider(root, "Longitud máx. de trazo (×10 px, 0 = sin límite)", "maxlen", 0, 60, 0, false));
        root.addView(slider(root, "Repetir cada trazo (más grueso)", "repeat", 1, 3, 1, false));
        TextView tip = new TextView(this);
        tip.setTextColor(0xFF777777);
        tip.setText("Si Instagram se salta partes: baja la velocidad, sube la pausa o limita la longitud de trazo.");
        root.addView(tip);

        serviceState = new TextView(this);
        serviceState.setPadding(0, dp(12), 0, 0);
        root.addView(serviceState);
        root.addView(button("2. Activar servicio de accesibilidad", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("3. Abrir Instagram", v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.instagram.android");
            if (i != null) startActivity(i);
            else Toast.makeText(this, "Instagram no está instalado", Toast.LENGTH_SHORT).show();
        }));

        TextView help = new TextView(this);
        help.setTextColor(0xFF555555);
        help.setPadding(0, dp(12), 0, 0);
        help.setText("Cómo usarlo:\n"
                + "• En Instagram abre una historia y entra al pincel (elige color y grosor).\n"
                + "• En el botón flotante: ▢ muestra dónde se dibujará, ▶ empieza (cuenta 3 s), "
                + "⏸ pausa, ⟲ reinicia, ✥ arrastra el botón.\n"
                + "• Bajar volumen también pausa. No toques la pantalla mientras dibuja.\n"
                + "• Si el servicio aparece bloqueado (Android 13+): Ajustes › Apps › AutoDraw › ⋮ › "
                + "\"Permitir ajustes restringidos\" y vuelve a activarlo.");
        root.addView(help);

        setContentView(sv);

        File f = new File(getFilesDir(), "source.png");
        if (f.exists()) { source = BitmapFactory.decodeFile(f.getAbsolutePath()); recompute(); }
        else info.setText("Elige una imagen para empezar.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = serviceEnabled();
        serviceState.setText(on ? "Servicio: ACTIVADO ✔" : "Servicio: desactivado");
        serviceState.setTextColor(on ? 0xFF1B8E3E : 0xFFC62828);
    }

    private boolean serviceEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(s)) return false;
        String me = new ComponentName(this, DrawService.class).flattenToString();
        for (String part : s.split(":")) if (part.equalsIgnoreCase(me)) return true;
        return false;
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTextColor(0xFFD62976);
        t.setPadding(0, dp(18), 0, dp(2));
        return t;
    }

    private CheckBox check(String text, String key, boolean def, boolean reextract) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setChecked(prefs.getBoolean(key, def));
        c.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            if (reextract) recompute(); else updateInfo(DrawData.get(this));
        });
        return c;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View slider(LinearLayout root, String label, String key, int min, int max, int def, boolean reextract) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, 0);
        TextView t = new TextView(this);
        t.setTextColor(Color.BLACK);
        int val = prefs.getInt(key, def);
        t.setText(label + ": " + val);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(val - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { t.setText(label + ": " + (p + min)); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt(key, s.getProgress() + min).apply();
                if (reextract) recompute(); else updateInfo(DrawData.get(MainActivity.this));
            }
        });
        box.addView(t);
        box.addView(sb);
        return box;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        info.setText("Cargando imagen…");
        exec.execute(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / sample > 1600) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap bmp;
                try (InputStream in = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, o2); }
                if (bmp == null) throw new Exception("formato no soportado");
                try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "source.png"))) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                runOnUiThread(() -> { source = bmp; recompute(); });
            } catch (Exception e) {
                runOnUiThread(() -> info.setText("No se pudo abrir la imagen: " + e.getMessage()));
            }
        });
    }

    private void recompute() {
        if (source == null) return;
        final int my = ++job;
        final Bitmap src = source;
        PathExtractor.Options op = new PathExtractor.Options();
        op.edgeThreshold = Math.round(420 - prefs.getInt("detail", 55) * 3.9f); // 1 → poco, 100 → mucho
        op.maxDim = prefs.getInt("res", 200);
        op.minLen = prefs.getInt("minlen", 3);
        op.contours = prefs.getBoolean("contours", true);
        op.invert = prefs.getBoolean("invert", false);
        op.hatch = prefs.getBoolean("hatch", false);
        op.hatchMode = prefs.getInt("hmode", 0);
        op.hatchDark = prefs.getInt("hdark", 110);
        op.hatchSpacing = prefs.getInt("hspace", 4);
        info.setText("Procesando…");
        exec.execute(() -> {
            PathExtractor.Result r = PathExtractor.extract(src, op);
            Bitmap pv = PathExtractor.render(r, Math.max(1, 600 / Math.max(r.w, r.h)));
            runOnUiThread(() -> {
                if (my != job) return;
                DrawData.set(this, r);
                preview.setImageBitmap(pv);
                updateInfo(r);
            });
        });
    }

    private void updateInfo(PathExtractor.Result r) {
        if (r == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        float scale = sw * prefs.getInt("size", 80) / 100f / r.w;
        int speed = prefs.getInt("speed", 8), rep = prefs.getInt("repeat", 1);
        double ms = rep * (r.totalLength() * scale / (0.25 * speed) + r.strokes.size() * (prefs.getInt("gap", 30) + 25));
        int sec = (int) (ms / 1000);
        info.setText(r.strokes.size() + " trazos · tiempo aprox. " + (sec / 60) + " min " + (sec % 60) + " s");
        info.setGravity(Gravity.START);
    }
}
