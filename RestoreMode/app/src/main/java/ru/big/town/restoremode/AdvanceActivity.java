package ru.big.town.restoremode;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.text.TextWatcher;
import android.widget.NumberPicker;

import java.util.List;


public class AdvanceActivity extends AppCompatActivity {
    private EditText canCommandEditor;
    private Button buttonBack;
    private NumberPicker pickerCustomCommandCount;
    public void onButtonClickFinish(View v){
        Intent intent = new Intent();
                intent.putExtra("customCommand", canCommandEditor.getText().toString());
                intent.putExtra("customCommandCount", pickerCustomCommandCount.getValue());
        setResult(RESULT_OK, intent);
        finish();
    }
    public void onButtonClickClean(View v){
        canCommandEditor.setText("");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_advance);
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
//            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
//            return insets;
//        });
        canCommandEditor = findViewById(R.id.rawCanCodes);
        buttonBack = findViewById(R.id.buttonBack);
        pickerCustomCommandCount = findViewById(R.id.pickerCustomCommandCount);
        pickerCustomCommandCount.setMaxValue(10);
        pickerCustomCommandCount.setMinValue(1);
        pickerCustomCommandCount.setTextColor(0xffffffff);
        pickerCustomCommandCount.setTextSize(40f);


        Intent intent = getIntent();
            if (intent != null) {
                // Extract data from the intent
                String customCommand = intent.getStringExtra("customCommand");
                int customCommandCount = intent.getIntExtra("customCommandCount",1);
                canCommandEditor.setText(customCommand);
                pickerCustomCommandCount.setValue(customCommandCount);
                Log.i("$$$ Advance Create $$$$", String.format("%s %d", customCommand, customCommandCount));
                // Do something with the data
            }
        canCommandEditor.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    Log.i("$$$ setOnFocusChangeListener $$$$", "FOCUS ON");
                } else {
                    Log.i("$$$ setOnFocusChangeListener $$$$", "FOCUS OFF");

                }
            }
        });


        canCommandEditor.addTextChangedListener(new TextWatcher() {
            private boolean isFormatting = false;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                Log.i("$$$ beforeTextChanged $$$", s.toString()+String.format("int start, int count, int after: %d, %d %d ", start,count,after));
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                Log.i("$$$ onTextChanged $$$", s.toString()+String.format("int start, int before, int count: %d, %d %d ", start,before,count));
                if (isFormatting) return;
                isFormatting = true;

                String input = s.toString().toLowerCase();
                String filtered = input.replaceAll("[^0-9a-f,\n]", "");
                String[] q;
                q=filtered.split("\n");
                StringBuilder formatted = new StringBuilder();
                for(String i: q){
                    Log.i("LENGTH i",String.format("%s %d",i,i.length()));
                    for(int j=0; j<i.length(); j++){
                        if(j % 2 == 0){
                            formatted.append(" ");
                        }
                        formatted.append(i.charAt(j));
                        if(j >= 19){
                           formatted.append("\n");
                        }
                    }
                }
                Log.i("$$$ LENGTH formatted.length $$$ ",String.format("%d",formatted.length()));

                if(formatted.length() % 31 == 0){
                    canCommandEditor.setBackgroundColor(Color.WHITE);
                    buttonBack.setEnabled(true);
                    buttonBack.setTextColor(Color.WHITE);
                } else {
                    canCommandEditor.setBackgroundColor(0xffffafaf);
                    buttonBack.setEnabled(false);
                    buttonBack.setTextColor(Color.GRAY);
                }

                canCommandEditor.removeTextChangedListener(this);
                    canCommandEditor.setText(formatted.toString());
                    canCommandEditor.setSelection(formatted.length());
                    canCommandEditor.addTextChangedListener(this);
                    isFormatting = false;
            }

            @Override
            public void afterTextChanged(Editable s) {
                Log.i("$$$ afterTextChanged $$$", s.toString());

            }
        });


    }
}