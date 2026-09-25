package org.vita3k.emulator;

import android.content.Context;
import android.view.SurfaceHolder;

import org.libsdl.app.SDLSurface;

/**
 * Superficie de video do Vita3K no Android.
 *
 * A engine (libVita3K.so) expoe o metodo nativo
 * Java_org_vita3k_emulator_EmuSurface_setSurfaceStatus, que grava um flag
 * global usado pelo renderizador para saber se a superficie Android esta
 * pronta. Sem esse flag a Vulkan nao apresenta nada na tela (tela preta),
 * mesmo com o jogo executando e o audio tocando.
 *
 * A classe oficial (EmuSurface) tambem cria um InputOverlay (botoes de toque
 * na tela). Nao reaproveitamos a oficial porque ela depende de recursos do
 * pacote org.vita3k.emulator (IDs de drawable fixos) que nao existem no
 * pacote deste app.
 *
 * O nome da classe precisa ser exatamente org.vita3k.emulator.EmuSurface
 * porque o vinculo JNI e feito pelo nome da classe + metodo.
 */
public class EmuSurface extends SDLSurface {

    public EmuSurface(Context context) {
        super(context);
    }

    private native void setSurfaceStatus(boolean status);

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        setSurfaceStatus(true);
        super.surfaceCreated(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        setSurfaceStatus(true);
        super.surfaceChanged(holder, format, width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        setSurfaceStatus(false);
        super.surfaceDestroyed(holder);
    }
}
