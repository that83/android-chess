package jwtc.android.chess.lichess;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;

import java.util.HashMap;
import java.util.Map;

import jwtc.android.chess.R;
import jwtc.android.chess.helpers.ResultDialog;
import jwtc.android.chess.helpers.ResultDialogListener;
import jwtc.android.chess.helpers.Utils;

public class ChallengeDialog extends ResultDialog<Map<String, Object>> {

    private static final String TAG = "Lichess.ChallengeDialog";
    public static final int REQUEST_CHALLENGE = 1;
    public static final int REQUEST_SEEK = 2;
    
    // Lichess bot usernames by level (1-8)
    private static final String[] LICHESS_BOTS = {
        "stockfish-bot",  // Level 1 (~800 ELO)
        "maia1",          // Level 2 (~1100 ELO)
        "maia5",          // Level 3 (~1400 ELO)
        "maia9",          // Level 4 (~1700 ELO)
        "sf-bot",         // Level 5 (~2000 ELO)
        "stockfish-bot",  // Level 6 (~2300 ELO) - using same bot with different settings
        "stockfish-bot",  // Level 7 (~2600 ELO)
        "stockfish-bot"   // Level 8 (~3000+ ELO)
    };

    public ChallengeDialog(Context context, ResultDialogListener<Map<String, Object>> listener, int requestCode, final SharedPreferences prefs) {
        super(context, listener, requestCode);

        setContentView(R.layout.lichess_challenge);

        setTitle(requestCode == REQUEST_CHALLENGE ? R.string.lichess_create_challenge_title : R.string.lichess_create_seek_title);

        final MaterialCardView playerView = findViewById(R.id.CardViewPlayer);
        playerView.setVisibility(requestCode == REQUEST_CHALLENGE ? View.VISIBLE : View.GONE);

        final EditText editTextPlayer = findViewById(R.id.EditTextMatchOpponent);
        final TextView textViewPlayerName = findViewById(R.id.tvMatchPlayerName);
        final RadioButton radioButtonOpponentHuman = findViewById(R.id.RadioButtonOpponentHuman);
        final RadioButton radioButtonOpponentBot = findViewById(R.id.RadioButtonOpponentBot);
        final LinearLayout layoutBotLevel = findViewById(R.id.LayoutBotLevel);
        final LinearLayout layoutPlayerName = findViewById(R.id.LayoutPlayerName);
        final Spinner spinnerBotLevel = findViewById(R.id.SpinnerBotLevel);

        final RadioButton radioButtonUnlimitedTime = findViewById(R.id.RadioButtonUnlimitedTime);
        final RadioButton radioButtonTimeControl = findViewById(R.id.RadioButtonTimeControl);

        final LinearLayout layoutDays = findViewById(R.id.LayoutDays);
        final LinearLayout layoutMinutes = findViewById(R.id.LayoutMinutes);
        final LinearLayout layoutIncrement = findViewById(R.id.LayoutIncrement);
        final EditText editTextDays = findViewById(R.id.EditTextDays);
        final EditText editTextTime = findViewById(R.id.EditTextMinutes);
        final EditText editTextIncrement = findViewById(R.id.EditTextIncrement);

        final RadioButton radioButtonVariantDefault = findViewById(R.id.RadioButtonStandard);
        final RadioButton radioButtonVariantChess960 = findViewById(R.id.RadioButtonChess960);

        final RadioButton radioButtonRandom = findViewById(R.id.RadioButtonRandom);
        final RadioButton radioButtonWhite = findViewById(R.id.RadioButtonWhite);
        final RadioButton radioButtonBlack = findViewById(R.id.RadioButtonBlack);

        final CheckBox checkBoxRated = findViewById(R.id.CheckBoxSeekRated);

        // Setup bot level spinner
        if (requestCode == REQUEST_CHALLENGE) {
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                context,
                R.array.lichess_bot_levels,
                android.R.layout.simple_spinner_item
            );
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerBotLevel.setAdapter(adapter);
            
            // Load saved opponent type preference
            boolean isBot = prefs.getBoolean("lichess_challenge_opponent_bot", false);
            int savedBotLevel = prefs.getInt("lichess_challenge_bot_level", 0);
            if (savedBotLevel > 0 && savedBotLevel <= LICHESS_BOTS.length) {
                spinnerBotLevel.setSelection(savedBotLevel - 1);
            }
            
            radioButtonOpponentHuman.setChecked(!isBot);
            radioButtonOpponentBot.setChecked(isBot);
            updateOpponentTypeUI(isBot, layoutBotLevel, layoutPlayerName, editTextPlayer, spinnerBotLevel, prefs);
            
            // Listener for bot level spinner
            spinnerBotLevel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (radioButtonOpponentBot.isChecked()) {
                        int botLevel = position + 1;
                        if (botLevel >= 1 && botLevel <= LICHESS_BOTS.length) {
                            editTextPlayer.setText(LICHESS_BOTS[botLevel - 1]);
                        }
                    }
                }
                
                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        } else {
            // initial values for human player
            editTextPlayer.setText(prefs.getString("lichess_challenge_name", ""));
        }
        boolean withTimeControl = prefs.getBoolean("lichess_challenge_timetcontrol", true);
        int days = prefs.getInt("lichess_challenge_days", 1);
        editTextDays.setText("" + days);
        int minutes = prefs.getInt("lichess_challenge_minutes", 5);
        editTextTime.setText("" + minutes);
        editTextIncrement.setText("" + prefs.getInt("lichess_challenge_increment", 10));

        if (withTimeControl) {
            layoutDays.setVisibility(View.GONE);
            layoutMinutes.setVisibility(View.VISIBLE);
            layoutIncrement.setVisibility(View.VISIBLE);
            radioButtonTimeControl.setChecked(true);
            radioButtonUnlimitedTime.setChecked(false);
        } else {
            layoutDays.setVisibility(View.VISIBLE);
            layoutMinutes.setVisibility(View.INVISIBLE);
            layoutIncrement.setVisibility(View.GONE);
            radioButtonTimeControl.setChecked(false);
            radioButtonUnlimitedTime.setChecked(true);
        }

        String variant = prefs.getString("lichess_challenge_variant", "standard");
        radioButtonVariantDefault.setChecked(variant.equals("standard"));
        radioButtonVariantChess960.setChecked(!radioButtonVariantDefault.isChecked());
        radioButtonVariantDefault.setEnabled(false); // @TODO until castling is fixed
        radioButtonVariantChess960.setEnabled(false);

        String color = prefs.getString("lichess_challenge_color", "random");
        radioButtonRandom.setChecked(color.equals("random"));
        radioButtonWhite.setChecked(color.equals("white"));
        radioButtonBlack.setChecked(color.equals("black"));

        checkBoxRated.setChecked(prefs.getBoolean("lichess_challenge_rated", false));

        // radio group behaviour
        radioButtonUnlimitedTime.setOnClickListener(v -> {
            radioButtonTimeControl.setChecked(!radioButtonUnlimitedTime.isChecked());
            layoutDays.setVisibility(View.VISIBLE);
            layoutMinutes.setVisibility(View.INVISIBLE);
            layoutIncrement.setVisibility(View.GONE);
        });
        radioButtonTimeControl.setOnClickListener(v -> {
            radioButtonUnlimitedTime.setChecked(!radioButtonTimeControl.isChecked());
            layoutDays.setVisibility(View.GONE);
            layoutMinutes.setVisibility(View.VISIBLE);
            layoutIncrement.setVisibility(View.VISIBLE);
        });

        radioButtonVariantDefault.setOnClickListener(v -> radioButtonVariantChess960.setChecked(!radioButtonVariantDefault.isChecked()));
        radioButtonVariantChess960.setOnClickListener(v -> radioButtonVariantDefault.setChecked(!radioButtonVariantChess960.isChecked()));

        radioButtonRandom.setOnClickListener(v -> {
            radioButtonWhite.setChecked(!radioButtonRandom.isChecked());
            radioButtonBlack.setChecked(!radioButtonRandom.isChecked());
        });
        radioButtonWhite.setOnClickListener(v -> {
            radioButtonRandom.setChecked(!radioButtonWhite.isChecked());
            radioButtonBlack.setChecked(!radioButtonWhite.isChecked());
        });
        radioButtonBlack.setOnClickListener(v -> {
            radioButtonWhite.setChecked(!radioButtonBlack.isChecked());
            radioButtonRandom.setChecked(!radioButtonBlack.isChecked());
        });

        // Opponent type radio buttons
        if (requestCode == REQUEST_CHALLENGE) {
            radioButtonOpponentHuman.setOnClickListener(v -> {
                radioButtonOpponentBot.setChecked(!radioButtonOpponentHuman.isChecked());
                updateOpponentTypeUI(false, layoutBotLevel, layoutPlayerName, editTextPlayer, spinnerBotLevel, prefs);
            });
            
            radioButtonOpponentBot.setOnClickListener(v -> {
                radioButtonOpponentHuman.setChecked(!radioButtonOpponentBot.isChecked());
                updateOpponentTypeUI(true, layoutBotLevel, layoutPlayerName, editTextPlayer, spinnerBotLevel, prefs);
            });
        }

        final Button buttonOk = findViewById(R.id.ButtonChallengeOk);
        buttonOk.setOnClickListener(v -> {

            SharedPreferences.Editor editor = prefs.edit();
            Map<String, Object> data = new HashMap<>();

            // username
            if (requestCode == REQUEST_CHALLENGE) {
                String username;
                boolean isBot = radioButtonOpponentBot.isChecked();
                
                if (isBot) {
                    int botLevel = spinnerBotLevel.getSelectedItemPosition() + 1;
                    if (botLevel >= 1 && botLevel <= LICHESS_BOTS.length) {
                        username = LICHESS_BOTS[botLevel - 1];
                        editor.putBoolean("lichess_challenge_opponent_bot", true);
                        editor.putInt("lichess_challenge_bot_level", botLevel);
                    } else {
                        username = editTextPlayer.getText().toString();
                    }
                } else {
                    username = editTextPlayer.getText().toString();
                    editor.putBoolean("lichess_challenge_opponent_bot", false);
                }
                
                if (!username.isEmpty()) {
                    data.put("username", username);
                    if (!isBot) {
                        editor.putString("lichess_challenge_name", username);
                    }
                }
            }

            // timecontrol
            if (radioButtonTimeControl.isChecked()) {

                int editMinutes = Utils.parseInt(editTextTime.getText().toString(), 5);
                int increment = Utils.parseInt(editTextIncrement.getText().toString(), 0);

                editor.putBoolean("lichess_challenge_timetcontrol", true);
                editor.putInt("lichess_challenge_minutes", editMinutes);
                editor.putInt("lichess_challenge_increment", increment);

                if (editMinutes >= 3) {
                    editTextTime.setError(null);

                    if (requestCode == REQUEST_CHALLENGE) {
                        data.put("clock.limit", editMinutes * 60);
                        data.put("clock.increment", increment);
                    } else {
                        data.put("time", editMinutes);
                        data.put("increment", increment);
                    }
                } else {
                    editTextTime.setError("Number must be greater than 2");
                    return;
                }
            } else {
                int editDays = Utils.parseInt(editTextDays.getText().toString(), 1);
                editor.putBoolean("lichess_challenge_timetcontrol", false);
                editor.putInt("lichess_challenge_days", editDays);
                data.put("days", editDays);
            }

            // variant
            String editVariant = radioButtonVariantDefault.isChecked() ? "standard" : "chess960";
            editor.putString("lichess_challenge_variant", editVariant);
            data.put("variant", editVariant);

            // color
            String editColor = radioButtonRandom.isChecked()
                    ? "random" : radioButtonWhite.isChecked() ? "white" : "black";

            editor.putString("lichess_challenge_color", editColor);
            data.put("color", editColor);

            editor.putBoolean("lichess_challenge_rated", checkBoxRated.isChecked());
            data.put("rated", checkBoxRated.isChecked());

            editor.apply();

            ChallengeDialog.this.dismiss();
            setResult(data);
        });
        final Button buttonCancel = findViewById(R.id.ButtonChallengeCancel);
        buttonCancel.setOnClickListener(v -> {
            ChallengeDialog.this.dismiss();
            setResult(null);
        });
    }
    
    private void updateOpponentTypeUI(boolean isBot, LinearLayout layoutBotLevel, 
                                     LinearLayout layoutPlayerName, EditText editTextPlayer,
                                     Spinner spinnerBotLevel, SharedPreferences prefs) {
        if (isBot) {
            layoutBotLevel.setVisibility(View.VISIBLE);
            layoutPlayerName.setVisibility(View.GONE);
            // Auto-fill bot username when bot level is selected
            int botLevel = spinnerBotLevel.getSelectedItemPosition() + 1;
            if (botLevel >= 1 && botLevel <= LICHESS_BOTS.length) {
                editTextPlayer.setText(LICHESS_BOTS[botLevel - 1]);
            } else {
                editTextPlayer.setText(LICHESS_BOTS[0]); // Default to level 1
            }
        } else {
            layoutBotLevel.setVisibility(View.GONE);
            layoutPlayerName.setVisibility(View.VISIBLE);
            // Restore saved player name or clear
            String savedName = prefs.getString("lichess_challenge_name", "");
            editTextPlayer.setText(savedName);
        }
    }
}