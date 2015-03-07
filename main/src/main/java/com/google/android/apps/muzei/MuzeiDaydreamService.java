package com.google.android.apps.muzei;

import android.app.Service;
import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.IBinder;
import android.service.dreams.DreamService;
import android.view.WindowManager;

import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.event.LockScreenVisibleChangedEvent;
import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent;
import com.google.android.apps.muzei.render.MuzeiBlurRenderer;
import com.google.android.apps.muzei.render.RealRenderController;
import com.google.android.apps.muzei.render.RenderController;

import de.greenrobot.event.EventBus;

public class MuzeiDaydreamService extends DreamService implements
        RenderController.Callbacks,
        MuzeiBlurRenderer.Callbacks {

    private static final long TEMPORARY_FOCUS_DURATION_MILLIS = 3000;

    private GLSurfaceView mGLView;
    private RenderController mRenderController;
    private MuzeiBlurRenderer mRenderer;
    private boolean mArtDetailMode = false;
    private Handler mMainThreadHandler = new Handler();

    public MuzeiDaydreamService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mGLView = new GLSurfaceView(this);
        mRenderer = new MuzeiBlurRenderer(MuzeiDaydreamService.this, this);
        mRenderer.setIsPreview(false);
        mGLView.setEGLContextClientVersion(2);
        mGLView.setRenderer(mRenderer);
        mRenderController = new RealRenderController(MuzeiDaydreamService.this,
                mRenderer, this);
        mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        EventBus.getDefault().postSticky(new WallpaperActiveStateChangedEvent(true));
        EventBus.getDefault().registerSticky(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        EventBus.getDefault().postSticky(new WallpaperActiveStateChangedEvent(false));

        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                if (mRenderer != null) {
                    mRenderer.destroy();
                }
            }
        });
        mRenderController.destroy();
    }
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        setFullscreen(true);
        setContentView(mGLView);

        mRenderController.reloadCurrentArtwork(true);
        requestRender();
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        super.onWindowAttributesChanged(attrs);

        EventBus.getDefault().postSticky(new WallpaperSizeChangedEvent(attrs.width, attrs.height));

        mRenderController.reloadCurrentArtwork(true);
        requestRender();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();

        mRenderController.setVisible(true);
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();

        mRenderController.setVisible(false);
    }

    @Override
    public void queueEventOnGlThread(Runnable runnable) {
        mGLView.queueEvent(runnable);
    }

    @Override
    public void requestRender() {
        mGLView.requestRender();
    }

    private void cancelDelayedBlur() {
        mMainThreadHandler.removeCallbacks(mBlurRunnable);
    }

    private Runnable mBlurRunnable = new Runnable() {
        @Override
        public void run() {
            mGLView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    mRenderer.setIsBlurred(true, false);
                }
            });
        }
    };

    private void delayedBlur() {
        if (mArtDetailMode || mRenderer.isBlurred()) {
            return;
        }

        cancelDelayedBlur();
        mMainThreadHandler.postDelayed(mBlurRunnable, TEMPORARY_FOCUS_DURATION_MILLIS);
    }

    public void onEventMainThread(final ArtDetailOpenedClosedEvent e) {
        if (e.isArtDetailOpened() == mArtDetailMode) {
            return;
        }

        mArtDetailMode = e.isArtDetailOpened();
        cancelDelayedBlur();
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.setIsBlurred(!e.isArtDetailOpened(), true);
            }
        });
    }

    public void onEventMainThread(ArtDetailViewport e) {
        requestRender();
    }

    public void onEventMainThread(LockScreenVisibleChangedEvent e) {
        final boolean blur = !e.isLockScreenVisible();
        cancelDelayedBlur();
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.setIsBlurred(blur, false);
            }
        });
    }

}
