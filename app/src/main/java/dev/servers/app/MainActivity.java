package dev.servers.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dev.servers.app.runtime.AppPrivateHome;
import dev.servers.app.runtime.RootfsInstaller;
import dev.servers.app.runtime.ServerRuntimeController;

/** Minimal UI: install Linux runtime, create servers, import files, start/stop, view log. */
public class MainActivity extends Activity {
    private static final int REQ_IMPORT = 41;
    private static final int REQ_NOTIF = 42;
    private static final String ROOTFS_NAME = "debian-trixie-arm64-box64-rootfs.tar.gz";
    private static final String SHA_NAME = "debian-trixie-arm64-box64-rootfs.sha256";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout serverList;
    private TextView runtimeStatus, logView;
    private ProgressBar progress;
    private Button installButton;
    private String importTarget;
    private String logServer;
    private volatile boolean installing;

    private final Runnable logTick = new Runnable() {
        @Override public void run() {
            refreshLog();
            ui.postDelayed(this, 2000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        int p = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text("ServerBox64", 24, true));
        runtimeStatus = text("", 15, false);
        root.addView(runtimeStatus);
        installButton = new Button(this);
        installButton.setOnClickListener(v -> installRuntime());
        root.addView(installButton);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        root.addView(text("Servers", 20, true));
        LinearLayout add = new LinearLayout(this);
        EditText name = new EditText(this);
        name.setHint("server name (a-z, 0-9, . _ -)");
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        Button create = new Button(this);
        create.setText("Create");
        create.setOnClickListener(v -> {
            String id = name.getText().toString().trim();
            if (!id.matches("[A-Za-z0-9._-]{1,64}") || id.startsWith(".")) {
                toast("Invalid name");
                return;
            }
            try {
                File dir = new File(AppPrivateHome.ensure(this).servers, id);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir failed");
                name.setText("");
                refreshServers();
            } catch (IOException e) {
                toast("Error: " + e.getMessage());
            }
        });
        add.addView(name);
        add.addView(create);
        root.addView(add);

        serverList = new LinearLayout(this);
        serverList.setOrientation(LinearLayout.VERTICAL);
        root.addView(serverList);

        root.addView(text("Log", 20, true));
        logView = text("(pick a server's Log button)", 12, false);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        root.addView(logView);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshRuntime();
        refreshServers();
        ui.post(logTick);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(logTick);
    }

    // ---------- runtime install ----------
    private boolean runtimeInstalled() {
        try {
            return new File(AppPrivateHome.ensure(this).rootfs, "usr/bin/box64").isFile();
        } catch (IOException e) {
            return false;
        }
    }

    private void refreshRuntime() {
        boolean ok = runtimeInstalled();
        runtimeStatus.setText(ok ? "Linux runtime: installed" : "Linux runtime: not installed");
        installButton.setText(ok ? "Reinstall / update runtime" : "Download & install runtime");
        installButton.setEnabled(!installing);
    }

    private void installRuntime() {
        final String base = BuildConfig.RUNTIME_BASE_URL;
        if (base.isEmpty()) {
            toast("RUNTIME_BASE_URL was not set at build time");
            return;
        }
        installing = true;
        installButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        new Thread(() -> {
            try {
                String sha = new String(readAll(open(base + "/" + SHA_NAME)), StandardCharsets.UTF_8)
                        .trim().split("\\s+")[0];
                HttpURLConnection c = open(base + "/" + ROOTFS_NAME);
                final long total = c.getContentLengthLong();
                try (InputStream in = new FilterInputStream(c.getInputStream()) {
                    long n;
                    int last = -1;
                    @Override public int read(byte[] buf, int off, int len) throws IOException {
                        int r = super.read(buf, off, len);
                        if (r > 0 && total > 0) {
                            n += r;
                            int pct = (int) (n * 100 / total);
                            if (pct != last) { last = pct; ui.post(() -> progress.setProgress(pct)); }
                        }
                        return r;
                    }
                }) {
                    RootfsInstaller.install(this, in, sha);
                }
                ui.post(() -> toast("Runtime installed"));
            } catch (Exception e) {
                ui.post(() -> toast("Install failed: " + e.getMessage()));
            } finally {
                installing = false;
                ui.post(() -> { progress.setVisibility(View.GONE); refreshRuntime(); });
            }
        }, "runtime-install").start();
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
        return c;
    }

    private static byte[] readAll(HttpURLConnection c) throws IOException {
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) o.write(b, 0, r);
            return o.toByteArray();
        }
    }

    // ---------- servers ----------
    private void refreshServers() {
        serverList.removeAllViews();
        File[] dirs;
        try {
            dirs = AppPrivateHome.ensure(this).servers.listFiles(File::isDirectory);
        } catch (IOException e) {
            return;
        }
        if (dirs == null) return;
        Arrays.sort(dirs);
        for (File d : dirs) {
            final String id = d.getName();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            row.addView(text(id, 17, true));
            LinearLayout btns = new LinearLayout(this);
            btns.addView(button("Files", v -> pickFiles(id)));
            btns.addView(button("Start", v -> startServer(id)));
            btns.addView(button("Stop", v -> stopServer(id)));
            btns.addView(button("Log", v -> { logServer = id; refreshLog(); }));
            row.addView(btns);
            serverList.addView(row);
        }
    }

    private void startServer(String id) {
        if (!runtimeInstalled()) { toast("Install the runtime first"); return; }
        try {
            ServerRuntimeController.start(this, id, null, true, 5);
            logServer = id;
        } catch (RuntimeException e) {
            toast("Error: " + e.getMessage());
        }
    }

    private void stopServer(String id) {
        try { ServerRuntimeController.stop(this, id); } catch (RuntimeException e) { toast("Error: " + e.getMessage()); }
    }

    private void refreshLog() {
        if (logServer == null) return;
        try {
            File f = new File(AppPrivateHome.ensure(this).logs, logServer + ".log");
            if (!f.isFile()) { logView.setText(logServer + ": no log yet"); return; }
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                long start = Math.max(0, r.length() - 6000);
                r.seek(start);
                byte[] b = new byte[(int) (r.length() - start)];
                r.readFully(b);
                logView.setText(new String(b, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { }
    }

    // ---------- import files / zip ----------
    private void pickFiles(String id) {
        importTarget = id;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || importTarget == null) return;
        final String id = importTarget;
        final java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) uris.add(data.getClipData().getItemAt(k).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        toast("Importing…");
        new Thread(() -> {
            try {
                File dir = new File(AppPrivateHome.ensure(this).servers, id).getCanonicalFile();
                for (Uri u : uris) importOne(u, dir);
                ui.post(() -> toast("Import finished"));
            } catch (Exception e) {
                ui.post(() -> toast("Import failed: " + e.getMessage()));
            }
        }, "import").start();
    }

    private void importOne(Uri uri, File dir) throws IOException {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
        }
        if (name == null) name = "file";
        name = new File(name).getName();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open " + name);
            if (name.toLowerCase().endsWith(".zip")) {
                unzip(in, dir);
            } else {
                copy(in, new File(dir, name));
            }
        }
    }

    private static void unzip(InputStream in, File dir) throws IOException {
        String root = dir.getPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                File out = new File(dir, e.getName()).getCanonicalFile();
                if (!out.getPath().startsWith(root)) throw new IOException("Unsafe zip entry: " + e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                copy(z, out);
            }
        }
    }

    private static void copy(InputStream in, File out) throws IOException {
        try (OutputStream o = new java.io.FileOutputStream(out)) {
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) o.write(b, 0, r);
        }
    }

    // ---------- helpers ----------
    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
