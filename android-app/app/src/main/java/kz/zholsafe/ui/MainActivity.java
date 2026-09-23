package kz.zholsafe.ui;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import kz.zholsafe.R;

/**
 * Vehicle UI shell. Stage 0: placeholder status screen only.
 *
 * <p>Rule: no business logic in Activities. Stage 1 will bind a ViewModel-free controller that
 * observes pipeline state and RiskAssessments coming from the core module.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TextView status = findViewById(R.id.statusText);
        status.setText(R.string.status_placeholder);
    }
}
