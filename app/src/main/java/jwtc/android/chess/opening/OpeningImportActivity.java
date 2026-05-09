package jwtc.android.chess.opening;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.ScrollView;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jwtc.android.chess.R;
import jwtc.android.chess.lichess.LichessApi;
import jwtc.android.chess.lichess.LichessService;
import jwtc.android.chess.lichess.OAuth2AuthCodePKCE;

/**
 * Simple activity to list Lichess studies and import one into local storage.
 * This is an initial implementation focused on debugging / inspection.
 */
public class OpeningImportActivity extends Activity implements AdapterView.OnItemClickListener {

    private static final String TAG = "OpeningImportActivity";

    private TextView textStatus;
    private TextView textLog;
    private ProgressBar progressBar;
    private ListView listView;
    private Button buttonImportUrl;
    private Button buttonImportPgn;
    private Button buttonLogCopy;
    private Button buttonLogClear;

    private android.widget.ScrollView logScrollView;

    private SimpleAdapter adapter;
    private ArrayList<HashMap<String, String>> data = new ArrayList<>();

    private LichessApi lichessApi;
    private boolean serviceConnected = false;
    private boolean offlineMode = false;
    private String lichessUsername;

    private static final int REQUEST_PICK_PGN_FILE = 2001;
    private static final String PGN_FILE_REF_PREFIX = "@file:";
    private static final int PGN_INLINE_MAX_CHARS = 256 * 1024;
    private static final Pattern STUDY_URL_PATTERN = Pattern.compile("(?i)https?://(www\\.)?lichess\\.org/study/([a-zA-Z0-9]{8})(/.*)?");
    /** Holds PGN text loaded from file, to be consumed by the import dialog. */
    private String pendingFilePgn = null;
    private EditText pendingPgnEditText = null;
    private TextView pendingFileStatusText = null;

