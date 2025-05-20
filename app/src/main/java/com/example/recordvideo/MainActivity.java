package com.example.recordvideo;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.EdgeToEdge;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private VideoEncoder encoder;
    private Handler handler = new Handler(Looper.getMainLooper());
    private int frameIndex = 0;
    private List<Bitmap> bitmapList;

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        bitmapList = Arrays.asList(
                BitmapFactory.decodeResource(getResources(), R.drawable.img1),
                BitmapFactory.decodeResource(getResources(), R.drawable.img2),
                BitmapFactory.decodeResource(getResources(), R.drawable.img3)
        );

        encoder = new VideoEncoder(getApplicationContext(), 720, 1280, 30);
        encoder.start();

        handler.post(frameRunnable);
    }
    private Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            if (frameIndex < bitmapList.size()) {
                encoder.drawBitmap(bitmapList.get(frameIndex));
                frameIndex++;
                handler.postDelayed(this, 1000 / 30); // 30 FPS
            } else {
                encoder.stop();
            }
        }
    };
}