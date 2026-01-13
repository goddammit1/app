package com.goddddd.notification;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
public class DialogActivity extends AppCompatActivity {
    @Override
    public void onBackPressed() {}
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        setContentView(R.layout.activity_dialog);
        setFinishOnTouchOutside(false);
        Button yes = findViewById(R.id.btnYes);
        Button no = findViewById(R.id.btnNo);
        // При нажатии выключаем всё: звук, вибрацию и убираем уведомление
        yes.setOnClickListener(b -> {
            MyFirebaseMessagingService.stopAll(this);
            finish();
        });
        no.setOnClickListener(b -> {
            MyFirebaseMessagingService.stopAll(this);
            finish();
        });
    }
}