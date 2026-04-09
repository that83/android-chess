package jwtc.android.chess.cynus;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jwtc.android.chess.R;
import jwtc.android.chess.helpers.ActivityHelper;
import jwtc.chess.JNI;
import jwtc.chess.Move;

/**
 * Connects CYNUS-* Bluetooth LE chess boards and replies with moves from the built-in native engine,
 * mirroring the Windows Python + Stockfish script (fen: / get move → move uci).
 */
public class CynusActivity extends AppCompatActivity {

    private static final String TAG = "CynusActivity";
    private static final UUID CCC_DESCRIPTOR_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private ListView listDevices;
    private Button buttonScan;
    private Button buttonDisconnect;
    private Button buttonExportPgn;
    private SwitchMaterial switchAppEngine;
    private SwitchMaterial switchFlipBoard;
    private TextView textLog;
    private ScrollView scrollLog;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService cynusExecutor = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String, BluetoothDevice> found = new LinkedHashMap<>();
    private final ArrayList<String> displayLines = new ArrayList<>();
    private ArrayAdapter<String> listAdapter;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner leScanner;
    private boolean scanning;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic cynusCharacteristic;
    private final StringBuilder rxBuffer = new StringBuilder();
    private boolean bleReadyForWrites;
    private boolean autoConnectInProgress;
    private boolean autoConnectAttempted;

    private String lastFenNormalized;
    private boolean pendingMoveRequest;

    /** Avoid sending BLE / side effects when resetting switches programmatically. */
    private boolean suppressFlipBoardCallback;
    private boolean suppressAppEngineCallback;

    /** When using external engine control, the board internal engine must be disabled first. */
    private boolean internalEngineOffSent;
    /** UI meaning: true => bot should calculate White moves. */
    private boolean botPlaysWhite;
    /** True when app JNI engine should answer "get move". */
    private boolean useAppEngine;

