package com.google.android.apps.muzei;

import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.service.dreams.DreamService;
import android.view.View;
import android.view.WindowManager;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent;
import com.google.android.apps.muzei.render.MuzeiBlurRenderer;
import com.google.android.apps.muzei.render.RealRenderController;
import com.google.android.apps.muzei.render.RenderController;
import com.google.android.apps.muzei.util.ShadowDipsTextView;
import com.google.android.apps.muzei.util.TypefaceUtil;

import net.nurik.roman.muzei.R;

import android.text.format.DateFormat;

import de.greenrobot.event.EventBus;

public class MuzeiDaydreamService extends DreamService implements
        RenderController.Callbacks,
        MuzeiBlurRenderer.Callbacks {

    private static final long TEMPORARY_FOCUS_DURATION_MILLIS = 3000;
    private static final long TIMER_INTERVAL = 10000;

    private View mContent;
    private GLSurfaceView mGLView = null;
    private ShadowDipsTextView mTimeTextView;
    private ShadowDipsTextView mDateTextView;
    private ShadowDipsTextView mTitleTextView;
    private ShadowDipsTextView mBylineTextView;

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

        Typeface tf = TypefaceUtil.getAndCache(this, "Alegreya-BlackItalic.ttf");
        mTitleTextView = (ShadowDipsTextView) mContent.findViewById(R.id.txTitle);
        mTitleTextView.setTypeface(tf);

        tf = TypefaceUtil.getAndCache(this, "Alegreya-Italic.ttf");
        mBylineTextView = (ShadowDipsTextView) mContent.findViewById(R.id.txByline);
        mBylineTextView.setTypeface(tf);

        mGLView.setEGLContextClientVersion(2);
        mGLView.setRenderer(mRenderer);
        mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        mMainThreadHandler.postDelayed(updateTimerThread, TIMER_INTERVAL);

        // Full picture quality
        mRenderer.setDim(0);
        mRenderer.setGrey(0);
        mRenderer.setIsBlurred(false, false);
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
        mRenderController.setVisible(true);
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
        mContent.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        mContent.setVisibility(View.GONE);
    }

    @Override
    public void queueEventOnGlThread(Runnable runnable) {
        mGLView.queueEvent(runnable);
    }

    @Override
    public void requestRender() {
        if (mGLView != null)
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
        mBylineTextView.setText("");
        if (currentArtwork != null) {
            if (!currentArtwork.getTitle().isEmpty())
                mTitleTextView.setText(currentArtwork.getTitle());
            if (!currentArtwork.getByline().isEmpty())
                mBylineTextView.setText(currentArtwork.getByline());
        }

        requestRender();
    }

    private Runnable updateTimerThread = new Runnable() {

        public void run() {
            java.util.Date curdate = new java.util.Date();
            mTimeTextView.setText(DateFormat.getTimeFormat(getApplicationContext()).format(curdate));
            mDateTextView.setText(DateFormat.getLongDateFormat(getApplicationContext()).format(curdate));

            mMainThreadHandler.postDelayed(this, TIMER_INTERVAL);
        }

    };

}
