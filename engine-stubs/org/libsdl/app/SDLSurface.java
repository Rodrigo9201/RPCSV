package org.libsdl.app;

import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** Stub de compilacao apenas — o codigo real vem do classes2.dex da engine. */
public class SDLSurface extends SurfaceView implements SurfaceHolder.Callback {
    public SDLSurface(Context context) {
        super(context);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }
}
