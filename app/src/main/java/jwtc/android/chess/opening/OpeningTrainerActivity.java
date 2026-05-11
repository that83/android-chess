package jwtc.android.chess.opening;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.MotionEvent;
import android.widget.ScrollView;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.android.material.switchmaterial.SwitchMaterial;

import jwtc.android.chess.R;
import jwtc.android.chess.activities.ChessBoardActivity;
import jwtc.android.chess.engine.EngineApi;
import jwtc.android.chess.engine.EngineListener;
import jwtc.android.chess.engine.LocalEngine;
import jwtc.android.chess.helpers.ActivityHelper;
import jwtc.android.chess.helpers.MoveRecyclerAdapter;
import jwtc.android.chess.helpers.ResultDialogListener;
import jwtc.android.chess.play.GameSettingsDialog;
import jwtc.android.chess.play.MenuDialog;
import jwtc.android.chess.services.GameApi;
import jwtc.android.chess.constants.PieceSets;
import jwtc.chess.JNI;
import jwtc.chess.Move;
import jwtc.chess.PGNEntry;
import jwtc.chess.board.BoardConstants;
import androidx.recyclerview.widget.RecyclerView;
import jwtc.android.chess.views.EvalBarView;
import jwtc.android.chess.views.OpeningLineProgressView;

public class OpeningTrainerActivity extends ChessBoardActivity implements EngineListener, ResultDialogListener<android.os.Bundle>, MoveRecyclerAdapter.OnItemClickListener {

    private static final String TAG = "OpeningTrainerActivity";

    private TextView textStatus;
    private ScrollView statusScrollView;
    /** Auto-scroll status log only if user already at bottom — avoids jerk + outer scroll trapping. */
    private boolean statusScrollFollowBottom = true;
    /** Ghi chú PGN dưới thanh tiến trình line. */
    private TextView textViewBookComment;
    private TextView lineDoneOverlay;
    private TextView fenTurnBoardFlash;
    private Runnable fenTurnFlashHideRunnable;
    private Button buttonHint;
    private Button buttonUndo;
    private Button buttonRestart;
    private Button buttonExit;
    private ImageButton buttonPlayBot;
    private ProgressBar progressBarBot;
    private ImageButton buttonMenu;
    private ImageButton buttonPrev;
    private ImageButton buttonNext;
    private ImageButton buttonEco;
    private TextView textViewEco;
    private TextView textViewWhitePieces;
    private TextView textViewBlackPieces;
    private RecyclerView historyRecyclerView;
    private MoveRecyclerAdapter moveAdapter;
    private com.google.android.material.switchmaterial.SwitchMaterial switchSound;
    private com.google.android.material.switchmaterial.SwitchMaterial switchSpeech;
    private com.google.android.material.switchmaterial.SwitchMaterial switchFlip;
    private com.google.android.material.switchmaterial.SwitchMaterial switchBlindfold;

    private static final int REQUEST_MENU = 5001;
    private static final int REQUEST_GAME_SETTINGS = 5002;
    private static final int LINE_DONE_OVERLAY_MS = 1000;
    private static final int FEN_TURN_FLASH_VISIBLE_MS = 500;
    private static final int FEN_TURN_FLASH_FADE_MS = 120;
    private static final String PREF_COMPLETED_LINES_PREFIX = "opening_trainer_completed_";
    private static final String PREF_OPENING_TRAINER_UI = "opening_trainer_ui";
    private static final String PREF_CHAPTER_SELECTION_PREFIX = "opening_trainer_chapter_selection_";
    /** New key so default Off applies after older installs had show_guide=true. */
    private static final String PREF_KEY_SHOW_GUIDE = "show_guide_default_off_v1";

    private OpeningMoveTree moveTree;
    private OpeningMoveTree.Node currentNode;
    private String playAs = "white";
    /** Side chosen on Play white / Play black (before FEN override, if any). */
    private String intentPlayAs = "white";
    /** Side-to-move label when chapter has FEN (flashed on board, not persisted in status log). */
    private String fenTurnBanner = null;
    private String lastDebugLine = "";
    /** Brace comment from PGN for the half-move that led to the current board position. */
    private String activeBookComment = "";
    /** Custom tag [Guide "..."] for the current chapter / game (Chessable export). */
    private String currentChapterGuide = "";
    /** When non-null, shown as the first line of the status log (e.g. wrong-move banner). */
    private String statusExtraFirstLine = null;

    private EngineApi engine;
    private boolean engineEnabled = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean engineMoveScheduled = false;
    private int evalGeneration = 0;
    private Runnable evalScheduledRunnable = null;
    private String lastEvalLine = "Engine eval: --";
    private float lastEvalCp = 0f;
    private EvalBarView evalBarView;
    private OpeningLineProgressView openingLineProgressView;

    private TextView textViewStudyLine;
    private SwitchMaterial switchAutoNext;
    private Button buttonOpeningNextLine;
    private SwitchMaterial switchShuffleLines;
    private SwitchMaterial switchShowGuide;
    private Button buttonSelectChapters;
    /** Per-chapter selection state (parallel to studyChaptersAll). null = all selected. */
    private boolean[] chapterSelectionMask = null;

    private boolean wrongMovePendingUndo = false;
    private int wrongMoveBoardNum = -1;

    // Eval baseline (relative display):
    // Some positions have a constant offset in native evaluation. We subtract the baseline
    // captured at the start of each opened line so user-facing eval/bar becomes ~0 at start.
    private float evalBaselineCpWhite = 0f;
    private boolean evalBaselineInitialized = false;

    // Study line selection (root-to-leaf lines, where each node follows the first child after the root choice).
    private int currentStudyLineIndex = 0; // 0-based
    private final Random rng = new Random();

    // Inside a chapter (study chunk), we enumerate all root-to-leaf paths and treat each path as one "line".
    private String currentChapterName = "";
    private int totalLinesInChapter = 0;
    private final ArrayList<ArrayList<OpeningMoveTree.Edge>> chapterLinesAll = new ArrayList<>();
    private final ArrayList<ArrayList<OpeningMoveTree.Edge>> chapterLinesOrdered = new ArrayList<>();
    private int currentChapterLineIndex = 0; // 0-based
    private ArrayList<OpeningMoveTree.Edge> currentLineEdges = new ArrayList<>();

    // Track completed "lines" (chapter index + line index within that chapter enumeration order).
    // Important: line order must be stable, so we don't shuffle chapterLinesOrdered in enumerate.
    private final HashSet<String> completedLineKeys = new HashSet<>();

    private static class StudyChapter {
        final String chapterName;
        final String chapterUrl;
        final String pgnChunk;
        /** Lichess-style custom start position; null = standard initial position. */
        final String startFen;
        final String persistentId;
        final int lineCount;
        /** Optional [Guide "..."] from PGN headers. */
        final String guideText;

        StudyChapter(String chapterName, String chapterUrl, String pgnChunk, String startFen, String persistentId, int lineCount, String guideText) {
            this.chapterName = chapterName;
            this.chapterUrl = chapterUrl;
            this.pgnChunk = pgnChunk;
            this.startFen = startFen;
            this.persistentId = persistentId;
            this.lineCount = Math.max(1, lineCount);
            this.guideText = guideText != null ? guideText : "";
        }
    }

    /** FEN for the chapter currently loaded (set in startStudyLine). */
    private String activeChapterStartFen = null;

    private static final Pattern PGN_FEN_TAG_PATTERN =
            Pattern.compile("\\[FEN\\s+\"([^\"]+)\"\\]", Pattern.CASE_INSENSITIVE);

    private static String extractStartFenFromChunk(String chunk) {
        if (chunk == null) return null;
        Matcher m = PGN_FEN_TAG_PATTERN.matcher(chunk);
        if (m.find()) {
            String fen = m.group(1).trim();
            return fen.isEmpty() ? null : fen;
        }
        return null;
    }

    /** FEN field 2: active color {@code w} or {@code b}. */
    private static Integer fenSideToMoveFromString(String fen) {
        if (fen == null) {
            return null;
        }
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        if ("w".equalsIgnoreCase(parts[1])) {
            return BoardConstants.WHITE;
        }
        if ("b".equalsIgnoreCase(parts[1])) {
            return BoardConstants.BLACK;
        }
        return null;
    }

    /**
     * Train the side to move in the chapter start position (overrides Play white/black when FEN present).
     */
    private void syncPlayAsFromChapterFen() {
        Integer stm = fenSideToMoveFromString(activeChapterStartFen);
        if (stm != null) {
            playAs = (stm == BoardConstants.WHITE) ? "white" : "black";
            fenTurnBanner = (stm == BoardConstants.WHITE)
                    ? getString(R.string.opening_trainer_fen_white_to_play)
                    : getString(R.string.opening_trainer_fen_black_to_play);
        } else {
            playAs = intentPlayAs;
            fenTurnBanner = null;
        }
    }

    private static String buildChapterPersistentId(String chapterName, String chapterUrl, String chunk) {
        String base = (chapterUrl != null && !chapterUrl.isEmpty())
                ? chapterUrl
                : ((chapterName != null ? chapterName : "") + "|" + (chunk != null ? Integer.toHexString(chunk.hashCode()) : "0"));
        return base;
    }

    private static int countLeafPathsInChunk(String chunk) {
        OpeningMoveTree tmpTree = new OpeningMoveTree();
        OpeningPgnParser.parseIntoTree(chunk, tmpTree);
        return countLeafPaths(tmpTree.getRoot());
    }

    private String sanitizeChapterNameForUi(String rawName, int fallbackIndex) {
        String name = rawName != null ? rawName : "";
        // Common mojibake marker when source encoding is imperfect.
        name = name.replace('\uFFFD', '-');
        name = name.replaceAll("\\s+", " ").trim();
        if (name.isEmpty() || "?".equals(name)) {
            return "Chapter " + (fallbackIndex + 1);
        }
        return name;
    }

    private String buildWhiteBlackChapterName(String chunk) {
        String white = OpeningPgnParser.extractBracketTagValue(chunk, "White");
        String black = OpeningPgnParser.extractBracketTagValue(chunk, "Black");
        String whiteTrim = white != null ? white.trim() : "";
        String blackTrim = black != null ? black.trim() : "";
        if (!whiteTrim.isEmpty() && !blackTrim.isEmpty()) {
            return whiteTrim + " - " + blackTrim;
        }
        return null;
    }