    /** Simple session PGN tracking (best-effort; tolerant to out-of-order presses). */
    private final ArrayList<String> pgnSanPlies = new ArrayList<>();
    private String pgnPrevFen;
    private String pgnStartFen;

    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.cynus_activity);
        ActivityHelper.fixPaddings(this, findViewById(R.id.cynus_root));

        listDevices = findViewById(R.id.ListViewCynusDevices);
        buttonScan = findViewById(R.id.ButtonCynusScan);
        buttonDisconnect = findViewById(R.id.ButtonCynusDisconnect);
        buttonExportPgn = findViewById(R.id.ButtonCynusExportPgn);
        switchAppEngine = findViewById(R.id.SwitchCynusAppEngine);
        switchFlipBoard = findViewById(R.id.SwitchCynusFlipBoard);
        textLog = findViewById(R.id.TextViewCynusLog);
        scrollLog = findViewById(R.id.ScrollViewCynusLog);

        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayLines);
        listDevices.setAdapter(listAdapter);
        listDevices.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= displayLines.size()) return;
            String line = displayLines.get(position);
            int idx = line.lastIndexOf(' ');
            if (idx < 0) return;
            String addr = line.substring(idx + 1);
            BluetoothDevice dev = found.get(addr);
            if (dev != null) {
                stopScan();
                connectGatt(dev);
            }
        });

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    if (hasBlePermissions()) {
                        appendLog(getString(R.string.cynus_perm_ok));
                        maybeAutoScanAndConnect();
                    } else {
                        Toast.makeText(this, R.string.cynus_perm_denied, Toast.LENGTH_LONG).show();
                    }
                });

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
        }

        buttonScan.setOnClickListener(v -> {
            if (!hasBlePermissions()) {
                requestBlePermissions();
                return;
            }
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                Toast.makeText(this, R.string.cynus_bt_disabled, Toast.LENGTH_LONG).show();
                return;
            }
            startScan();
        });

        buttonDisconnect.setOnClickListener(v -> disconnectGatt());
        buttonExportPgn.setOnClickListener(v -> exportPgnToClipboard());

        // "Use app's engine" ON => disable board internal engine via BLE; OFF => board plays.
        switchAppEngine.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressAppEngineCallback) return;
            useAppEngine = isChecked;
            if (!bleReadyForWrites || bluetoothGatt == null || cynusCharacteristic == null) {
                return;
            }
            if (isChecked) {
                if (!internalEngineOffSent) {
                    internalEngineOffSent = true;
                    sendInternalEngineOffBleCommand();
                }
            } else {
                internalEngineOffSent = false;
                sendInternalEngineOnBleCommand();
            }
        });

        // "Bot play WHITE": in board-engine mode => only sends flip command.
        // In app-engine mode => only affects which side JNI plays (no BLE flip).
        switchFlipBoard.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressFlipBoardCallback) return;
            if (bluetoothGatt == null || cynusCharacteristic == null) {
                // Not connected; snap back to OFF silently.
                suppressFlipBoardCallback = true;
                switchFlipBoard.setChecked(false);
                suppressFlipBoardCallback = false;
                return;
            }
            botPlaysWhite = isChecked;
            if (!useAppEngine) {
                sendFlipBoardBleCommand(isChecked);
            }
        });

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            appendLog(getString(R.string.cynus_no_le));
            buttonScan.setEnabled(false);
        } else {
            requestBlePermissions();
            // Auto flow: enter screen -> scan -> connect first CYNUS-* found.
            maybeAutoScanAndConnect();
        }
    }

    private void maybeAutoScanAndConnect() {
        if (autoConnectAttempted || autoConnectInProgress) return;
        if (!hasBlePermissions()) return;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        autoConnectAttempted = true;
        autoConnectInProgress = true;
        appendLog("Auto scan/connect CYNUS-* ...");
        startScan();
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBlePermissions() {
        if (hasBlePermissions()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            });
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            });
        }
    }

    private void startScan() {
        if (scanning || bluetoothAdapter == null) return;
        leScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (leScanner == null) {
            appendLog("LE scanner null");
            return;
        }
        found.clear();
        displayLines.clear();
        listAdapter.notifyDataSetChanged();
        scanning = true;
        appendLog(getString(R.string.cynus_scanning));
        leScanner.startScan(null, new ScanSettings.Builder().build(), scanCallback);
        mainHandler.postDelayed(this::stopScan, 20_000);
    }

    private void stopScan() {
        if (!scanning || leScanner == null) return;
        try {
            leScanner.stopScan(scanCallback);
        } catch (Exception e) {
            Log.w(TAG, "stopScan", e);
        }
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            String name = dev.getName();
            if (name == null && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            if (name == null) return;
            if (!name.startsWith("CYNUS-")) return;
            String addr = dev.getAddress();
            if (found.containsKey(addr)) return;
            found.put(addr, dev);
            displayLines.add(name + "  " + addr);
            runOnUiThread(() -> listAdapter.notifyDataSetChanged());

            if (autoConnectInProgress) {
                autoConnectInProgress = false;
                stopScan();
                connectGatt(dev);
            }
        }
    };

    private void connectGatt(BluetoothDevice device) {
        disconnectGatt();
        appendLog(getString(R.string.cynus_connecting, device.getAddress()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
        buttonDisconnect.setEnabled(true);
    }

    private void disconnectGatt() {
        stopScan();
        cynusCharacteristic = null;
        bleReadyForWrites = false;
        rxBuffer.setLength(0);
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        buttonDisconnect.setEnabled(false);
        internalEngineOffSent = false;
        botPlaysWhite = false;
        useAppEngine = false;
        pgnSanPlies.clear();
        pgnPrevFen = null;
        pgnStartFen = null;
        setCynusBleControlsEnabled(false);
        appendLog(getString(R.string.cynus_disconnected));
    }

    /**
     * Switches are always visible; enable only when BLE is ready for writes.
     * Resets to defaults when disconnected.
     */
    private void setCynusBleControlsEnabled(boolean enabled) {
        runOnUiThread(() -> {
            suppressAppEngineCallback = true;
            suppressFlipBoardCallback = true;
            if (switchAppEngine != null) {
                switchAppEngine.setEnabled(enabled);
                if (!enabled) {
                    switchAppEngine.setChecked(false);
                    useAppEngine = false;
                    internalEngineOffSent = false;
                }
            }
            if (switchFlipBoard != null) {
                switchFlipBoard.setEnabled(enabled);
                switchFlipBoard.setChecked(false);
                botPlaysWhite = false;
            }
            suppressAppEngineCallback = false;
            suppressFlipBoardCallback = false;
        });
    }

    /** Protocol: newline-terminated lines as in device docs. */
    private void sendFlipBoardBleCommand(boolean flipOn) {
        String cmd = flipOn ? "set flip board on\r\n" : "set flip board off\r\n";
        appendLog("→ " + cmd.replace("\r", "").replace("\n", "").trim());
        writeToBoard(cmd.getBytes(StandardCharsets.UTF_8));
    }

    private void sendInternalEngineOffBleCommand() {
        String cmd = "set internal engine off\r\n";
        appendLog("→ " + cmd.replace("\r", "").replace("\n", "").trim());
        writeToBoard(cmd.getBytes(StandardCharsets.UTF_8));
    }

    private void sendInternalEngineOnBleCommand() {
        String cmd = "set internal engine on\r\n";
        appendLog("→ " + cmd.replace("\r", "").replace("\n", "").trim());
        writeToBoard(cmd.getBytes(StandardCharsets.UTF_8));
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLog(getString(R.string.cynus_connected));
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog(getString(R.string.cynus_gatt_disconnect));
                cynusCharacteristic = null;
                setCynusBleControlsEnabled(false);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("discover fail: " + status);
                return;
            }
            BluetoothGattCharacteristic ch = findCynusCharacteristic(gatt);
            if (ch == null) {
                appendLog(getString(R.string.cynus_char_not_found));
                return;
            }
            cynusCharacteristic = ch;
            gatt.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor ccc = ch.getDescriptor(CCC_DESCRIPTOR_UUID);
            if (ccc != null) {
                ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean ok = gatt.writeDescriptor(ccc);
                if (!ok) {
                    appendLog("write CCC failed");
                    // Continue anyway; some firmwares still work without CCC write.
                    onBoardReadyAfterGattSetup();
                }
            } else {
                appendLog(getString(R.string.cynus_no_ccc));
                onBoardReadyAfterGattSetup();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (CCC_DESCRIPTOR_UUID.equals(descriptor.getUuid())) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    appendLog("CCC write status: " + status);
                }
                onBoardReadyAfterGattSetup();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value == null || value.length == 0) return;
            String chunk = new String(value, StandardCharsets.UTF_8);
            synchronized (rxBuffer) {
                rxBuffer.append(chunk);
                drainRxLines();
            }
        }
    };

    private void onBoardReadyAfterGattSetup() {
        if (bleReadyForWrites) return;
        bleReadyForWrites = true;
        appendLog(getString(R.string.cynus_ready));
        runOnUiThread(() -> {
            if (switchAppEngine != null) {
                switchAppEngine.setEnabled(true);
            }
            if (switchFlipBoard != null) {
                switchFlipBoard.setEnabled(true);
            }
        });
    }

    private BluetoothGattCharacteristic findCynusCharacteristic(BluetoothGatt gatt) {
        UUID target = CynusHelper.cynusCharacteristicUuid();
        for (BluetoothGattService svc : gatt.getServices()) {
            for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                UUID u = ch.getUuid();
                if (u.equals(target)) {
                    return ch;
                }
                String us = u.toString().toLowerCase(Locale.US);
                if (us.startsWith("0000fff1")) {
                    return ch;
                }
            }
        }
        return null;
    }

    private void drainRxLines() {
        while (true) {
            int cut = findLineBreak(rxBuffer);
            if (cut < 0) break;
            String line = rxBuffer.substring(0, cut).trim();
            rxBuffer.delete(0, cut + 1);
            if (!line.isEmpty()) {
                mainHandler.post(() -> handleProtocolLine(line));
            }
        }
    }

    private static int findLineBreak(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '\n') return i;
            if (c == '\r') {
                if (i + 1 < sb.length() && sb.charAt(i + 1) == '\n') {
                    return i + 1;
                }
                return i;
            }
        }
        return -1;
    }

    private void handleProtocolLine(String line) {
        appendLog("← " + line);
        if (line.startsWith("fen:")) {
            String payload = line.substring(4).trim();
            appendLog("fen raw: " + payload);
            lastFenNormalized = CynusHelper.normalizeIncomingFen(payload, false);
            appendLog("fen normalized: " + lastFenNormalized);
            captureUserMoveFromFen(lastFenNormalized);
            if (pendingMoveRequest && lastFenNormalized != null) {
                pendingMoveRequest = false;
                if (useAppEngine) {
                    String fenForEngine = CynusHelper.forceSideToMove(lastFenNormalized, botPlaysWhite);
                    appendLog("fen forced for engine: " + fenForEngine);
                    runEngineAndSendMove(fenForEngine);
                }
            }
        } else if (line.regionMatches(true, 0, "get move", 0, 8)) {
            pendingMoveRequest = true;
            if (lastFenNormalized != null) {
                if (useAppEngine) {
                    pendingMoveRequest = false;
                    String fenForEngine = CynusHelper.forceSideToMove(lastFenNormalized, botPlaysWhite);
                    appendLog("fen forced for get move: " + fenForEngine);
                    runEngineAndSendMove(fenForEngine);
                }
            }
        }
    }

    private void runEngineAndSendMove(String fen) {
        cynusExecutor.execute(() -> {
            try {
                JNI jni = JNI.getInstance();
                jni.interrupt();
                Thread.sleep(30);
                if (!jni.initFEN(fen)) {
                    mainHandler.post(() -> appendLog("initFEN failed: " + fen));
                    return;
                }
                if (jni.isEnded() != 0) {
                    mainHandler.post(() -> appendLog(getString(R.string.cynus_jni_ended)));
                    return;
                }
                jni.searchMove(1000, 0);
                int mv = jni.getMove();
                if (mv == 0) {
                    mainHandler.post(() -> appendLog(getString(R.string.cynus_no_best)));
                    return;
                }
                String uci = CynusHelper.moveToUci(mv);
                if (uci.isEmpty()) {
                    mainHandler.post(() -> appendLog("UCI empty move=" + mv + " dbg=" + Move.toDbgString(mv)));
                    return;
                }
                // Advance local board for PGN tracking.
                jni.move(mv);
                String botSan = jni.getMyMoveToString();
                String fenAfterBot = jni.toFEN();
                if (botSan != null && !botSan.trim().isEmpty()) {
                    pgnSanPlies.add(botSan.trim());
                } else {
                    pgnSanPlies.add(uci);
                }
                pgnPrevFen = fenAfterBot;
                byte[] out = ("move " + uci + "\r\n").getBytes(StandardCharsets.UTF_8);
                mainHandler.post(() -> appendLog("→ move " + uci));
                writeToBoard(out);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "engine", e);
                mainHandler.post(() -> appendLog("engine: " + e.getMessage()));
            }
        });
    }

    private void writeToBoard(byte[] data) {
        runOnUiThread(() -> {
            if (bluetoothGatt == null || cynusCharacteristic == null || !bleReadyForWrites) {
                appendLog(getString(R.string.cynus_write_no_conn));
                return;
            }
            cynusCharacteristic.setValue(data);
            int props = cynusCharacteristic.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                cynusCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                cynusCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }
            boolean ok = bluetoothGatt.writeCharacteristic(cynusCharacteristic);
            if (!ok) {
                appendLog(getString(R.string.cynus_write_fail));
            }
        });
    }

    private void appendLog(String s) {
        runOnUiThread(() -> {
            Log.i(TAG, s);
            textLog.append(s);
            textLog.append("\n");
            if (scrollLog != null) {
                scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private static String fenBoardPart(String fen) {
        if (fen == null) return "";
        int idx = fen.indexOf(' ');
        return idx > 0 ? fen.substring(0, idx) : fen;
    }

    private void captureUserMoveFromFen(String currentFenNormalized) {
        if (currentFenNormalized == null || currentFenNormalized.isEmpty()) return;
        if (pgnPrevFen == null) {
            pgnPrevFen = currentFenNormalized;
            if (pgnStartFen == null) {
                pgnStartFen = currentFenNormalized;
            }
            return;
        }
        if (fenBoardPart(pgnPrevFen).equals(fenBoardPart(currentFenNormalized))) {
            return;
        }
        try {
            JNI jni = JNI.getInstance();
            if (!jni.initFEN(pgnPrevFen)) {
                pgnPrevFen = currentFenNormalized;
                return;
            }
            int n = jni.getMoveArraySize();
            String targetBoard = fenBoardPart(currentFenNormalized);
            for (int i = 0; i < n; i++) {
                int mv = jni.getMoveArrayAt(i);
                if (!jni.initFEN(pgnPrevFen)) continue;
                jni.move(mv);
                String fenAfter = jni.toFEN();
                if (targetBoard.equals(fenBoardPart(fenAfter))) {
                    String san = jni.getMyMoveToString();
                    if (san != null && !san.trim().isEmpty()) {
                        pgnSanPlies.add(san.trim());
                    } else {
                        pgnSanPlies.add(CynusHelper.moveToUci(mv));
                    }
                    pgnPrevFen = fenAfter;
                    return;
                }
            }
        } catch (Exception ignore) {
            // Best effort only.
        }
        // Fallback if we cannot infer exact user move.
        pgnSanPlies.add("{?}");
        pgnPrevFen = currentFenNormalized;
    }

    private String buildPgnText() {
        StringBuilder sb = new StringBuilder();
        sb.append("[Event \"Cynus Session\"]\n");
        sb.append("[Site \"Android\"]\n");
        sb.append("[Result \"*\"]\n");
        if (pgnStartFen != null && !pgnStartFen.isEmpty()
                && !pgnStartFen.startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")) {
            sb.append("[FEN \"").append(pgnStartFen.replace("\"", "'")).append("\"]\n");
            sb.append("[SetUp \"1\"]\n");
        }
        sb.append("\n");
        for (int i = 0; i < pgnSanPlies.size(); i++) {
            if (i % 2 == 0) {
                sb.append((i / 2) + 1).append(". ");
            }
            sb.append(pgnSanPlies.get(i)).append(' ');
        }
        sb.append("*\n");
        return sb.toString();
    }

    private void exportPgnToClipboard() {
        String pgn = buildPgnText();
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            cb.setPrimaryClip(ClipData.newPlainText("Cynus PGN", pgn));
            appendLog(getString(R.string.cynus_pgn_copied));
        }
    }

    @Override
    protected void onDestroy() {
        disconnectGatt();
        cynusExecutor.shutdownNow();
        super.onDestroy();
    }

}