    private String saveLargePgnToInternalFile(String studyId, String pgn) {
        if (studyId == null || pgn == null) return null;
        try {
            File dir = new File(getFilesDir(), "opening_pgn");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File out = new File(dir, studyId + ".pgn");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(pgn.getBytes(StandardCharsets.UTF_8));
            }
            return PGN_FILE_REF_PREFIX + out.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "saveLargePgnToInternalFile failed", e);
            return null;
        }
    }

    private static String decodeBytesPreferUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        // BOM detection first.
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2
                && (bytes[0] & 0xFF) == 0xFE
                && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        // Strict UTF-8; if invalid then fallback.
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer cb = decoder.decode(ByteBuffer.wrap(bytes));
            return cb.toString();
        } catch (CharacterCodingException ignored) {
        }
        // Common fallback for legacy TXT exports on Windows/Android tools.
        return new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
    }

    private String resolveStoredPgn(String stored) {
        if (stored == null) return "";
        if (!stored.startsWith(PGN_FILE_REF_PREFIX)) {
            return stored;
        }
        String path = stored.substring(PGN_FILE_REF_PREFIX.length());
        if (path.isEmpty()) return "";
        try (InputStream is = new java.io.FileInputStream(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8), 64 * 1024)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[32 * 1024];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "resolveStoredPgn failed path=" + path, e);
            return "";
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            serviceConnected = true;
            appendLog("Service connected: LichessService");
            LichessService lichessService = ((LichessService.LocalBinder) service).getService();
            lichessApi.setAuth(lichessService.getAuth());
            appendLog("Auth attached to LichessApi");
            fetchRemoteStudies();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceConnected = false;
            appendLog("Service disconnected: LichessService");
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_PGN_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                readPgnFileAsync(data.getData());
            }
            return;
        }
        // Forward OAuth login results when user re-authenticates from this screen.
        if (data != null) {
            try {
                lichessApi.handleLoginData(data);
                appendLog("Login result received. Refreshing studies...");
                fetchRemoteStudies();
            } catch (Exception e) {
                appendLog("ERROR handleLoginData: " + e.getMessage());
            }
        }
    }

    private void readPgnFileAsync(Uri uri) {
        appendLog("Reading file: " + uri.toString());
        if (pendingFileStatusText != null) {
            pendingFileStatusText.setText(R.string.opening_import_pgn_file_reading);
        }
        progressBar.setVisibility(View.VISIBLE);

        new Thread(() -> {
            String result = null;
            long size = 0;
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    byte[] raw = bos.toByteArray();
                    result = decodeBytesPreferUtf8(raw);
                    size = raw.length;
                    is.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "readPgnFileAsync failed", e);
            }
            final String pgn = result;
            final long bytes = size;
            new Handler(Looper.getMainLooper()).post(() -> {
                progressBar.setVisibility(View.GONE);
                if (pgn != null && !pgn.trim().isEmpty()) {
                    pendingFilePgn = pgn;
                    String sizeStr = bytes > 1024 * 1024
                            ? String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                            : String.format("%.1f KB", bytes / 1024.0);
                    String fileName = uri.getLastPathSegment();
                    if (fileName == null) fileName = "file";
                    String msg = getString(R.string.opening_import_pgn_file_loaded, fileName, sizeStr);
                    appendLog(msg);
                    if (pendingFileStatusText != null) {
                        pendingFileStatusText.setText(msg);
                    }
                    if (pendingPgnEditText != null) {
                        int previewLen = Math.min(pgn.length(), 2000);
                        String preview = pgn.substring(0, previewLen);
                        if (previewLen < pgn.length()) {
                            preview += "\n\n… (" + sizeStr + " total, truncated in preview)";
                        }
                        pendingPgnEditText.setText(preview);
                    }
                } else {
                    appendLog(getString(R.string.opening_import_pgn_file_error));
                    if (pendingFileStatusText != null) {
                        pendingFileStatusText.setText(R.string.opening_import_pgn_file_error);
                    }
                }
            });
        }).start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.opening_import);

        textStatus = findViewById(R.id.TextViewOpeningStatus);
        textLog = findViewById(R.id.TextViewOpeningLog);
        progressBar = findViewById(R.id.ProgressBarOpening);
        listView = findViewById(R.id.ListViewStudies);
        buttonImportUrl = findViewById(R.id.ButtonImportStudyUrl);
        buttonImportPgn = findViewById(R.id.ButtonImportStudyPgn);
        buttonLogCopy = findViewById(R.id.ButtonOpeningLogCopy);
        buttonLogClear = findViewById(R.id.ButtonOpeningLogClear);
        logScrollView = findViewById(R.id.ScrollViewOpeningLog);

        lichessUsername = getIntent().getStringExtra("lichess_username");

        adapter = new SimpleAdapter(
                this,
                data,
                android.R.layout.simple_list_item_2,
                new String[]{"line1", "line2"},
                new int[]{android.R.id.text1, android.R.id.text2}
        );
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(this);
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= data.size()) {
                return true;
            }
            HashMap<String, String> map = data.get(position);
            if (!"1".equals(map.get("imported"))) {
                return true;
            }
            String studyId = map.get("id");
            String name = map.get("name");
            if (studyId == null) {
                return true;
            }

            new AlertDialog.Builder(OpeningImportActivity.this)
                    .setTitle("Delete imported study")
                    .setMessage("Delete \"" + (name != null ? name : studyId) + "\" from local storage?")
                    .setPositiveButton(android.R.string.ok, (d, w) -> deleteImportedStudy(studyId))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });

        // Create API instance; Auth will be provided by binding to LichessService.
        lichessApi = new LichessApi();

        loadLocalStudies();
        appendLog("Loaded local studies: " + data.size());

        if (buttonImportUrl != null) {
            buttonImportUrl.setOnClickListener(v -> showImportFromUrlDialog());
        }
        if (buttonImportPgn != null) {
            buttonImportPgn.setOnClickListener(v -> showImportFromPgnDialog());
        }

        if (buttonLogCopy != null) {
            buttonLogCopy.setOnClickListener(v -> copyProgressLogToClipboard());
        }
        if (buttonLogClear != null) {
            buttonLogClear.setOnClickListener(v -> {
                if (textLog != null) {
                    textLog.setText("");
                }
            });
        }
    }

    private void deleteImportedStudy(String studyId) {
        appendLog("Deleting studyId=" + studyId);
        int chapters = getContentResolver().delete(
                OpeningStudyProvider.CONTENT_URI_CHAPTERS,
                OpeningStudyProvider.COL_CHAPTER_STUDY_ID + "=?",
                new String[]{studyId}
        );
        int studies = getContentResolver().delete(
                OpeningStudyProvider.CONTENT_URI_STUDIES,
                OpeningStudyProvider.COL_STUDY_ID + "=?",
                new String[]{studyId}
        );
        appendLog("Deleted rows: chapters=" + chapters + " studies=" + studies);

        // Update list UI
        for (int i = data.size() - 1; i >= 0; i--) {
            HashMap<String, String> m = data.get(i);
            if (studyId.equals(m.get("id"))) {
                data.remove(i);
            }
        }
        adapter.notifyDataSetChanged();
        textStatus.setText(R.string.opening_import_loaded);
    }

    @Override
    protected void onStart() {
        super.onStart();
        offlineMode = !isNetworkAvailable();
        if (offlineMode) {
            progressBar.setVisibility(View.GONE);
            textStatus.setText(R.string.opening_import_loaded);
            appendLog("Offline mode: using local opening studies only");
            return;
        }
        appendLog("onStart: binding LichessService");
        // Ensure LichessService is running and bind to obtain Auth/token
        startService(new Intent(OpeningImportActivity.this, LichessService.class));
        bindService(new Intent(OpeningImportActivity.this, LichessService.class), mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (serviceConnected) {
            appendLog("onStop: unbinding LichessService");
            unbindService(mConnection);
            serviceConnected = false;
        }
    }

    private void loadLocalStudies() {
        // Preload any locally stored studies so we can show which are already imported.
        Cursor c = getContentResolver().query(
                OpeningStudyProvider.CONTENT_URI_STUDIES,
                null,
                null,
                null,
                null
        );
        if (c == null) {
            return;
        }
        try {
            while (c.moveToNext()) {
                String studyId = c.getString(c.getColumnIndexOrThrow(OpeningStudyProvider.COL_STUDY_ID));
                String name = c.getString(c.getColumnIndexOrThrow(OpeningStudyProvider.COL_STUDY_NAME));
                HashMap<String, String> map = new HashMap<>();
                map.put("id", studyId);
                map.put("name", name != null ? name : studyId);
                map.put("imported", "1");
                map.put("line1", map.get("name") + " (imported)");
                map.put("line2", studyId);
                data.add(map);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading local studies", e);
        } finally {
            c.close();
        }
        adapter.notifyDataSetChanged();
    }

    private boolean hasLocalStudy(String studyId) {
        for (HashMap<String, String> map : data) {
            if (studyId.equals(map.get("id"))) {
                return true;
            }
        }
        return false;
    }

    private void fetchRemoteStudies() {
        if (!isNetworkAvailable()) {
            progressBar.setVisibility(View.GONE);
            textStatus.setText(R.string.opening_import_loaded);
            appendLog("Remote study fetch skipped (offline). Local studies remain available.");
            return;
        }
        appendLog("Fetching remote studies v2… username=" + (lichessUsername != null ? lichessUsername : "null"));
        textStatus.setText(R.string.opening_import_loading);
        progressBar.setVisibility(View.VISIBLE);

        OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback = new OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject>() {
            @Override
            public void onSuccess(JsonObject json) {
                progressBar.setVisibility(View.GONE);
                try {
                    String studyId = json.has("id") ? json.get("id").getAsString() : null;
                    String name = json.has("name") ? json.get("name").getAsString() : studyId;
                    String url = json.has("url") ? json.get("url").getAsString() : null;

                    if (studyId == null || hasLocalStudy(studyId)) {
                        return;
                    }

                    HashMap<String, String> map = new HashMap<>();
                    map.put("id", studyId);
                    map.put("name", name != null ? name : studyId);
                    map.put("url", url != null ? url : "");
                    map.put("imported", "0");
                    map.put("line1", map.get("name"));
                    map.put("line2", studyId);
                    data.add(map);
                    adapter.notifyDataSetChanged();
                    textStatus.setText(R.string.opening_import_loaded);
                    appendLog("Study: " + studyId + " :: " + map.get("name"));
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse study json " + json, e);
                    appendLog("Parse error: " + e.getMessage());
                }
            }

            @Override
            public void onError(JsonObject error) {
                progressBar.setVisibility(View.GONE);
                Log.d(TAG, "listStudies error " + error);
                String extra = (error != null && error.has("error")) ? error.get("error").getAsString() : "";
                textStatus.setText(getString(R.string.opening_import_error) + (extra.isEmpty() ? "" : " (" + extra + ")"));
                appendLog("ERROR listStudies: " + (error != null ? error.toString() : "null"));
            }
        };

        if (lichessUsername != null && !lichessUsername.isEmpty()) {
            lichessApi.listStudiesForUser(lichessUsername, callback);
        } else {
            lichessApi.listStudies(callback);
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= data.size()) {
            return;
        }
        HashMap<String, String> map = data.get(position);
        final String studyId = map.get("id");
        final String name = map.get("name");
        if (studyId == null) {
            return;
        }

        String imported = map.get("imported");
        if ("1".equals(imported)) {
            // Already imported: show library view for this study
            showStudyLibraryDialog(studyId, name);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.opening_import_confirm_title)
                    .setMessage(getString(R.string.opening_import_confirm_message, name))
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> importStudy(studyId, name, map.get("url")))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    /**
     * Lichess returns HTTP 403 with body like {@code {"error":"This study is now private"}} when the study
     * is private and the OAuth token cannot read it (wrong account, or visibility changed).
     */
    private Integer describePrivateStudyExportError(JsonObject error) {
        if (error == null || !error.has("error")) {
            return null;
        }
        if (!"http_403".equals(error.get("error").getAsString()) || !error.has("body")) {
            return null;
        }
        String body = error.get("body").getAsString();
        if (body == null) {
            return null;
        }
        String lower = body.toLowerCase(Locale.US);
        if (lower.contains("private")) {
            return R.string.opening_import_error_private_study;
        }
        return null;
    }

    private void importStudy(String studyId, String studyName, String url) {
        textStatus.setText(R.string.opening_import_importing);
        progressBar.setVisibility(View.VISIBLE);
        appendLog("Importing: " + studyId + " :: " + studyName);

        lichessApi.exportStudyPgn(studyId, new OAuth2AuthCodePKCE.Callback<String, JsonObject>() {
            @Override
            public void onSuccess(String pgn) {
                progressBar.setVisibility(View.GONE);
                long now = System.currentTimeMillis();
                appendLog("Export PGN OK. Size=" + (pgn != null ? pgn.length() : 0));

                // Insert or update study row
                ContentValues studyValues = new ContentValues();
                studyValues.put(OpeningStudyProvider.COL_STUDY_ID, studyId);
                studyValues.put(OpeningStudyProvider.COL_STUDY_NAME, studyName);
                studyValues.put(OpeningStudyProvider.COL_STUDY_URL, url);
                studyValues.put(OpeningStudyProvider.COL_STUDY_IMPORTED_AT, now);
                getContentResolver().insert(OpeningStudyProvider.CONTENT_URI_STUDIES, studyValues);

                // For now, store whole PGN as a single synthetic chapter
                ContentValues chapterValues = new ContentValues();
                chapterValues.put(OpeningStudyProvider.COL_CHAPTER_ID, studyId + "_all");
                chapterValues.put(OpeningStudyProvider.COL_CHAPTER_NAME, studyName);
                chapterValues.put(OpeningStudyProvider.COL_CHAPTER_STUDY_ID, studyId);
                chapterValues.put(OpeningStudyProvider.COL_CHAPTER_PGN, pgn);
                chapterValues.put(OpeningStudyProvider.COL_CHAPTER_IMPORTED_AT, now);
                getContentResolver().insert(OpeningStudyProvider.CONTENT_URI_CHAPTERS, chapterValues);

                textStatus.setText(R.string.opening_import_done);
                appendLog("Saved to DB: studies + chapters");

                // Update list immediately (for URL import and list import).
                upsertImportedStudyInList(studyId, studyName, url);
            }

            @Override
            public void onError(JsonObject error) {
                progressBar.setVisibility(View.GONE);
                Log.d(TAG, "exportStudyPgn error " + error);
                appendLog("ERROR exportStudyPgn: " + (error != null ? error.toString() : "null"));

                Integer privateHint = describePrivateStudyExportError(error);
                if (privateHint != null) {
                    textStatus.setText(privateHint);
                    appendLog(getString(privateHint));
                } else {
                    textStatus.setText(R.string.opening_import_error);
                }

                if (error != null && error.has("error") && "http_401".equals(error.get("error").getAsString())) {
                    showReLoginDialog();
                }
            }
        });
    }

    private void upsertImportedStudyInList(String studyId, String studyName, String url) {
        if (studyId == null) return;

        boolean found = false;
        for (int i = 0; i < data.size(); i++) {
            HashMap<String, String> map = data.get(i);
            if (studyId.equals(map.get("id"))) {
                found = true;
                map.put("name", studyName != null ? studyName : studyId);
                map.put("url", url != null ? url : map.get("url"));
                map.put("imported", "1");
                map.put("line1", map.get("name") + " (imported)");
                map.put("line2", studyId);
                break;
            }
        }

        if (!found) {
            HashMap<String, String> map = new HashMap<>();
            map.put("id", studyId);
            map.put("name", studyName != null ? studyName : studyId);
            map.put("url", url != null ? url : "");
            map.put("imported", "1");
            map.put("line1", map.get("name") + " (imported)");
            map.put("line2", studyId);
            data.add(0, map);
        }

        adapter.notifyDataSetChanged();
        textStatus.setText(R.string.opening_import_loaded);
        appendLog("List updated: imported=" + studyId);
    }

    private void showReLoginDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Lichess authorization required")
                .setMessage("This request was rejected (401). If you recently enabled study import/export, you must login again to grant the required 'study:read'/'study:write' scopes, or your token may have expired.\n\nTap Login to re-authorize, then try again.")
                .setPositiveButton(R.string.button_login, (d, w) -> {
                    try {
                        lichessApi.login(OpeningImportActivity.this);
                    } catch (Exception e) {
                        appendLog("ERROR re-login: " + e.getMessage());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showImportFromPgnDialog() {
        pendingFilePgn = null;

        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (16 * density);
        int scrollHeight = (int) (280 * density);

        EditText editPgn = new EditText(this);
        editPgn.setHint(R.string.opening_import_pgn_hint);
        editPgn.setSingleLine(false);
        editPgn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editPgn.setFilters(new InputFilter[0]);
        editPgn.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        editPgn.setHorizontallyScrolling(false);

        pendingPgnEditText = editPgn;

        ScrollView scrollPgn = new ScrollView(this);
        scrollPgn.setFillViewport(true);
        scrollPgn.addView(editPgn, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.addView(scrollPgn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                scrollHeight));

        TextView fileStatusLabel = new TextView(this);
        fileStatusLabel.setTextSize(12);
        fileStatusLabel.setPadding(0, (int)(4 * density), 0, (int)(4 * density));
        pendingFileStatusText = fileStatusLabel;

        Button buttonChooseFile = new Button(this);
        buttonChooseFile.setText(R.string.opening_import_pgn_choose_file);
        buttonChooseFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            String[] mimeTypes = {"text/plain", "application/x-chess-pgn", "application/octet-stream"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            startActivityForResult(intent, REQUEST_PICK_PGN_FILE);
        });

        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);
        fileRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        fileRow.addView(buttonChooseFile, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        fileRow.addView(fileStatusLabel, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));
        root.addView(fileRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText editName = new EditText(this);
        editName.setHint(R.string.opening_import_name_hint);
        editName.setSingleLine(true);
        root.addView(editName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        new AlertDialog.Builder(this)
                .setTitle(R.string.opening_import_pgn_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String pgn;
                    if (pendingFilePgn != null && !pendingFilePgn.trim().isEmpty()) {
                        pgn = pendingFilePgn;
                    } else {
                        pgn = editPgn.getText() != null ? editPgn.getText().toString().trim() : "";
                    }
                    String name = editName.getText() != null ? editName.getText().toString().trim() : "";
                    if (pgn.isEmpty()) {
                        appendLog(getString(R.string.opening_import_pgn_empty));
                        textStatus.setText(R.string.opening_import_error);
                        return;
                    }
                    if (name.isEmpty()) {
                        name = "Imported PGN";
                    }
                    importPgnStudy(pgn, name);
                    pendingFilePgn = null;
                    pendingPgnEditText = null;
                    pendingFileStatusText = null;
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    pendingFilePgn = null;
                    pendingPgnEditText = null;
                    pendingFileStatusText = null;
                })
                .show();
    }

    /**
     * Save pasted PGN locally (no Lichess API). Same DB shape as URL import — opens in library / trainer like other imports.
     */
    private void importPgnStudy(String pgn, String studyName) {
        if (pgn == null || pgn.trim().isEmpty()) {
            appendLog(getString(R.string.opening_import_pgn_empty));
            textStatus.setText(R.string.opening_import_error);
            return;
        }
        long now = System.currentTimeMillis();
        String studyId = "local_pgn_" + Long.toHexString(now);
        appendLog("Import PGN (local): " + studyName + " id=" + studyId + " bytes=" + pgn.length());

        ContentValues studyValues = new ContentValues();
        studyValues.put(OpeningStudyProvider.COL_STUDY_ID, studyId);
        studyValues.put(OpeningStudyProvider.COL_STUDY_NAME, studyName);
        studyValues.put(OpeningStudyProvider.COL_STUDY_URL, "pgn://local");
        studyValues.put(OpeningStudyProvider.COL_STUDY_IMPORTED_AT, now);
        getContentResolver().insert(OpeningStudyProvider.CONTENT_URI_STUDIES, studyValues);

        String storedPgn = pgn;
        if (pgn.length() > PGN_INLINE_MAX_CHARS) {
            String fileRef = saveLargePgnToInternalFile(studyId, pgn);
            if (fileRef != null) {
                storedPgn = fileRef;
                appendLog("Stored large PGN in file reference (chars=" + pgn.length() + ")");
            } else {
                appendLog("WARN: failed to save large PGN file, fallback to inline DB storage");
            }
        }

        ContentValues chapterValues = new ContentValues();
        chapterValues.put(OpeningStudyProvider.COL_CHAPTER_ID, studyId + "_all");
        chapterValues.put(OpeningStudyProvider.COL_CHAPTER_NAME, studyName);
        chapterValues.put(OpeningStudyProvider.COL_CHAPTER_STUDY_ID, studyId);
        chapterValues.put(OpeningStudyProvider.COL_CHAPTER_PGN, storedPgn);
        chapterValues.put(OpeningStudyProvider.COL_CHAPTER_IMPORTED_AT, now);
        getContentResolver().insert(OpeningStudyProvider.CONTENT_URI_CHAPTERS, chapterValues);

        textStatus.setText(R.string.opening_import_done);
        appendLog("Saved PGN import to DB");
        upsertImportedStudyInList(studyId, studyName, "pgn://local");
    }

    private void showImportFromUrlDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);

        EditText editUrl = new EditText(this);
        editUrl.setHint(R.string.opening_import_url_hint);
        editUrl.setSingleLine(false);
        root.addView(editUrl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        EditText editName = new EditText(this);
        editName.setHint(R.string.opening_import_name_hint);
        editName.setSingleLine(true);
        root.addView(editName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        new AlertDialog.Builder(this)
                .setTitle(R.string.opening_import_url_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = editUrl.getText() != null ? editUrl.getText().toString().trim() : "";
                    String name = editName.getText() != null ? editName.getText().toString().trim() : "";

                    String studyId = extractStudyIdFromUrl(url);
                    if (studyId == null) {
                        appendLog(getString(R.string.opening_import_url_invalid));
                        textStatus.setText(R.string.opening_import_error);
                        return;
                    }

                    if (name.isEmpty()) {
                        name = "Study " + studyId;
                    }

                    if (!serviceConnected) {
                        appendLog("ERROR importByUrl: service not connected yet");
                        return;
                    }

                    appendLog("Import from URL: " + url);
                    appendLog("Parsed studyId=" + studyId + " name=" + name);
                    importStudy(studyId, name, url);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String extractStudyIdFromUrl(String url) {
        if (url == null) return null;
        Matcher m = STUDY_URL_PATTERN.matcher(url.trim());
        if (m.matches()) {
            return m.group(2);
        }
        // Allow bare ID input too
        String t = url.trim();
        if (t.matches("^[a-zA-Z0-9]{8}$")) {
            return t;
        }
        return null;
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return false;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            Log.w(TAG, "Network check failed", e);
            return false;
        }
    }

    private void appendLog(String line) {
        Log.d(TAG, line);
        if (textLog == null) return;
        String current = textLog.getText() != null ? textLog.getText().toString() : "";
        if (current.equals(getString(R.string.opening_import_log_initial))) {
            current = "";
        }
        if (!current.isEmpty()) {
            current += "\n";
        }
        current += line;
        textLog.setText(current);

        // Keep the newest log entries visible.
        if (logScrollView != null) {
            logScrollView.post(() -> logScrollView.fullScroll(android.view.View.FOCUS_DOWN));
        }
    }

    private void copyProgressLogToClipboard() {
        if (textLog == null) return;
        String s = textLog.getText() != null ? textLog.getText().toString() : "";
        if (s.trim().isEmpty()) {
            s = "(empty)";
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Opening import log", s));
        }
        appendLog("Copied progress log to clipboard. chars=" + s.length());
    }

    private void showStudyLibraryDialog(String studyId, String studyName) {
        // Query all chapters for this study
        Cursor c = getContentResolver().query(
                OpeningStudyProvider.CONTENT_URI_CHAPTERS,
                null,
                OpeningStudyProvider.COL_CHAPTER_STUDY_ID + "=?",
                new String[]{studyId},
                null
        );
        if (c == null) {
            return;
        }
        try {
            if (!c.moveToFirst()) {
                return;
            }

            // For now we expect one synthetic chapter, but handle multiple generically.
            final ArrayList<String> chapterNames = new ArrayList<>();
            final ArrayList<String> chapterStoredPgns = new ArrayList<>();
            do {
                String chapterName = c.getString(c.getColumnIndexOrThrow(OpeningStudyProvider.COL_CHAPTER_NAME));
                chapterNames.add(chapterName != null ? chapterName : "Chapter");
                String stored = c.getString(c.getColumnIndexOrThrow(OpeningStudyProvider.COL_CHAPTER_PGN));
                chapterStoredPgns.add(stored != null ? stored : "");
            } while (c.moveToNext());

            if (chapterNames.size() == 1) {
                showPgnDialog(studyId, studyName, chapterNames.get(0), chapterStoredPgns.get(0));
            } else {
                CharSequence[] items = chapterNames.toArray(new CharSequence[0]);
                new AlertDialog.Builder(this)
                        .setTitle(studyName)
                        .setItems(items, (dialog, which) -> {
                            showPgnDialog(studyId, studyName, chapterNames.get(which), chapterStoredPgns.get(which));
                        })
                        .show();
            }
        } finally {
            c.close();
        }
    }

    private void showPgnDialog(String studyId, String studyName, String chapterName, String storedPgn) {
        final String pgn = resolveStoredPgn(storedPgn);
        TextView textView = new TextView(this);
        textView.setTextIsSelectable(true);
        String previewText = pgn;
        if (pgn.length() > 20000) {
            previewText = pgn.substring(0, 20000) + "\n\n... (preview truncated)";
        }
        textView.setText(previewText);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        textView.setPadding(padding, padding, padding, padding);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);

        // Custom container so we can have 4 actions: OK, Copy PGN, Play White, Play Black.
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );

        Button buttonOk = new Button(this);
        buttonOk.setText(android.R.string.ok);
        buttonRow.addView(buttonOk, buttonParams);

        Button buttonCopy = new Button(this);
        buttonCopy.setText(R.string.opening_library_copy);
        buttonCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("PGN", pgn);
                clipboard.setPrimaryClip(clip);
            }
        });
        buttonRow.addView(buttonCopy, buttonParams);

        Button buttonPlayWhite = new Button(this);
        buttonPlayWhite.setText(R.string.opening_library_play_white);
        buttonPlayWhite.setOnClickListener(v -> {
            Intent intent = new Intent(OpeningImportActivity.this, jwtc.android.chess.opening.OpeningTrainerActivity.class);
            intent.putExtra("study_name", studyName);
            intent.putExtra("chapter_name", chapterName);
            if (storedPgn != null && storedPgn.startsWith(PGN_FILE_REF_PREFIX)) {
                intent.putExtra("pgn_file_path", storedPgn.substring(PGN_FILE_REF_PREFIX.length()));
            } else {
                intent.putExtra("pgn", pgn);
            }
            intent.putExtra("play_as", "white");
            startActivity(intent);
        });
        buttonRow.addView(buttonPlayWhite, buttonParams);

        Button buttonPlayBlack = new Button(this);
        buttonPlayBlack.setText(R.string.opening_library_play_black);
        buttonPlayBlack.setOnClickListener(v -> {
            Intent intent = new Intent(OpeningImportActivity.this, jwtc.android.chess.opening.OpeningTrainerActivity.class);
            intent.putExtra("study_name", studyName);
            intent.putExtra("chapter_name", chapterName);
            if (storedPgn != null && storedPgn.startsWith(PGN_FILE_REF_PREFIX)) {
                intent.putExtra("pgn_file_path", storedPgn.substring(PGN_FILE_REF_PREFIX.length()));
            } else {
                intent.putExtra("pgn", pgn);
            }
            intent.putExtra("play_as", "black");
            startActivity(intent);
        });
        buttonRow.addView(buttonPlayBlack, buttonParams);

        Button buttonUpload = new Button(this);
        buttonUpload.setText(R.string.opening_library_upload_to_lichess);
        buttonUpload.setOnClickListener(v -> {
            if (!serviceConnected) {
                appendLog("ERROR export->lichess: service not connected yet");
                return;
            }
            if (lichessApi == null) return;
            showTargetStudyPickerAndUpload(studyId, chapterName, pgn);
        });
        buttonRow.addView(buttonUpload, buttonParams);

        container.addView(buttonRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(studyName + " - " + chapterName)
                .setView(container)
                .create();
        buttonOk.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showTargetStudyPickerAndUpload(String localStudyId, String chapterName, String pgn) {
        final ArrayList<HashMap<String, String>> remoteStudies = new ArrayList<>();
        for (HashMap<String, String> m : data) {
            if ("1".equals(m.get("imported"))) continue; // local imported marker, not a remote catalog item
            if (m.get("id") == null) continue;
            remoteStudies.add(m);
        }

        if (remoteStudies.isEmpty()) {
            appendLog("No remote studies loaded. Refreshing list first.");
            fetchRemoteStudies();
            return;
        }

        CharSequence[] items = new CharSequence[remoteStudies.size()];
        for (int i = 0; i < remoteStudies.size(); i++) {
            String id = remoteStudies.get(i).get("id");
            String name = remoteStudies.get(i).get("name");
            items[i] = (name != null ? name : id) + " (" + id + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.opening_library_choose_target_study)
                .setItems(items, (d, which) -> {
                    if (which < 0 || which >= remoteStudies.size()) return;
                    String targetStudyId = remoteStudies.get(which).get("id");
                    uploadChapterToStudy(localStudyId, targetStudyId, chapterName, pgn);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void uploadChapterToStudy(String localStudyId, String targetStudyId, String chapterName, String pgn) {
        if (targetStudyId == null || targetStudyId.trim().isEmpty()) {
            appendLog("Upload cancelled: empty target study id");
            return;
        }
        textStatus.setText(R.string.opening_library_uploading);
        progressBar.setVisibility(View.VISIBLE);
        appendLog("Uploading local studyId=" + localStudyId + " => target lichessStudyId=" + targetStudyId);

        lichessApi.importStudyPgn(targetStudyId, pgn, chapterName, new OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                // Verify target study is readable after import.
                lichessApi.exportStudyPgn(targetStudyId, new OAuth2AuthCodePKCE.Callback<String, JsonObject>() {
                    @Override
                    public void onSuccess(String pgnCheck) {
                        progressBar.setVisibility(View.GONE);
                        textStatus.setText(R.string.opening_library_upload_done);
                        appendLog("Upload done into existing study=" + targetStudyId);
                        appendLog("URL: https://lichess.org/study/" + targetStudyId);
                    }

                    @Override
                    public void onError(JsonObject verifyError) {
                        progressBar.setVisibility(View.GONE);
                        textStatus.setText(R.string.opening_library_upload_error);
                        appendLog("Upload response received, but verification failed for study="
                                + targetStudyId + " error=" + (verifyError != null ? verifyError.toString() : "null"));
                        appendLog("Raw import response: " + (result != null ? result.toString() : "null"));
                    }
                });
            }

            @Override
            public void onError(JsonObject error) {
                progressBar.setVisibility(View.GONE);
                String extra = (error != null && error.has("error")) ? error.get("error").getAsString() : "";
                textStatus.setText(R.string.opening_library_upload_error + (extra.isEmpty() ? "" : (" (" + extra + ")")));
                appendLog("Upload error: " + (error != null ? error.toString() : "null"));

                if (error != null && error.has("error") && "http_401".equals(error.get("error").getAsString())) {
                    showReLoginDialog();
                }
            }
        });
    }
}

