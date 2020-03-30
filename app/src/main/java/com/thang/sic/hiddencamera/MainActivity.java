package com.thang.sic.hiddencamera;

import android.app.Activity;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;

import com.thang.sic.hiddencamera.Camera.Camera2Fragment;

public class MainActivity extends Activity
{
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.atc_main);
        if (null == savedInstanceState) {
            getFragmentManager().beginTransaction()
                .replace(R.id.container, Camera2Fragment.newInstance())
                .commit();
        }
    }
}
