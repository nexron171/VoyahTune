package ru.big.town.restoremode;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.NumberPicker;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

public class AdvanceActivityStarButton extends AppCompatActivity {
    static final int MSG_APPLY_DRIVE_MODES_STAR_BUTTON = 2;
    private EditText canCommandEditorStarButton1, canCommandEditorStarButton2;
    private Button buttonSaveStarButton, buttonApplyStarButton1, buttonApplyStarButton2, buttonBackStarButton;
    private NumberPicker pickerCustomCommandCountStarButton;
    private Messenger serviceMessenger;
    private boolean isBound;
    static final int MSG_RESULT = 4;

    private String customCommandStarButton1 = "";
    private String customCommandStarButton2 = "";


    public void onButtonClickCleanStarButton1(View v) {
        canCommandEditorStarButton1.setText("");
    }

    public void onButtonClickCleanStarButton2(View v) {
        canCommandEditorStarButton2.setText("");
    }

    public void onButtonClickSaveStarButton(View v) {
        GlobalVars.editor.putString("customCommandStarButton1", canCommandEditorStarButton1.getText().toString());
        GlobalVars.editor.putString("customCommandStarButton2", canCommandEditorStarButton2.getText().toString());
        GlobalVars.editor.apply();
    }

    public void onButtonClickApplyStarButton1(View v) {
        if (GlobalVars.isBound) {
            try {
                Message msg = Message.obtain(null, MSG_APPLY_DRIVE_MODES_STAR_BUTTON);
                msg.replyTo = GlobalVars.clientMessenger;
                msg.arg1=1;
                GlobalVars.serviceMessenger.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public void onButtonClickApplyStarButton2(View v) {
        if (GlobalVars.isBound) {
            try {
                Message msg = Message.obtain(null, MSG_APPLY_DRIVE_MODES_STAR_BUTTON);
                msg.replyTo = GlobalVars.clientMessenger;
                msg.arg1=2;
                GlobalVars.serviceMessenger.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public void onButtonClickBackStarButton(View v) {
        finish();
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_advance_start_button);

        canCommandEditorStarButton1 = findViewById(R.id.rawCanCodesStarButton1);
        buttonApplyStarButton1 = findViewById(R.id.buttonApplyStarButton1);
        canCommandEditorStarButton2 = findViewById(R.id.rawCanCodesStarButton2);
        buttonApplyStarButton2 = findViewById(R.id.buttonApplyStarButton2);

        buttonSaveStarButton = findViewById(R.id.buttonSaveStarButton);
        buttonBackStarButton = findViewById(R.id.buttonBackStarButton);

        customCommandStarButton1 = GlobalVars.sharedPreferences.getString("customCommandStarButton1", "");
        canCommandEditorStarButton1.setText(customCommandStarButton1);
        customCommandStarButton2 = GlobalVars.sharedPreferences.getString("customCommandStarButton2", "");
        canCommandEditorStarButton2.setText(customCommandStarButton2);


        canCommandEditorStarButton1.addTextChangedListener(new TextWatcher() {
            private boolean isFormatting = false;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                Log.i("$$$ beforeTextChanged $$$", s.toString() + String.format("int start, int count, int after: %d, %d %d ", start, count, after));
            }

            @Override
            public void afterTextChanged(Editable s) {
                Log.i("$$$ afterTextChanged $$$", s.toString());

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                Log.i("$$$ onTextChanged $$$", s.toString() + String.format("int start, int before, int count: %d, %d %d ", start, before, count));
                if (isFormatting) return;
                isFormatting = true;

                String input = s.toString().toLowerCase();
                String filtered = input.replaceAll("[^0-9a-f,\n]", "");
                String[] q;
                q = filtered.split("\n");
                StringBuilder formatted = new StringBuilder();
                for (String i : q) {
                    Log.i("LENGTH i", String.format("%s %d", i, i.length()));
                    for (int j = 0; j < i.length(); j++) {
                        if (j % 2 == 0) {
                            formatted.append(" ");
                        }
                        formatted.append(i.charAt(j));
                        if (j >= 19) {
                            formatted.append("\n");
                        }
                    }
                }
                Log.i("$$$ LENGTH formatted.length $$$ ", String.format("%d", formatted.length()));

                if (formatted.length() % 31 == 0) {
                    canCommandEditorStarButton1.setBackgroundColor(Color.WHITE);
                    buttonSaveStarButton.setEnabled(true);
                    buttonSaveStarButton.setTextColor(Color.WHITE);
                    buttonApplyStarButton1.setEnabled(true);
                    buttonApplyStarButton1.setTextColor(Color.WHITE);
                    buttonBackStarButton.setEnabled(true);
                    buttonBackStarButton.setTextColor(Color.WHITE);
                } else {
                    canCommandEditorStarButton1.setBackgroundColor(0xffffafaf);
                    buttonSaveStarButton.setEnabled(false);
                    buttonSaveStarButton.setTextColor(Color.GRAY);
                    buttonApplyStarButton1.setEnabled(false);
                    buttonApplyStarButton1.setTextColor(Color.GRAY);
                    buttonBackStarButton.setEnabled(false);
                    buttonBackStarButton.setTextColor(Color.GRAY);
                }

                canCommandEditorStarButton1.removeTextChangedListener(this);
                canCommandEditorStarButton1.setText(formatted.toString());
                canCommandEditorStarButton1.setSelection(formatted.length());
                canCommandEditorStarButton1.addTextChangedListener(this);
                isFormatting = false;
            }
        });


        canCommandEditorStarButton2.addTextChangedListener(new TextWatcher() {
            private boolean isFormatting = false;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                Log.i("$$$ beforeTextChanged $$$", s.toString() + String.format("int start, int count, int after: %d, %d %d ", start, count, after));
            }

            @Override
            public void afterTextChanged(Editable s) {
                Log.i("$$$ afterTextChanged $$$", s.toString());

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                Log.i("$$$ onTextChanged $$$", s.toString() + String.format("int start, int before, int count: %d, %d %d ", start, before, count));
                if (isFormatting) return;
                isFormatting = true;

                String input = s.toString().toLowerCase();
                String filtered = input.replaceAll("[^0-9a-f,\n]", "");
                String[] q;
                q = filtered.split("\n");
                StringBuilder formatted = new StringBuilder();
                for (String i : q) {
                    Log.i("LENGTH i", String.format("%s %d", i, i.length()));
                    for (int j = 0; j < i.length(); j++) {
                        if (j % 2 == 0) {
                            formatted.append(" ");
                        }
                        formatted.append(i.charAt(j));
                        if (j >= 19) {
                            formatted.append("\n");
                        }
                    }
                }
                Log.i("$$$ LENGTH formatted.length $$$ ", String.format("%d", formatted.length()));

                if (formatted.length() % 31 == 0) {
                    canCommandEditorStarButton2.setBackgroundColor(Color.WHITE);
                    buttonSaveStarButton.setEnabled(true);
                    buttonSaveStarButton.setTextColor(Color.WHITE);
                    buttonApplyStarButton2.setEnabled(true);
                    buttonApplyStarButton2.setTextColor(Color.WHITE);
                    buttonBackStarButton.setEnabled(true);
                    buttonBackStarButton.setTextColor(Color.WHITE);
                } else {
                    canCommandEditorStarButton2.setBackgroundColor(0xffffafaf);
                    buttonSaveStarButton.setEnabled(false);
                    buttonSaveStarButton.setTextColor(Color.GRAY);
                    buttonApplyStarButton2.setEnabled(false);
                    buttonApplyStarButton2.setTextColor(Color.GRAY);
                    buttonBackStarButton.setEnabled(false);
                    buttonBackStarButton.setTextColor(Color.GRAY);
                }

                canCommandEditorStarButton2.removeTextChangedListener(this);
                canCommandEditorStarButton2.setText(formatted.toString());
                canCommandEditorStarButton2.setSelection(formatted.length());
                canCommandEditorStarButton2.addTextChangedListener(this);
                isFormatting = false;
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}