    private void loadChapterSelectionMaskFromPrefs() {
        if (currentStudyKey == null || currentStudyKey.isEmpty()) {
            chapterSelectionMask = null;
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREF_OPENING_TRAINER_UI, MODE_PRIVATE);
        String raw = prefs.getString(PREF_CHAPTER_SELECTION_PREFIX + currentStudyKey, null);
        if (raw == null || raw.isEmpty() || studyChaptersAll.isEmpty()) {
            chapterSelectionMask = null;
            return;
        }
        if (raw.length() != studyChaptersAll.size()) {
            chapterSelectionMask = null;
            return;
        }
        boolean[] loaded = new boolean[studyChaptersAll.size()];
        boolean hasSelected = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            loaded[i] = (c == '1');
            if (loaded[i]) hasSelected = true;
        }
        chapterSelectionMask = hasSelected ? loaded : null;
    }

    private void saveChapterSelectionMaskToPrefs() {
        if (currentStudyKey == null || currentStudyKey.isEmpty()) return;
        SharedPreferences prefs = getSharedPreferences(PREF_OPENING_TRAINER_UI, MODE_PRIVATE);
        String key = PREF_CHAPTER_SELECTION_PREFIX + currentStudyKey;
        if (chapterSelectionMask == null || chapterSelectionMask.length != studyChaptersAll.size()) {
            prefs.edit().remove(key).apply();
            return;
        }
        StringBuilder sb = new StringBuilder(chapterSelectionMask.length);
        boolean allSelected = true;
        boolean hasSelected = false;
        for (boolean selected : chapterSelectionMask) {
            sb.append(selected ? '1' : '0');
            if (!selected) allSelected = false;
            if (selected) hasSelected = true;
        }
        if (allSelected || !hasSelected) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, sb.toString()).apply();
        }
    }

    private String readPgnFromFilePath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(filePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8), 64 * 1024)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[32 * 1024];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read PGN from file path: " + filePath, e);
            return null;
        }
    }

    /** Split merged PGN (several games) on a blank line before a new {@code [} header block. */
    private static ArrayList<String> splitPgnIntoGames(String fullPgn) {
        ArrayList<String> out = new ArrayList<>();
        if (fullPgn == null || fullPgn.trim().isEmpty()) {
            return out;
        }
        String normalized = fullPgn.replace("\r\n", "\n").replace("\r", "\n");
        Pattern splitPat = Pattern.compile("\\n\\s*\\n(?=\\s*\\[)");
        String[] parts = splitPat.split(normalized);
        for (String part : parts) {
            if (part != null) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static int countLeafPaths(OpeningMoveTree.Node node) {
        if (node == null) return 1;
        List<OpeningMoveTree.Edge> edges = node.getEdges();
        if (edges == null || edges.isEmpty()) return 1;
        int sum = 0;
        for (OpeningMoveTree.Edge e : edges) {
            sum += countLeafPaths(e.child);
        }
        return Math.max(sum, 1);
    }

    private final ArrayList<StudyChapter> studyChaptersAll = new ArrayList<>();
    private final ArrayList<StudyChapter> studyChaptersOrdered = new ArrayList<>();
    private String currentStudyKey = "";
    private String currentChapterPersistentId = "";
    private int totalLinesInStudy = 0;
    private int requestedInitialChapterLineIndex = -1;

    private int totalEdgesWhite = 0;
    private int totalEdgesBlack = 0;
    private final Set<String> visitedEdgesWhite = new HashSet<>();
    private final Set<String> visitedEdgesBlack = new HashSet<>();

    private final Set<String> pendingEdgesWhite = new HashSet<>();
    private final Set<String> pendingEdgesBlack = new HashSet<>();
    private boolean hintUsedThisLine = false;

    private int linePly = 0;
    private int completedLines = 0;
    private boolean wrongMoveThisLine = false;

    private int attemptsWhite = 0;
    private int correctWhite = 0;
    private int attemptsBlack = 0;
    private int correctBlack = 0;

    private int hintStage = 0; // 0=none, 1=from highlighted, 2=from+to highlighted
    private int hintedFrom = -1;
    private int hintedTo = -1;
    private String hintedSan = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.opening_trainer);

            ActivityHelper.fixPaddings(this, findViewById(R.id.root_layout));

            textStatus = findViewById(R.id.TextViewOpeningTrainerStatus);
            statusScrollView = findViewById(R.id.ScrollViewOpeningTrainerStatus);
            lineDoneOverlay = findViewById(R.id.TextViewLineDoneOverlay);
            fenTurnBoardFlash = findViewById(R.id.TextViewFenTurnFlash);
            buttonHint = findViewById(R.id.ButtonOpeningHint);
            buttonUndo = findViewById(R.id.ButtonOpeningUndo);
            buttonRestart = findViewById(R.id.ButtonOpeningRestart);
            buttonExit = findViewById(R.id.ButtonOpeningExit);
            buttonPlayBot = findViewById(R.id.ButtonPlay);
            progressBarBot = findViewById(R.id.ProgressBarPlay);
            buttonMenu = findViewById(R.id.ButtonMenu);
            buttonPrev = findViewById(R.id.ButtonPrevious);
            buttonNext = findViewById(R.id.ButtonNext);
            buttonEco = findViewById(R.id.ButtonEco);
            textViewEco = findViewById(R.id.TextViewEco);
            textViewWhitePieces = findViewById(R.id.TextViewWhitePieces);
            textViewBlackPieces = findViewById(R.id.TextViewBlackPieces);
            evalBarView = findViewById(R.id.EvalBar);
            openingLineProgressView = findViewById(R.id.OpeningLineProgress);
            textViewBookComment = findViewById(R.id.TextViewOpeningBookComment);
            textViewStudyLine = findViewById(R.id.TextViewOpeningTrainerStudyLine);
            switchAutoNext = findViewById(R.id.SwitchAutoNext);
            buttonOpeningNextLine = findViewById(R.id.ButtonOpeningNextLine);
            switchShuffleLines = findViewById(R.id.SwitchShuffleLines);
            switchShowGuide = findViewById(R.id.SwitchShowGuide);
            buttonSelectChapters = findViewById(R.id.ButtonSelectChapters);
            historyRecyclerView = findViewById(R.id.HistoryRecyclerView);
            switchSound = findViewById(R.id.SwitchSound);
            switchSpeech = findViewById(R.id.SwitchSpeech);
            switchFlip = findViewById(R.id.SwitchFlip);
            switchBlindfold = findViewById(R.id.SwitchBlindfold);
            if (statusScrollView != null) {
                statusScrollView.setNestedScrollingEnabled(true);
                statusScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    statusScrollFollowBottom = isStatusInnerScrollNearBottom(scrollY);
                });
                statusScrollView.setOnTouchListener((v, event) -> {
                    // Keep vertical scroll gestures on log area, not the outer page ScrollView.
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return false;
                });
            }

            setStatusDebug("Trainer init v1: layout OK");

            playAs = getIntent().getStringExtra("play_as");
            if (playAs == null) {
                playAs = "white";
            }
            intentPlayAs = playAs;
            setStatusDebug("Trainer init v1: play_as=" + playAs);

            gameApi = new GameApi();
            afterCreate();
            setStatusDebug("Trainer init v1: afterCreate OK");

            engine = new LocalEngine();
            engine.setQuiescentSearchOn(false);
            engine.setPly(4);
            engine.addListener(this);

            // Wire up Play screen controls
            final MenuDialog menuDialog = new MenuDialog(this, this, REQUEST_MENU);
            if (buttonMenu != null) {
                buttonMenu.setOnClickListener(v -> menuDialog.show());
            }
            if (buttonPrev != null) {
                buttonPrev.setOnClickListener(v -> stepBackOneMove());
                buttonPrev.setOnLongClickListener(v -> {
                    if (wrongMovePendingUndo) {
                        undoWrongMoveIfPending();
                        return true;
                    }
                    gameApi.jumpToBoardNum(1);
                    syncTrainerStateFromBoard();
                    rebuildBoard();
                    updateStatus();
                    return true;
                });
            }
            if (buttonNext != null) {
                buttonNext.setOnClickListener(v -> {
                    if (wrongMovePendingUndo) {
                        undoWrongMoveIfPending();
                    } else {
                        advanceOneMoveForReview();
                    }
                });
                buttonNext.setOnLongClickListener(v -> {
                    if (wrongMovePendingUndo) {
                        undoWrongMoveIfPending();
                    } else {
                        advanceToEndOfLineForReview();
                    }
                    return true;
                });
            }
            if (historyRecyclerView != null) {
                moveAdapter = new MoveRecyclerAdapter(this, gameApi, this);
                historyRecyclerView.setAdapter(moveAdapter);
            }
            if (buttonEco != null) {
                // Not wired to ECO book in trainer for now; keep disabled.
                buttonEco.setEnabled(false);
            }
            if (textViewEco != null) {
                textViewEco.setText("--");
            }
            if (switchSound != null) {
                switchSound.setChecked(fVolume == 1.0f);
                switchSound.setOnCheckedChangeListener((btn, checked) -> fVolume = checked ? 1.0f : 0.0f);
            }
            if (switchSpeech != null) {
                switchSpeech.setChecked(moveToSpeech);
                switchSpeech.setOnCheckedChangeListener((btn, checked) -> moveToSpeech = checked);
            }
            if (switchFlip != null) {
                switchFlip.setChecked(false);
                switchFlip.setOnCheckedChangeListener((btn, checked) -> {
                    // Rotate view (in addition to play-as orientation)
                    boolean baseRot = "black".equals(playAs);
                    chessBoardView.setRotated(baseRot ^ checked);
                    rebuildBoard();
                    updateSelectedSquares();
                });
            }
            if (switchBlindfold != null) {
                switchBlindfold.setChecked(false);
                switchBlindfold.setOnCheckedChangeListener((btn, checked) -> {
                    PieceSets.selectedBlindfoldMode = checked ? PieceSets.BLINDFOLD_HIDE_PIECES : PieceSets.BLINDFOLD_SHOW_PIECES;
                    rebuildBoard();
                });
            }

            String pgn = getIntent().getStringExtra("pgn");
            if (pgn == null || pgn.isEmpty()) {
                String pgnFilePath = getIntent().getStringExtra("pgn_file_path");
                if (pgnFilePath != null && !pgnFilePath.trim().isEmpty()) {
                    pgn = readPgnFromFilePath(pgnFilePath);
                }
            }
            int pgnLen = pgn != null ? pgn.length() : -1;
            setStatusDebug("Trainer init v1: pgn_len=" + pgnLen);
            if (pgn == null || pgn.trim().isEmpty()) {
                throw new IllegalStateException("Empty PGN payload");
            }
            final String trainerPgn = pgn;

            moveTree = new OpeningMoveTree();
            OpeningPgnParser.parseIntoTree(trainerPgn, moveTree);
            currentNode = moveTree.getRoot();
            computeTotals();
            setStatusDebug("Trainer init v1: parseIntoTree OK, rootChildren=" + currentNode.getEdges().size()
                    + " totals W=" + totalEdgesWhite + " B=" + totalEdgesBlack);

            // Study line selection UI (chapters inside the study PGN).
            // We keep `pgn` as full study export; startStudyLine() will build a move tree per selected chapter.
            initStudyLinesOrder(trainerPgn);
            updateStudyLineUi();
            if (buttonOpeningNextLine != null) {
                buttonOpeningNextLine.setOnClickListener(v -> advanceToNextLineManual());
            }
            if (switchAutoNext != null) {
                switchAutoNext.setOnCheckedChangeListener((btn, checked) -> updateStudyLineUi());
            }
            if (switchShuffleLines != null) {
                switchShuffleLines.setOnCheckedChangeListener((btn, checked) -> {
                    initStudyLinesOrder(trainerPgn);
                    // Shuffle changes meaning of line index, so restart from the first line in the new order.
                    startStudyLine(/*lineIndex=*/0, /*resetCoverage=*/true);
                });
            }

            if (buttonSelectChapters != null) {
                buttonSelectChapters.setOnClickListener(v -> showChapterSelectionDialog());
            }

            SharedPreferences prefsUi = getSharedPreferences(PREF_OPENING_TRAINER_UI, MODE_PRIVATE);
            if (switchShowGuide != null) {
                switchShowGuide.setChecked(prefsUi.getBoolean(PREF_KEY_SHOW_GUIDE, false));
                switchShowGuide.setOnCheckedChangeListener((btn, checked) -> {
                    prefsUi.edit().putBoolean(PREF_KEY_SHOW_GUIDE, checked).apply();
                    updateBookCommentUi();
                });
            }

            startStudyLine(/*lineIndex=*/0, /*resetCoverage=*/true);
            setStatusDebug("Trainer init v1: startFromBeginning OK");

            buttonHint.setOnClickListener(v -> showHint());
            buttonUndo.setOnClickListener(v -> undoLastMove());
            buttonRestart.setOnClickListener(v -> {
                completedLineKeys.clear();
                completedLines = 0;
                saveCompletedLineKeysToPrefs();
                setStatusDebug("Reset done: all completed-line marks cleared");
                startStudyLine(/*lineIndex=*/0, /*resetCoverage=*/false);
            });
            buttonExit.setOnClickListener(v -> finish());

            if (buttonPlayBot != null) {
                updateBotButtonUi();
                buttonPlayBot.setOnClickListener(v -> {
                    // First tap enables engine takeover, subsequent taps request a bot move if it's bot turn.
                    if (!engineEnabled) {
                        engineEnabled = true;
                        appendEngineDebug("Engine takeover ENABLED");
                        updateBotButtonUi();
                    }
                    maybePlayBotIfNeeded(/*forced=*/true);
                });
                buttonPlayBot.setOnLongClickListener(v -> {
                    engineEnabled = false;
                    appendEngineDebug("Engine takeover DISABLED");
                    updateBotButtonUi();
                    if (progressBarBot != null) progressBarBot.setVisibility(View.GONE);
                    return true;
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "Opening trainer crashed onCreate", t);
            showFatalErrorDialog(t);
        }
    }

    @Override
    public void OnDialogResult(int requestCode, android.os.Bundle data) {
        if (requestCode == REQUEST_MENU) {
            String item = data != null ? data.getString("item") : null;
            if (item == null) return;
            if (item.equals(getString(R.string.menu_game_settings))) {
                GameSettingsDialog settingsDialog = new GameSettingsDialog(this, this, REQUEST_GAME_SETTINGS, getPrefs());
                settingsDialog.show();
            }
        } else if (requestCode == REQUEST_GAME_SETTINGS) {
            // Re-apply engine level prefs if user changed them in the dialog.
            int levelMode = getPrefs().getInt("levelMode", EngineApi.LEVEL_PLY);
            int levelPly = getPrefs().getInt("levelPly", 2);
            int levelTime = getPrefs().getInt("level", 2);
            boolean qOn = getPrefs().getBoolean("quiescentSearchOn", true);
            if (engine != null) {
                engine.setQuiescentSearchOn(qOn);
                if (levelMode == EngineApi.LEVEL_TIME) {
                    engine.setMsecs(levelTime);
                } else {
                    engine.setPly(levelPly);
                }
            }
            setStatusDebug("Engine settings updated");
            updateStatus();
        }
    }

    @Override
    public void onMoveItemClick(int position) {
        gameApi.jumpToBoardNum(position + 1);
        rebuildBoard();
        updateStatus();
    }

    @Override
    public void OnMove(int move) {
        super.OnMove(move);
        if (moveAdapter != null) {
            moveAdapter.update();
            if (historyRecyclerView != null) {
                historyRecyclerView.scrollToPosition(JNI.getInstance().getNumBoard() - 1);
            }
        }
        if (textViewWhitePieces != null) {
            textViewWhitePieces.setText(getPiecesDescription(BoardConstants.WHITE));
        }
        if (textViewBlackPieces != null) {
            textViewBlackPieces.setText(getPiecesDescription(BoardConstants.BLACK));
        }
        updateOpeningLineProgress();
    }

    private void startFromBeginning() {
        Log.d(TAG, "startFromBeginning");
        // Cancel any pending eval and invalidate previous eval threads.
        if (evalScheduledRunnable != null) {
            uiHandler.removeCallbacks(evalScheduledRunnable);
        }
        evalGeneration++;
        evalScheduledRunnable = null;

        // Reset visible eval UI.
        lastEvalLine = "Engine eval: --";
        lastEvalCp = 0f;
        evalBaselineCpWhite = 0f;
        evalBaselineInitialized = false;
        if (evalBarView != null) {
            evalBarView.setEvalCp(0f);
        }
        if (openingLineProgressView != null) {
            openingLineProgressView.setProgress(0f);
        }

        // Reset board + move history used by trainer UI.
        syncPlayAsFromChapterFen();
        applyChapterBoardStart();
        currentNode = moveTree.getRoot();
        visitedEdgesWhite.clear();
        visitedEdgesBlack.clear();
        pendingEdgesWhite.clear();
        pendingEdgesBlack.clear();
        hintUsedThisLine = false;
        wrongMoveThisLine = false;
        linePly = 0;
        resetHintHighlight();
        // Restart always returns to Opening mode (engine takeover off).
        engineEnabled = false;
        engineMoveScheduled = false;
        if (progressBarBot != null) progressBarBot.setVisibility(View.GONE);
        updateBotButtonUi();
        chessBoardView.setRotated("black".equals(playAs));
        activeBookComment = "";
        statusExtraFirstLine = null;
        updateStatus();
        rebuildBoard();
        playAutoOpeningMovesUntilUsersTurn();
        maybePlayBotIfNeeded(/*forced=*/false);
        flashFenTurnOnBoardIfNeeded();
    }

    private void recalibrateEvalBaselineIfNeeded() {
        if (evalBaselineInitialized) return;
        JNI jni = JNI.getInstance();
        int turn = jni.getTurn();
        int boardValue = jni.getBoardValue();
        float cpTurn = boardValue / 100.0f;
        float cpWhite = (turn == BoardConstants.WHITE) ? cpTurn : -cpTurn;
        evalBaselineCpWhite = cpWhite;
        evalBaselineInitialized = true;
    }

    /** Standard start, then optional chapter [FEN] / Lichess setup line. */
    private void applyChapterBoardStart() {
        gameApi.newGame();
        if (activeChapterStartFen != null && !activeChapterStartFen.trim().isEmpty()) {
            if (!gameApi.initFEN(activeChapterStartFen.trim(), true)) {
                Log.w(TAG, "initFEN failed; using standard start. FEN=" + activeChapterStartFen);
                gameApi.newGame();
            }
        }
    }

    /**
     * Play trainer-side moves (from the selected line) with no delay until it's the user's turn.
     * Needed for chapters that start from a FEN where the opponent moves first, and for "Play Black"
     * where White opens from the standard position.
     */
    private void playAutoOpeningMovesUntilUsersTurn() {
        if (engineEnabled) return;
        JNI jni = JNI.getInstance();
        for (int guard = 0; guard < 256; guard++) {
            if (jni.isEnded() != 0) {
                break;
            }
            int sideToMove = jni.getTurn();
            if (isUsersTurn(sideToMove)) {
                break;
            }
            if (currentNode == null || !currentNode.hasChildren()) {
                break;
            }
            OpeningMoveTree.Edge edge = getExpectedEdgeFromCurrentLine();
            if (edge == null) {
                break;
            }
            if (currentNode.getEdge(edge.sanNorm) == null) {
                setStatusDebug("Auto-open: line edge not at current node");
                break;
            }
            int move = findMoveForSanNorm(jni, edge.sanNorm);
            if (move == 0) {
                setStatusDebug("Auto-open: SAN not legal here: " + edge.san);
                break;
            }
            int sideBefore = jni.getTurn();
            gameApi.move(move, -1);
            recordEdge(sideBefore, currentNode, edge);
            currentNode = edge.child;
            linePly++;
            resetHintHighlight();
        }
        rebuildBoard();
        updateOpeningLineProgress();
        applyBookCommentAfterLineStateSync();
        updateStatus();
        recalibrateEvalBaselineIfNeeded();
        if (!engineEnabled) {
            scheduleEngineEval();
        }
    }

    /** Comment on the edge into the current position (uses line ply + selected line). */
    private void applyBookCommentAfterLineStateSync() {
        activeBookComment = "";
        if (currentLineEdges == null || currentLineEdges.isEmpty() || linePly <= 0) {
            return;
        }
        int idx = linePly - 1;
        if (idx < 0 || idx >= currentLineEdges.size()) {
            return;
        }
        OpeningMoveTree.Edge e = currentLineEdges.get(idx);
        if (e != null && e.comment != null && !e.comment.trim().isEmpty()) {
            activeBookComment = e.comment.trim();
        }
    }

    private void applyBookCommentFromEdge(OpeningMoveTree.Edge edge) {
        if (edge != null && edge.comment != null && !edge.comment.trim().isEmpty()) {
            activeBookComment = edge.comment.trim();
        } else {
            activeBookComment = "";
        }
    }

    private void initStudyLinesOrder(String fullPgn) {
        studyChaptersAll.clear();
        studyChaptersOrdered.clear();
        totalLinesInStudy = 0;

        ArrayList<StudyChapter> parsed = parseStudyChapters(fullPgn);
        if (parsed.isEmpty()) {
            String chunk = fullPgn != null ? fullPgn : "";
            if (OpeningPgnParser.chunkHasMoves(chunk)) {
                String id = buildChapterPersistentId("Line 1", "", chunk);
                int lineCount = countLeafPathsInChunk(chunk);
                String guide = OpeningPgnParser.extractBracketTagValue(chunk, "Guide");
                studyChaptersAll.add(new StudyChapter("Line 1", "", chunk, extractStartFenFromChunk(chunk), id, lineCount,
                        guide != null ? guide : ""));
            }
        } else {
            studyChaptersAll.addAll(parsed);
        }
        for (StudyChapter c : studyChaptersAll) {
            totalLinesInStudy += c.lineCount;
        }

        currentStudyKey = Integer.toHexString((fullPgn != null ? fullPgn : "").hashCode());
        loadChapterSelectionMaskFromPrefs();
        applyChapterSelectionToOrdered();
        loadCompletedLineKeysFromPrefs();
        completedLines = completedLineKeys.size();
        currentStudyLineIndex = 0;

        updateSelectChaptersButtonVisibility();
    }

    private void applyChapterSelectionToOrdered() {
        studyChaptersOrdered.clear();
        totalLinesInStudy = 0;
        if (chapterSelectionMask == null || chapterSelectionMask.length != studyChaptersAll.size()) {
            studyChaptersOrdered.addAll(studyChaptersAll);
        } else {
            for (int i = 0; i < studyChaptersAll.size(); i++) {
                if (chapterSelectionMask[i]) {
                    studyChaptersOrdered.add(studyChaptersAll.get(i));
                }
            }
            if (studyChaptersOrdered.isEmpty()) {
                studyChaptersOrdered.addAll(studyChaptersAll);
            }
        }
        for (StudyChapter c : studyChaptersOrdered) {
            totalLinesInStudy += c.lineCount;
        }
        boolean doShuffle = switchShuffleLines != null && switchShuffleLines.isChecked();
        if (doShuffle) {
            Collections.shuffle(studyChaptersOrdered, rng);
        }
    }

    private void updateSelectChaptersButtonVisibility() {
        if (buttonSelectChapters == null) return;
        buttonSelectChapters.setVisibility(studyChaptersAll.size() >= 2 ? View.VISIBLE : View.GONE);
        updateSelectChaptersButtonLabel();
    }

    private void updateSelectChaptersButtonLabel() {
        if (buttonSelectChapters == null) return;
        buttonSelectChapters.setText(R.string.opening_trainer_select_chapters);
        int total = studyChaptersAll.size();
        int selected = studyChaptersOrdered.size();
        String detail;
        if (selected >= total || chapterSelectionMask == null) {
            detail = getString(R.string.opening_trainer_select_chapters_all, total);
        } else {
            detail = getString(R.string.opening_trainer_select_chapters_count, selected, total);
        }
        CharSequence cd = TextUtils.concat(
                buttonSelectChapters.getText(),
                ". ",
                detail
        );
        buttonSelectChapters.setContentDescription(cd);
    }

    /** True when inner status ScrollView content is scrolled to (near) bottom. */
    private boolean isStatusInnerScrollNearBottom(int scrollY) {
        if (statusScrollView == null) return true;
        int childTotal = statusScrollView.getChildCount() <= 0 ? 0 : statusScrollView.getChildAt(0).getHeight();
        int visibleBottom = scrollY + statusScrollView.getHeight();
        return childTotal <= visibleBottom + 12;
    }

    private void maybeScrollStatusToBottom() {
        if (statusScrollView == null || !statusScrollFollowBottom) {
            return;
        }
        statusScrollView.post(() -> {
            if (statusScrollView == null) return;
            statusScrollFollowBottom = true;
            statusScrollView.fullScroll(View.FOCUS_DOWN);
        });
    }

    private void showChapterSelectionDialog() {
        int total = studyChaptersAll.size();
        if (total < 2) return;

        String[] names = new String[total];
        boolean[] checked = new boolean[total];
        for (int i = 0; i < total; i++) {
            StudyChapter ch = studyChaptersAll.get(i);
            String safeName = sanitizeChapterNameForUi(ch.chapterName, i);
            names[i] = (i + 1) + ". " + safeName + " (" + ch.lineCount + " lines)";
            checked[i] = (chapterSelectionMask == null) || chapterSelectionMask[i];
        }

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.opening_trainer_select_chapters_title)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.opening_trainer_select_all, null)
                .setNegativeButton(R.string.opening_trainer_clear_all, null)
                .create();

        dlg.setOnShowListener(dialog -> {
            final ListView listView = dlg.getListView();

            Button buttonOk = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            buttonOk.setOnClickListener(v -> {
                applyChapterSelectionFromDialog(checked);
                dlg.dismiss();
            });

            Button buttonSelectAll = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
            buttonSelectAll.setOnClickListener(v -> {
                for (int i = 0; i < checked.length; i++) {
                    checked[i] = true;
                    listView.setItemChecked(i, true);
                }
            });

            Button buttonClearAll = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
            buttonClearAll.setOnClickListener(v -> {
                for (int i = 0; i < checked.length; i++) {
                    checked[i] = false;
                    listView.setItemChecked(i, false);
                }
            });
        });
        dlg.show();
    }

    private void applyChapterSelectionFromDialog(boolean[] checked) {
        boolean allSelected = true;
        boolean noneSelected = true;
        for (boolean b : checked) {
            if (!b) allSelected = false;
            if (b) noneSelected = false;
        }
        if (allSelected || noneSelected) {
            chapterSelectionMask = null;
        } else {
            chapterSelectionMask = checked.clone();
        }
        saveChapterSelectionMaskToPrefs();
        applyChapterSelectionToOrdered();
        updateSelectChaptersButtonLabel();
        completedLineKeys.clear();
        completedLines = 0;
        saveCompletedLineKeysToPrefs();
        startStudyLine(0, true);
    }

    private void updateStudyLineUi() {
        if (textViewStudyLine == null) return;
        int chapterTotal = studyChaptersOrdered.size();
        int chapterCur = chapterTotal > 0 ? (currentStudyLineIndex + 1) : 0;
        String chapterName = currentChapterName != null && !currentChapterName.isEmpty() ? currentChapterName : "Line";

        int lineTotal = Math.max(totalLinesInChapter, 1);
        int lineCur = Math.min(currentChapterLineIndex + 1, lineTotal);

        textViewStudyLine.setText(getString(
                R.string.opening_trainer_study_line_label,
                chapterName,
                chapterCur,
                chapterTotal,
                lineCur,
                lineTotal
        ));
    }

    /**
     * Expected move for the user: the first child at each node (i.e. the "main line" as parsed from the PGN chunk).
     */
    private OpeningMoveTree.Edge getExpectedEdgeForCurrentNode() {
        if (engineEnabled) return null;
        if (currentNode == null) return null;
        List<OpeningMoveTree.Edge> edges = currentNode.getEdges();
        if (edges == null || edges.isEmpty()) return null;
        return edges.get(0);
    }

    /**
     * Expected edge (move) for the current exact training line.
     * `linePly` is the index of the next half-move to play from the root.
     */
    private OpeningMoveTree.Edge getExpectedEdgeFromCurrentLine() {
        if (engineEnabled) return null;
        if (currentLineEdges == null || currentLineEdges.isEmpty()) return null;
        if (linePly < 0 || linePly >= currentLineEdges.size()) return null;
        return currentLineEdges.get(linePly);
    }

    private void startStudyLine(int lineIndex, boolean resetCoverage) {
        if (studyChaptersOrdered.isEmpty()) return;

        // Cancel any pending eval.
        cancelEngineEval();

        // Reset visible eval UI.
        lastEvalLine = "Engine eval: --";
        lastEvalCp = 0f;
        evalBaselineCpWhite = 0f;
        evalBaselineInitialized = false;
        if (evalBarView != null) evalBarView.setEvalCp(0f);
        if (openingLineProgressView != null) openingLineProgressView.setProgress(0f);

        // Study line state.
        int total = studyChaptersOrdered.size();
        currentStudyLineIndex = ((lineIndex % total) + total) % total; // wrap safely

        // Build move tree for the currently selected chapter.
        StudyChapter chapter = studyChaptersOrdered.get(currentStudyLineIndex);
        currentChapterGuide = chapter.guideText != null ? chapter.guideText : "";
        activeChapterStartFen = chapter.startFen;
        syncPlayAsFromChapterFen();
        refreshGuideSwitchVisibility();
        currentChapterPersistentId = chapter.persistentId;
        moveTree = new OpeningMoveTree();
        OpeningPgnParser.parseIntoTree(chapter.pgnChunk, moveTree);
        currentNode = moveTree.getRoot();
        computeTotals();

        currentChapterName = sanitizeChapterNameForUi(chapter.chapterName, currentStudyLineIndex);
        enumerateChapterLinesAndSelectFirst();

        // Reset trainer stats/coverage (optional).
        if (resetCoverage) {
            visitedEdgesWhite.clear();
            visitedEdgesBlack.clear();
            pendingEdgesWhite.clear();
            pendingEdgesBlack.clear();
            attemptsWhite = 0;
            correctWhite = 0;
            attemptsBlack = 0;
            correctBlack = 0;
        } else {
            pendingEdgesWhite.clear();
            pendingEdgesBlack.clear();
        }

        hintUsedThisLine = false;
        wrongMoveThisLine = false;
        linePly = 0;
        resetHintHighlight();

        wrongMovePendingUndo = false;
        wrongMoveBoardNum = -1;
        wrongPositions.clear();

        // Restart always returns to Opening mode (engine takeover off).
        engineEnabled = false;
        engineMoveScheduled = false;
        if (progressBarBot != null) progressBarBot.setVisibility(View.GONE);
        updateBotButtonUi();
        chessBoardView.setRotated("black".equals(playAs));
        activeBookComment = "";
        statusExtraFirstLine = null;

        // Reset board + move history (FEN chapter start when present).
        applyChapterBoardStart();
        currentNode = moveTree.getRoot();

        updateStudyLineUi();
        updateStatus();
        rebuildBoard();

        playAutoOpeningMovesUntilUsersTurn();
        maybePlayBotIfNeeded(/*forced=*/false);
        flashFenTurnOnBoardIfNeeded();
    }

    private void startNextStudyLine(boolean resetCoverage) {
        if (studyChaptersOrdered.isEmpty()) return;
        int total = studyChaptersOrdered.size();
        int next = currentStudyLineIndex + 1;
        if (next >= total) next = 0;
        startStudyLine(next, resetCoverage);
    }

    private void advanceToNextLineManual() {
        boolean shuffleOn = switchShuffleLines != null && switchShuffleLines.isChecked();
        ArrayList<LinePick> candidates = getUncompletedLinePicks(/*excludeCurrentLine=*/true);
        if (candidates.isEmpty()) {
            setStatusDebug("Đã đi hết các line, hãy bấm Reset để trở lại từ đầu");
            updateStatus();
            return;
        }
        LinePick pick = shuffleOn
                ? candidates.get(rng.nextInt(candidates.size()))
                : candidates.get(0);
        startLinePick(pick, /*clearPending=*/true);
    }

    private void startNextChapterLine(int lineIndex, boolean clearPending) {
        if (moveTree == null) return;
        if (chapterLinesOrdered == null || chapterLinesOrdered.isEmpty()) return;
        int total = chapterLinesOrdered.size();
        if (lineIndex < 0 || lineIndex >= total) return;

        cancelEngineEval();

        // Reset visible eval UI.
        lastEvalLine = "Engine eval: --";
        lastEvalCp = 0f;
        evalBaselineCpWhite = 0f;
        evalBaselineInitialized = false;
        if (evalBarView != null) evalBarView.setEvalCp(0f);
        if (openingLineProgressView != null) openingLineProgressView.setProgress(0f);

        currentChapterLineIndex = lineIndex;
        currentLineEdges = new ArrayList<>(chapterLinesOrdered.get(lineIndex));

        if (clearPending) {
            pendingEdgesWhite.clear();
            pendingEdgesBlack.clear();
        }

        hintUsedThisLine = false;
        linePly = 0;
        resetHintHighlight();

        wrongMovePendingUndo = false;
        wrongMoveBoardNum = -1;
        wrongPositions.clear();

        engineEnabled = false;
        engineMoveScheduled = false;
        if (progressBarBot != null) progressBarBot.setVisibility(View.GONE);
        updateBotButtonUi();
        chessBoardView.setRotated("black".equals(playAs));

        // Reset board + move history for a new training line (same chapter FEN).
        applyChapterBoardStart();
        currentNode = moveTree.getRoot();

        updateStudyLineUi();
        updateStatus();
        rebuildBoard();

        playAutoOpeningMovesUntilUsersTurn();
        maybePlayBotIfNeeded(/*forced=*/false);
        flashFenTurnOnBoardIfNeeded();
    }

    private void maybeAdvanceStudyLineAfterLineComplete() {
        if (engineEnabled) return;
        if (switchAutoNext == null || !switchAutoNext.isChecked()) return;

        boolean shuffleOn = switchShuffleLines != null && switchShuffleLines.isChecked();
        ArrayList<LinePick> candidates = getUncompletedLinePicks(/*excludeCurrentLine=*/false);
        if (candidates.isEmpty()) {
            setStatusDebug("Đã đi hết các line, hãy bấm Reset để trở lại từ đầu");
            updateStatus();
            return;
        }
        LinePick pick = shuffleOn
                ? candidates.get(rng.nextInt(candidates.size()))
                : candidates.get(0);
        startLinePick(pick, /*clearPending=*/false);
    }

    private ArrayList<StudyChapter> parseStudyChapters(String fullPgn) {
        ArrayList<StudyChapter> out = new ArrayList<>();
        if (fullPgn == null || fullPgn.trim().isEmpty()) {
            return out;
        }

        Pattern chapterNamePattern = Pattern.compile("\\[ChapterName\\s+\"([^\"]*)\"\\]");
        Pattern chapterUrlPattern = Pattern.compile("\\[ChapterURL\\s+\"([^\"]*)\"\\]");

        Matcher m = chapterNamePattern.matcher(fullPgn);
        ArrayList<Integer> starts = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            names.add(m.group(1));
        }
        if (!starts.isEmpty()) {
            for (int i = 0; i < starts.size(); i++) {
                int start = starts.get(i);
                int end = (i + 1 < starts.size()) ? starts.get(i + 1) : fullPgn.length();
                String chunk = fullPgn.substring(start, end);
                if (!OpeningPgnParser.chunkHasMoves(chunk)) {
                    continue;
                }

                Matcher urlMatcher = chapterUrlPattern.matcher(chunk);
                String url = "";
                if (urlMatcher.find()) {
                    url = urlMatcher.group(1);
                }
                String chapterName = names.get(i);
                if (chapterName == null || chapterName.trim().isEmpty()) {
                    String wbName = buildWhiteBlackChapterName(chunk);
                    if (wbName != null) {
                        chapterName = wbName;
                    } else {
                        chapterName = "Chapter " + (i + 1);
                    }
                }
                String id = buildChapterPersistentId(chapterName, url, chunk);
                int lineCount = countLeafPathsInChunk(chunk);
                String guide = OpeningPgnParser.extractBracketTagValue(chunk, "Guide");
                out.add(new StudyChapter(chapterName, url, chunk, extractStartFenFromChunk(chunk), id, lineCount,
                        guide != null ? guide : ""));
            }
            return out;
        }

        ArrayList<String> games = splitPgnIntoGames(fullPgn);
        if (games.size() > 1) {
            for (int i = 0; i < games.size(); i++) {
                String chunk = games.get(i);
                if (!OpeningPgnParser.chunkHasMoves(chunk)) {
                    continue;
                }
                String chapterNameTag = OpeningPgnParser.extractBracketTagValue(chunk, "ChapterName");
                String ev = OpeningPgnParser.extractBracketTagValue(chunk, "Event");
                String ch = OpeningPgnParser.extractBracketTagValue(chunk, "Chapter");
                String name;
                if (chapterNameTag != null && !chapterNameTag.trim().isEmpty()) {
                    name = chapterNameTag.trim();
                } else {
                    String wbName = buildWhiteBlackChapterName(chunk);
                    if (wbName != null) {
                        name = wbName;
                    } else if (ev != null && !ev.trim().isEmpty()) {
                        name = ev.trim();
                    } else if (ch != null && !ch.trim().isEmpty()) {
                        name = ch.trim();
                    } else {
                        name = "Game " + (i + 1);
                    }
                }
                String guide = OpeningPgnParser.extractBracketTagValue(chunk, "Guide");
                String id = buildChapterPersistentId(name, "", chunk);
                int lineCount = countLeafPathsInChunk(chunk);
                out.add(new StudyChapter(name, "", chunk, extractStartFenFromChunk(chunk), id, lineCount,
                        guide != null ? guide : ""));
            }
            return out;
        }

        String single = games.isEmpty() ? fullPgn.trim() : games.get(0);
        if (single.isEmpty() || !OpeningPgnParser.chunkHasMoves(single)) {
            return out;
        }
        String oneName = OpeningPgnParser.extractBracketTagValue(single, "ChapterName");
        if (oneName == null || oneName.trim().isEmpty()) {
            String wbName = buildWhiteBlackChapterName(single);
            oneName = wbName != null ? wbName : "Line 1";
        }
        String id = buildChapterPersistentId(oneName, "", single);
        int lineCount = countLeafPathsInChunk(single);
        String guide = OpeningPgnParser.extractBracketTagValue(single, "Guide");
        out.add(new StudyChapter(oneName, "", single, extractStartFenFromChunk(single), id, lineCount,
                guide != null ? guide : ""));
        return out;
    }

    private static final int MAX_CHAPTER_LINES = 20000;

    private void enumerateChapterLinesAndSelectFirst() {
        chapterLinesAll.clear();
        chapterLinesOrdered.clear();
        currentChapterLineIndex = 0;
        totalLinesInChapter = 0;
        currentLineEdges = new ArrayList<>();

        if (moveTree == null || moveTree.getRoot() == null) {
            totalLinesInChapter = 0;
            updateStudyLineUi();
            return;
        }

        enumerateRootToLeafPaths(moveTree.getRoot(), new ArrayList<>());

        totalLinesInChapter = chapterLinesAll.size();
        chapterLinesOrdered.addAll(chapterLinesAll);

        if (!chapterLinesOrdered.isEmpty()) {
            boolean shuffleOn = switchShuffleLines != null && switchShuffleLines.isChecked();
            if (requestedInitialChapterLineIndex >= 0 && requestedInitialChapterLineIndex < chapterLinesOrdered.size()) {
                currentChapterLineIndex = requestedInitialChapterLineIndex;
            } else {
                currentChapterLineIndex = shuffleOn
                        ? rng.nextInt(chapterLinesOrdered.size())
                        : 0;
            }
            currentLineEdges = new ArrayList<>(chapterLinesOrdered.get(currentChapterLineIndex));
        }
        requestedInitialChapterLineIndex = -1;

        updateStudyLineUi();
    }

    private void enumerateRootToLeafPaths(OpeningMoveTree.Node node, ArrayList<OpeningMoveTree.Edge> prefix) {
        if (node == null) return;
        if (chapterLinesAll.size() >= MAX_CHAPTER_LINES) return;

        if (!node.hasChildren()) {
            chapterLinesAll.add(new ArrayList<>(prefix));
            return;
        }

        List<OpeningMoveTree.Edge> edges = node.getEdges();
        for (OpeningMoveTree.Edge e : edges) {
            prefix.add(e);
            enumerateRootToLeafPaths(e.child, prefix);
            prefix.remove(prefix.size() - 1);
            if (chapterLinesAll.size() >= MAX_CHAPTER_LINES) {
                break;
            }
        }
    }

    @Override
    public boolean requestMove(int from, int to) {
        JNI jni = JNI.getInstance();

        if (jni.isEnded() != 0) {
            updateStatus();
            rebuildBoard();
            return false;
        }

        int sideToMove = jni.getTurn();
        boolean isUserTurn = ("white".equals(playAs) && sideToMove == BoardConstants.WHITE)
                || ("black".equals(playAs) && sideToMove == BoardConstants.BLACK);
        if (!isUserTurn) {
            rebuildBoard();
            return false;
        }

        // Engine takeover mode: allow any legal move (no study-tree enforcement).
        if (engineEnabled) {
            boolean ok = super.requestMove(from, to);
            if (!ok) {
                setStatusDebug("Engine mode: illegal move");
                rebuildBoard();
                return false;
            }
            resetHintHighlight();
            wrongMovePendingUndo = false;
            wrongMoveBoardNum = -1;
            wrongPositions.clear();
            activeBookComment = "";
            statusExtraFirstLine = null;
            rebuildBoard();
            updateStatus();
            maybePlayBotIfNeeded(/*forced=*/false);
            return true;
        }

        if (sideToMove == BoardConstants.WHITE) {
            attemptsWhite++;
        } else {
            attemptsBlack++;
        }

        int move = findMatchingMoveByFromTo(jni, from, to);
        if (move == 0) {
            showWrongMoveMessage("no_legal_move_from_to");
            rebuildBoard();
            return false;
        }

        // get SAN for this move by temporarily applying it
        String san;
        int tmp = jni.move(move);
        if (tmp != 0) {
            san = jni.getMyMoveToString();
            jni.undo();
        } else {
            showWrongMoveMessage("jni_move_failed");
            rebuildBoard();
            return false;
        }
        String sanNorm = OpeningPgnParser.normalizeSan(san);

        boolean inTree = currentNode != null && currentNode.getEdge(sanNorm) != null;
        OpeningMoveTree.Edge expectedEdge = getExpectedEdgeFromCurrentLine();

        boolean ok = gameApi.requestMove(from, to);
        if (!ok) {
            setStatusDebug("requestMove rejected by engine. san=" + san);
            rebuildBoard();
            return false;
        }

        // Allow deviation: mark wrong move and require undo on next board tap.
        if (!inTree || expectedEdge == null || !expectedEdge.sanNorm.equals(sanNorm)) {
            wrongMoveThisLine = true;
            wrongMovePendingUndo = true;
            wrongMoveBoardNum = jni.getNumBoard();
            wrongPositions.clear();
            wrongPositions.add(to);
            String expected = expectedEdge != null ? expectedEdge.san : describeExpectedMoves();
            String prefix = !inTree ? "not_in_tree " : "not_expected_in_line ";
            showWrongMoveMessage(prefix + "san=" + san + " expected=" + expected);
            rebuildBoard();
            updateSelectedSquares();
            updateStatus();
            return true;
        }

        if (sideToMove == BoardConstants.WHITE) {
            correctWhite++;
        } else {
            correctBlack++;
        }
        statusExtraFirstLine = null;
        OpeningMoveTree.Edge playedEdge = currentNode.getEdge(sanNorm);
        recordEdge(sideToMove, currentNode, playedEdge);
        currentNode = playedEdge.child;
        applyBookCommentFromEdge(playedEdge);
        resetHintHighlight();
        linePly++;
        rebuildBoard();
        maybePlayBotIfNeeded(/*forced=*/false);
        if (!engineEnabled) {
            // In opening training mode: evaluate right after the user move.
            // (Bot replies very soon, so we also cancel any pending eval before bot moves.)
            scheduleEngineEval();
            playReplyIfAny();
        }
        maybeCommitCoverageOnLineEnd();
        updateStatus();
        return true;
    }

    /**
     * Revert the illegal half-move the user played. {@code linePly} was not advanced for that move;
     * after {@link GameApi#undoMove()} fixes the PGN stack, we resync the tree node from the board.
     */
    private void undoWrongMoveIfPending() {
        if (!wrongMovePendingUndo) {
            return;
        }
        try {
            gameApi.undoMove();
        } catch (Exception ignored) {}
        wrongMovePendingUndo = false;
        wrongMoveBoardNum = -1;
        wrongPositions.clear();
        statusExtraFirstLine = null;
        resetHintHighlight();
        syncTrainerStateFromBoard();
        rebuildBoard();
        updateSelectedSquares();
        updateStatus();
        maybePlayBotIfNeeded(/*forced=*/false);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (wrongMovePendingUndo && ev.getActionMasked() == MotionEvent.ACTION_DOWN && chessBoardView != null) {
            float localX = ev.getX() - chessBoardView.getX();
            float localY = ev.getY() - chessBoardView.getY();
            if (localX >= 0 && localX <= chessBoardView.getWidth() && localY >= 0 && localY <= chessBoardView.getHeight()) {
                undoWrongMoveIfPending();
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private int findMatchingMoveByFromTo(JNI jni, int from, int to) {
        int size = jni.getMoveArraySize();
        for (int i = 0; i < size; i++) {
            int move = jni.getMoveArrayAt(i);
            if (Move.getFrom(move) == from && Move.getTo(move) == to) {
                return move;
            }
        }
        return 0;
    }

    private void playReplyIfAny() {
        if (engineEnabled) {
            return;
        }
        JNI jni = JNI.getInstance();
        if (jni.isEnded() != 0 || currentNode == null || !currentNode.hasChildren()) {
            return;
        }

        List<OpeningMoveTree.Edge> edges = currentNode.getEdges();
        if (edges == null || edges.isEmpty()) {
            return;
        }

        // Follow the currently selected exact "chapter line" instead of always picking the first child.
        OpeningMoveTree.Edge edge = getExpectedEdgeFromCurrentLine();
        if (edge == null || currentNode.getEdge(edge.sanNorm) == null) {
            // Fallback (shouldn't happen if the line is consistent).
            edge = edges.get(0);
        }
        String sanReply = edge.san;
        String sanNormReply = OpeningPgnParser.normalizeSan(sanReply);
        setStatusDebug("Reply: target=" + sanReply + " expectedNow=" + describeExpectedMoves());

        int size = jni.getMoveArraySize();
        for (int i = 0; i < size; i++) {
            int move = jni.getMoveArrayAt(i);
            int tmp = jni.move(move);
            if (tmp != 0) {
                String san = jni.getMyMoveToString();
                String norm = OpeningPgnParser.normalizeSan(san);
                jni.undo();
                if (sanNormReply.equals(norm)) {
                    final int moveFinal = move;
                    final int sideFinal = jni.getTurn();
                    final OpeningMoveTree.Edge edgeFinal = edge;
                    final OpeningMoveTree.Node nodeFinal = currentNode;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        // Opening reply will change the board. Cancel eval started after the user move
                        // to avoid updating UI with a value from an old position.
                        cancelEngineEval();
                        gameApi.move(moveFinal, -1);
                        recordEdge(sideFinal, nodeFinal, edgeFinal);
                        currentNode = edgeFinal.child;
                        linePly++;
                        applyBookCommentFromEdge(edgeFinal);
                        resetHintHighlight();
                        rebuildBoard();
                        maybeCommitCoverageOnLineEnd();
                        updateStatus();
                        // Re-run eval for the position after the opening reply (user-move eval was cancelled above).
                        if (!engineEnabled) {
                            scheduleEngineEval();
                        }
                    }, 500);
                    break;
                }
            }
        }
    }

    private void cancelEngineEval() {
        if (evalScheduledRunnable != null) {
            uiHandler.removeCallbacks(evalScheduledRunnable);
            evalScheduledRunnable = null;
        }
        evalGeneration++;
        try {
            JNI.getInstance().interrupt();
        } catch (Exception ignored) {}
    }

    private void showHint() {
        if (engineEnabled) {
            setStatusDebug("Hint disabled in engine mode");
            updateStatus();
            return;
        }
        JNI jni = JNI.getInstance();
        if (currentNode == null || !currentNode.hasChildren()) {
            textStatus.setText(R.string.opening_trainer_no_hint);
            return;
        }

        // Hint affects stats: counts as a wrong attempt, and disables coverage credit for this line.
        int sideToMove = jni.getTurn();
        if (sideToMove == BoardConstants.WHITE) {
            attemptsWhite++;
        } else {
            attemptsBlack++;
        }
        if (!hintUsedThisLine) {
            hintUsedThisLine = true;
            pendingEdgesWhite.clear();
            pendingEdgesBlack.clear();
        }

        OpeningMoveTree.Edge edge = getExpectedEdgeFromCurrentLine();
        if (edge == null) {
            textStatus.setText(R.string.opening_trainer_no_hint);
            return;
        }
        String san = edge.san;
        String sanNorm = OpeningPgnParser.normalizeSan(san);

        // If the candidate move changed since last hint, restart the hint stages.
        if (hintedSan == null || !hintedSan.equals(sanNorm)) {
            hintStage = 0;
        }

        int move = findMoveForSanNorm(jni, sanNorm);
        if (move == 0) {
            setStatusDebug("Hint: could not map SAN to a legal move. san=" + san);
            return;
        }

        hintedSan = sanNorm;
        hintedFrom = Move.getFrom(move);
        hintedTo = Move.getTo(move);

        if (hintStage == 0) {
            // Step 1: highlight the piece to move.
            hintPositions.clear();
            hintPositions.add(hintedFrom);
            hintStage = 1;
            setStatusDebug("Hint 1/2: piece at " + hintedFrom + " (" + san + ")");
        } else if (hintStage == 1) {
            // Step 2: highlight from and destination squares.
            hintPositions.clear();
            hintPositions.add(hintedFrom);
            hintPositions.add(hintedTo);
            hintStage = 2;
            setStatusDebug("Hint 2/2: " + san);
        } else {
            // Next press clears.
            resetHintHighlight();
            setStatusDebug("Hint cleared");
        }

        rebuildBoard();
        updateSelectedSquares();
        updateStatus();
    }

    private void undoLastMove() {
        JNI jni = JNI.getInstance();
        if (wrongMovePendingUndo) {
            undoWrongMoveIfPending();
            return;
        }

        if (jni.getNumBoard() <= 1) {
            return;
        }

        // Normal undo: revert a full turn (user + bot), i.e. 2 half-moves.
        gameApi.undoMove();
        if (jni.getNumBoard() > 1) {
            gameApi.undoMove();
        }
        statusExtraFirstLine = null;
        resetHintHighlight();
        syncTrainerStateFromBoard();
        rebuildBoard();
        updateSelectedSquares();
        updateStatus();
        maybePlayBotIfNeeded(/*forced=*/false);
    }

    /** Back button (<): step back exactly one half-move. */
    private void stepBackOneMove() {
        JNI jni = JNI.getInstance();
        if (wrongMovePendingUndo) {
            undoWrongMoveIfPending();
            return;
        }
        if (jni.getNumBoard() <= 1) {
            return;
        }
        gameApi.undoMove();
        statusExtraFirstLine = null;
        resetHintHighlight();
        syncTrainerStateFromBoard();
        rebuildBoard();
        updateSelectedSquares();
        updateStatus();
        maybePlayBotIfNeeded(/*forced=*/false);
    }

    private void syncTrainerStateFromBoard() {
        if (moveTree == null || moveTree.getRoot() == null) return;

        // Keep linePly consistent with the native board: m_numBoard is 0 at chapter start and
        // increments by 1 per half-move. linePly is the index of the *next* edge in currentLineEdges
        // (same convention as after playAutoOpeningMovesUntilUsersTurn / correct user moves).
        JNI jni = JNI.getInstance();
        linePly = Math.max(0, jni.getNumBoard());

        // Rebuild currentNode by walking the first linePly edges already on the board.
        currentNode = moveTree.getRoot();
        if (currentLineEdges == null || currentLineEdges.isEmpty()) {
            activeBookComment = "";
            return;
        }

        int max = Math.min(linePly, currentLineEdges.size());
        for (int i = 0; i < max; i++) {
            OpeningMoveTree.Edge e = currentNode.getEdge(currentLineEdges.get(i).sanNorm);
            if (e == null) {
                break;
            }
            currentNode = e.child;
        }
        applyBookCommentAfterLineStateSync();
    }

    /**
     * Advance one half-move along the current line for review/learning.
     * Plays the next expected move (user's or opponent's) without requiring board interaction.
     */
    private void advanceOneMoveForReview() {
        if (currentLineEdges == null || currentLineEdges.isEmpty()) return;
        if (linePly >= currentLineEdges.size()) return;

        JNI jni = JNI.getInstance();
        if (jni.isEnded() != 0) return;

        OpeningMoveTree.Edge edge = currentLineEdges.get(linePly);
        int move = findMoveForSanNorm(jni, edge.sanNorm);
        if (move == 0) {
            syncTrainerStateFromBoard();
            move = findMoveForSanNorm(jni, edge.sanNorm);
        }
        if (move == 0) return;

        int sideBefore = jni.getTurn();
        gameApi.move(move, -1);
        recordEdge(sideBefore, currentNode, edge);
        currentNode = edge.child;
        applyBookCommentFromEdge(edge);
        linePly++;
        resetHintHighlight();
        rebuildBoard();
        updateOpeningLineProgress();
        updateStatus();
        maybeCommitCoverageOnLineEnd();
    }

    /**
     * Advance to the end of the current line for review (long-press Next).
     */
    private void advanceToEndOfLineForReview() {
        if (currentLineEdges == null || currentLineEdges.isEmpty()) return;
        JNI jni = JNI.getInstance();
        int guard = 0;
        while (linePly < currentLineEdges.size() && jni.isEnded() == 0 && guard < 500) {
            OpeningMoveTree.Edge edge = currentLineEdges.get(linePly);
            int move = findMoveForSanNorm(jni, edge.sanNorm);
            if (move == 0) break;
            int sideBefore = jni.getTurn();
            gameApi.move(move, -1);
            recordEdge(sideBefore, currentNode, edge);
            currentNode = edge.child;
            linePly++;
            guard++;
        }
        applyBookCommentAfterLineStateSync();
        resetHintHighlight();
        rebuildBoard();
        updateOpeningLineProgress();
        updateStatus();
        maybeCommitCoverageOnLineEnd();
    }

    private void updateStatus() {
        JNI jni = JNI.getInstance();
        int turn = jni.getTurn();
        String side = (turn == BoardConstants.WHITE) ? getString(R.string.piece_white) : getString(R.string.piece_black);
        int done = completedLineKeys.size();
        int total = Math.max(totalLinesInStudy, 1);
        int pct = (int) Math.floor((done * 100.0) / total);
        String coverageLine = "Coverage: " + done + "/" + total + " (" + pct + "%)";

        StringBuilder sb = new StringBuilder();
        if (statusExtraFirstLine != null && !statusExtraFirstLine.isEmpty()) {
            sb.append(statusExtraFirstLine).append("\n");
        }
        sb.append(getString(R.string.opening_trainer_status_turn, side))
                .append("\n").append(coverageLine)
                .append("\n").append(lastEvalLine);
        sb.append("\n").append(lastDebugLine);
        textStatus.setText(sb.toString());
        maybeScrollStatusToBottom();
        updateStudyLineUi();
        updateOpeningLineProgress();
        updateBookCommentUi();
    }

    private void refreshGuideSwitchVisibility() {
        if (switchShowGuide == null) {
            return;
        }
        boolean hasGuide = currentChapterGuide != null && !currentChapterGuide.trim().isEmpty();
        switchShowGuide.setVisibility(hasGuide ? View.VISIBLE : View.GONE);
    }

    private void updateBookCommentUi() {
        if (textViewBookComment == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        boolean showGuide = switchShowGuide != null && switchShowGuide.isChecked();
        if (showGuide && currentChapterGuide != null && !currentChapterGuide.trim().isEmpty()) {
            sb.append(getString(R.string.opening_trainer_guide_label))
                    .append(" ")
                    .append(currentChapterGuide.trim());
        }
        if (activeBookComment != null && !activeBookComment.trim().isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(getString(R.string.opening_trainer_book_comment_label))
                    .append(" ")
                    .append(activeBookComment.trim());
        }
        if (sb.length() == 0) {
            textViewBookComment.setVisibility(View.GONE);
            return;
        }
        textViewBookComment.setVisibility(View.VISIBLE);
        textViewBookComment.setText(sb.toString());
    }

    /**
     * Length from {@code n} to a leaf following the first child at each step (same convention as auto-reply).
     */
    private static int firstChainLengthToLeaf(OpeningMoveTree.Node n) {
        int len = 0;
        OpeningMoveTree.Node cur = n;
        while (cur != null && cur.hasChildren()) {
            len++;
            cur = cur.getEdges().get(0).child;
        }
        return len;
    }

    /**
     * Progress along the current opening branch: plies matched on the board vs total plies in this branch
     * (prefix from PGN synced to the tree, then first-child continuation to leaf).
     */
    private void updateOpeningLineProgress() {
        if (openingLineProgressView == null || moveTree == null) {
            return;
        }
        if (engineEnabled) {
            openingLineProgressView.setProgress(0f);
            return;
        }
        int total = currentLineEdges != null ? currentLineEdges.size() : 0;
        if (total <= 0) {
            // No moves in this chapter/line.
            openingLineProgressView.setProgress(1f);
            return;
        }
        int done = Math.max(0, Math.min(linePly, total));
        openingLineProgressView.setProgress((float) done / (float) total);
    }

    private String completedLineKey(String chapterPersistentId, int lineIdx) {
        return chapterPersistentId + ":" + lineIdx;
    }

    private boolean isCompletedLine(String chapterPersistentId, int lineIdx) {
        return completedLineKeys.contains(completedLineKey(chapterPersistentId, lineIdx));
    }

    private ArrayList<Integer> getUncompletedLineCandidatesInCurrentChapter(boolean excludeCurrent) {
        ArrayList<Integer> out = new ArrayList<>();
        if (chapterLinesOrdered == null || chapterLinesOrdered.isEmpty()) return out;
        for (int i = 0; i < chapterLinesOrdered.size(); i++) {
            if (excludeCurrent && i == currentChapterLineIndex) continue;
            if (!isCompletedLine(currentChapterPersistentId, i)) {
                out.add(i);
            }
        }
        return out;
    }

    private void loadCompletedLineKeysFromPrefs() {
        completedLineKeys.clear();
        String raw = getPrefs().getString(PREF_COMPLETED_LINES_PREFIX + currentStudyKey, "");
        if (raw == null || raw.isEmpty()) return;
        String[] arr = raw.split("\n");
        for (String s : arr) {
            String t = s != null ? s.trim() : "";
            if (!t.isEmpty()) completedLineKeys.add(t);
        }
    }

    private void saveCompletedLineKeysToPrefs() {
        StringBuilder sb = new StringBuilder();
        for (String k : completedLineKeys) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(k);
        }
        getPrefs().edit().putString(PREF_COMPLETED_LINES_PREFIX + currentStudyKey, sb.toString()).apply();
    }

    private static class LinePick {
        final int chapterIdx;
        final int lineIdx;

        LinePick(int chapterIdx, int lineIdx) {
            this.chapterIdx = chapterIdx;
            this.lineIdx = lineIdx;
        }
    }

    private ArrayList<LinePick> getUncompletedLinePicks(boolean excludeCurrentLine) {
        ArrayList<LinePick> out = new ArrayList<>();
        for (int c = 0; c < studyChaptersOrdered.size(); c++) {
            StudyChapter chapter = studyChaptersOrdered.get(c);
            for (int l = 0; l < chapter.lineCount; l++) {
                if (excludeCurrentLine && c == currentStudyLineIndex && l == currentChapterLineIndex) continue;
                if (!isCompletedLine(chapter.persistentId, l)) {
                    out.add(new LinePick(c, l));
                }
            }
        }
        return out;
    }

    private void startLinePick(LinePick pick, boolean clearPending) {
        if (pick == null) return;
        requestedInitialChapterLineIndex = pick.lineIdx;
        startStudyLine(pick.chapterIdx, false);
        if (clearPending) {
            pendingEdgesWhite.clear();
            pendingEdgesBlack.clear();
        }
    }

    private void showWrongMoveMessage(String debug) {
        statusExtraFirstLine = getString(R.string.opening_trainer_wrong_move);
        lastDebugLine = "WRONG: " + debug;
        updateStatus();
    }

    private void setStatusDebug(String msg) {
        Log.d(TAG, msg);
        lastDebugLine = msg;
        if (textStatus != null) {
            updateStatus();
        } else if (statusScrollView != null) {
            maybeScrollStatusToBottom();
        }
    }

    private String describeExpectedMoves() {
        if (currentNode == null) return "null_node";
        List<OpeningMoveTree.Edge> edges = currentNode.getEdges();
        if (edges.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(edges.size(), 12);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(",");
            sb.append(edges.get(i).san);
        }
        if (edges.size() > limit) sb.append(",...");
        return sb.toString();
    }

    private void computeTotals() {
        totalEdgesWhite = 0;
        totalEdgesBlack = 0;

        class Frame {
            OpeningMoveTree.Node node;
            int depth; // root depth=0; edge from depth 0 is white's first move
            Frame(OpeningMoveTree.Node n, int d) { node = n; depth = d; }
        }

        java.util.ArrayDeque<Frame> q = new java.util.ArrayDeque<>();
        q.add(new Frame(moveTree.getRoot(), 0));

        while (!q.isEmpty()) {
            Frame f = q.removeFirst();
            List<OpeningMoveTree.Edge> edges = f.node.getEdges();
            for (OpeningMoveTree.Edge e : edges) {
                int side = (f.depth % 2 == 0) ? BoardConstants.WHITE : BoardConstants.BLACK;
                if (side == BoardConstants.WHITE) totalEdgesWhite++;
                else totalEdgesBlack++;
                q.add(new Frame(e.child, f.depth + 1));
            }
        }
    }

    private void recordEdge(int side, OpeningMoveTree.Node fromNode, OpeningMoveTree.Edge edge) {
        if (edge == null || fromNode == null) return;
        String key = System.identityHashCode(fromNode) + ":" + edge.sanNorm;
        if (hintUsedThisLine) {
            return;
        }
        if (side == BoardConstants.WHITE) pendingEdgesWhite.add(key);
        else pendingEdgesBlack.add(key);
    }

    private String formatPercent(int num, int den) {
        if (den <= 0) return "0%";
        int pct = (int) Math.floor((num * 100.0) / den);
        return pct + "% (" + num + "/" + den + ")";
    }

    private void maybeCommitCoverageOnLineEnd() {
        if (currentNode != null && currentNode.hasChildren()) {
            return;
        }
        showLineDoneOverlay();
        // Line complete at this node.
        if (!hintUsedThisLine && !wrongMoveThisLine) {
            visitedEdgesWhite.addAll(pendingEdgesWhite);
            visitedEdgesBlack.addAll(pendingEdgesBlack);
            setStatusDebug("Line complete: coverage committed");

            // Mark this exact line as completed (if we have enumerated line indexes).
            completedLineKeys.add(completedLineKey(currentChapterPersistentId, currentChapterLineIndex));
            completedLines = completedLineKeys.size();
            saveCompletedLineKeysToPrefs();
        } else {
            setStatusDebug("Line complete: not counted (hint or wrong move used)");
        }
        pendingEdgesWhite.clear();
        pendingEdgesBlack.clear();
        hintUsedThisLine = false;
        resetHintHighlight();

        // Auto-advance to the next study line when the current line reaches a leaf.
        uiHandler.postDelayed(this::maybeAdvanceStudyLineAfterLineComplete, LINE_DONE_OVERLAY_MS);
    }

    private void showLineDoneOverlay() {
        if (lineDoneOverlay == null) return;
        lineDoneOverlay.animate().cancel();
        lineDoneOverlay.setAlpha(1f);
        lineDoneOverlay.setVisibility(View.VISIBLE);
        lineDoneOverlay.animate()
                .alpha(0f)
                .setDuration(LINE_DONE_OVERLAY_MS)
                .withEndAction(() -> {
                    lineDoneOverlay.setVisibility(View.GONE);
                    lineDoneOverlay.setAlpha(1f);
                })
                .start();
    }

    /** Brief “White to play” / “Black to play” on the board when the line uses a FEN. */
    private void flashFenTurnOnBoardIfNeeded() {
        if (fenTurnBoardFlash == null) {
            return;
        }
        if (fenTurnFlashHideRunnable != null) {
            uiHandler.removeCallbacks(fenTurnFlashHideRunnable);
            fenTurnFlashHideRunnable = null;
        }
        fenTurnBoardFlash.animate().cancel();
        if (fenTurnBanner == null || fenTurnBanner.isEmpty()) {
            fenTurnBoardFlash.setVisibility(View.GONE);
            return;
        }
        fenTurnBoardFlash.setText(fenTurnBanner);
        fenTurnBoardFlash.setAlpha(1f);
        fenTurnBoardFlash.setVisibility(View.VISIBLE);
        fenTurnFlashHideRunnable = () -> {
            fenTurnFlashHideRunnable = null;
            if (fenTurnBoardFlash == null) {
                return;
            }
            fenTurnBoardFlash.animate()
                    .alpha(0f)
                    .setDuration(FEN_TURN_FLASH_FADE_MS)
                    .withEndAction(() -> {
                        fenTurnBoardFlash.setVisibility(View.GONE);
                        fenTurnBoardFlash.setAlpha(1f);
                    })
                    .start();
        };
        uiHandler.postDelayed(fenTurnFlashHideRunnable, FEN_TURN_FLASH_VISIBLE_MS);
    }

    private void resetHintHighlight() {
        hintStage = 0;
        hintedFrom = -1;
        hintedTo = -1;
        hintedSan = null;
        hintPositions.clear();
    }

    private int findMoveForSanNorm(JNI jni, String sanNorm) {
        int size = jni.getMoveArraySize();
        for (int i = 0; i < size; i++) {
            int move = jni.getMoveArrayAt(i);
            int tmp = jni.move(move);
            if (tmp != 0) {
                String san = jni.getMyMoveToString();
                String norm = OpeningPgnParser.normalizeSan(san);
                jni.undo();
                if (sanNorm.equals(norm)) {
                    return move;
                }
            }
        }
        return 0;
    }

    private void scheduleEngineEval() {
        // Avoid fighting with the engine while it is about to move.
        if (engineEnabled && engineMoveScheduled) {
            return;
        }
        final JNI jni = JNI.getInstance();
        evalGeneration++;
        final int gen = evalGeneration;

        // Stop previous search ASAP.
        try {
            jni.interrupt();
        } catch (Exception ignored) {}

        if (evalScheduledRunnable != null) {
            uiHandler.removeCallbacks(evalScheduledRunnable);
        }

        // Single eval at fixed depth 7 only (skip d4); start ASAP on next looper tick.
        evalScheduledRunnable = () -> runEvalDepthAsync(gen, /*depth=*/7);
        uiHandler.post(evalScheduledRunnable);
    }

    private void runEvalDepthAsync(int gen, int depth) {
        new Thread(() -> {
            final JNI jni = JNI.getInstance();
            try {
                if (jni.isEnded() != 0) return;
                jni.searchDepth(depth, /*quiescentOn=*/0);
                final int bestValue = jni.peekSearchBestValue();
                final int bestDepth = jni.peekSearchDepth();
                final int turn = jni.getTurn();
                // Engine "bestValue" is from the side-to-move perspective.
                final float cpTurn = bestValue / 100.0f;
                final float cpWhite = (turn == BoardConstants.WHITE) ? cpTurn : -cpTurn;

                // Extra debug to understand unexpected spikes:
                // - peekSearchBestValue(): bestValue from search
                // - getBoardValue(): static evaluation of current position
                final int boardValue = jni.getBoardValue();
                final float cpBoardTurn = boardValue / 100.0f;
                final float cpBoardWhite = (turn == BoardConstants.WHITE) ? cpBoardTurn : -cpBoardTurn;

                // Use the static board value for user-facing eval to avoid tactical spikes from bestValue,
                // and display eval relative to the baseline captured at line start.
                final float cpDisplayWhite = cpBoardWhite - evalBaselineCpWhite;
                final float cpDisplayTurn = (turn == BoardConstants.WHITE) ? cpDisplayWhite : -cpDisplayWhite;

                final String signTurn = cpDisplayTurn > 0 ? "+" : "";
                final String line = "Engine eval d" + bestDepth + ": " + signTurn
                        + String.format("%.2f", cpDisplayTurn)
                        + " (toMove=" + (turn == BoardConstants.WHITE ? "W" : "B") + ")"
                        + " | boardRaw=" + boardValue + " (" + (cpBoardWhite > 0 ? "+" : "") + String.format("%.2f", cpBoardWhite) + "W)"
                        + " | searchRaw=" + bestValue + " (" + (cpWhite > 0 ? "+" : "") + String.format("%.2f", cpWhite) + "W)";

                uiHandler.post(() -> {
                    if (gen != evalGeneration) return;
                    lastEvalLine = line;
                    lastEvalCp = cpDisplayWhite;
                    if (evalBarView != null) {
                        evalBarView.setEvalCp(cpDisplayWhite);
                    }
                    updateStatus();
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    if (gen != evalGeneration) return;
                    lastEvalLine = "Engine eval: error";
                    updateStatus();
                });
            }
        }, "OpeningEvalDepth" + depth).start();
    }

    private void updateBotButtonUi() {
        if (buttonPlayBot == null) return;
        buttonPlayBot.setEnabled(true);
        buttonPlayBot.setAlpha(engineEnabled ? 1.0f : 0.35f);
    }

    private void appendEngineDebug(String msg) {
        setStatusDebug(msg);
        updateStatus();
    }

    private boolean isUsersTurn(int sideToMove) {
        return ("white".equals(playAs) && sideToMove == BoardConstants.WHITE)
                || ("black".equals(playAs) && sideToMove == BoardConstants.BLACK);
    }

    private void maybePlayBotIfNeeded(boolean forced) {
        if (!engineEnabled || engine == null || !engine.isReady()) {
            return;
        }
        JNI jni = JNI.getInstance();
        if (jni.isEnded() != 0) return;
        int sideToMove = jni.getTurn();
        if (isUsersTurn(sideToMove)) {
            if (forced) {
                appendEngineDebug("Engine enabled, but it's your turn.");
            }
            return;
        }
        if (engineMoveScheduled) {
            return;
        }
        engineMoveScheduled = true;
        if (progressBarBot != null) progressBarBot.setVisibility(View.VISIBLE);
        uiHandler.postDelayed(() -> {
            try {
                engine.play();
            } finally {
                // Keep scheduled flag until we actually receive a move / error, to prevent spamming.
            }
        }, 500);
    }

    @Override
    public void OnEngineMove(int move, int duckMove, int value) {
        if (progressBarBot != null) progressBarBot.setVisibility(View.GONE);
        engineMoveScheduled = false;
        gameApi.move(move, duckMove);
        // When engine takes over, we no longer advance the PGN move tree.
        rebuildBoard();
        updateStatus();
        // Eval after bot move.
        scheduleEngineEval();
    }

    @Override
    public void OnEngineInfo(String message) {
        // Optional: show compact engine info
    }

    @Override
    public void OnEngineStarted() {
        if (progressBarBot != null) progressBarBot.setVisibility(View.VISIBLE);
    }

    @Override
    public void OnEngineAborted() {
        if (progressBarBot != null) progressBarBot.setVisibility(View.GONE);
        engineMoveScheduled = false;
    }

    @Override
    public void OnEngineError() {
        if (progressBarBot != null) progressBarBot.setVisibility(View.GONE);
        engineMoveScheduled = false;
        appendEngineDebug("Engine error");
    }

    private void showFatalErrorDialog(Throwable t) {
        String message = (t != null && t.getMessage() != null) ? t.getMessage() : String.valueOf(t);
        String stack = Log.getStackTraceString(t);

        if (textStatus != null) {
            textStatus.setText("ERROR: " + message);
        }

        new AlertDialog.Builder(this)
                .setTitle("Opening Trainer crashed")
                .setMessage(message + "\n\n" + stack)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("OpeningTrainer crash", stack));
                    }
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }
}

