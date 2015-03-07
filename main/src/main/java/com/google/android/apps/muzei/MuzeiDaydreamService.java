package com.google.android.apps.muzei;

import android.app.Service;
import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.IBinder;
import android.service.dreams.DreamService;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.event.LockScreenVisibleChangedEvent;
import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent;
import com.google.android.apps.muzei.render.MuzeiBlurRenderer;
import com.google.android.apps.muzei.render.RealRenderController;
import com.google.android.apps.muzei.render.RenderController;
import com.google.android.apps.muzei.util.ShadowDipsTextView;

import net.nurik.roman.muzei.R;

import android.text.format.DateFormat;
import java.util.Date;

import de.greenrobot.event.EventBus;

public class MuzeiDaydreamService extends DreamService implements
        RenderController.Callbacks,
        MuzeiBlurRenderer.Callbacks {

    private static final long TEMPORARY_FOCUS_DURATION_MILLIS = 3000;
    private static final long TIMER_INTERVAL = 10000;

    private View mContent;
    private GLSurfaceView mGLView;
    private ShadowDipsTextView mTimeTextView;
    private ShadowDipsTextView mDateTextView;
    private ShadowDipsTextView mTitleTextView;
    private ShadowDipsTextView mAuthorTextView;

    private RenderController mRenderController;
    private MuzeiBlurRenderer mRenderer;
    private boolean mArtDetailMode = false;
    private Handler mMainThreadHandler = new Handler();

    public MuzeiDaydreamService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mRenderer = new MuzeiBlurRenderer(MuzeiDaydreamService.this, this);
        mRenderer.setIsPreview(false);
        mRenderController = new RealRenderController(MuzeiDaydreamService.this,
                mRenderer, this);

        EventBus.getDefault().postSticky(new WallpaperActiveStateChangedEvent(true));
        EventBus.getDefault().registerSticky(this);

        mContent = View.inflate(getApplicationContext(), R.layout.daydream_view, null);
        mGLView = (GLSurfaceView) mContent.findViewById(R.id.textureView);
        mTimeTextView = (ShadowDipsTextView) mContent.findViewById(R.id.txTime);
        mDateTextView = (ShadowDipsTextView) mContent.findViewById(R.id.txDate);
        mTitleTextView = (ShadowDipsTextView) mContent.findViewById(R.id.txTitle);
        mAuthorTextView = (ShadowDipsTextView) mContent.findViewById(R.id.txAuthor);

        mGLView.setEGLContextClientVersion(2);
        mGLView.setRenderer(mRenderer);
        mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        mMainThreadHandler.postDelayed(updateTimerThread, TIMER_INTERVAL);
    }

    @Override
    public void onDestroy() {
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
        super.onDestroy();
    }
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        setFullscreen(true);
        setContentView(mContent);

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
        Update();
    }

    private void Update() {
        java.util.Date curdate = new java.util.Date();
        mTimeTextView.setText(DateFormat.getTimeFormat(getApplicationContext()).format(curdate));
        mDateTextView.setText(DateFormat.getLongDateFormat(getApplicationContext()).format(curdate));

        SourceManager sm = SourceManager.getInstance(getApplicationContext());
        SourceState selectedSourceState = sm.getSelectedSourceState();
        Artwork currentArtwork = selectedSourceState != null
                ? selectedSourceState.getCurrentArtwork() : null;

        mTitleTextView.setText("");
        mAuthorTextView.setText("");
        if (currentArtwork != null) {
            if (!currentArtwork.getTitle().isEmpty())
                mTitleTextView.setText(currentArtwork.getTitle());
            if (!currentArtwork.getByline().isEmpty())
                mAuthorTextView.setText(currentArtwork.getByline());
        }

        requestRender();
    }

    private Runnable updateTimerThread = new Runnable() {

        public void run() {
            EventBus.getDefault().post(ArtDetailViewport.getInstance());
            mMainThreadHandler.postDelayed(this, TIMER_INTERVAL);
        }

    };

}
