/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.widget;

import static android.view.View.MeasureSpec.makeMeasureSpec;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_WIDGETS_TRAY;
import static com.android.launcher3.Utilities.ATLEAST_S;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.CancellationSignal;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.launcher3.BaseActivity;
import com.android.launcher3.CheckLongPressHelper;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.icons.FastBitmapDrawable;
import com.android.launcher3.icons.RoundDrawableWrapper;
import com.android.launcher3.model.WidgetItem;
import com.android.launcher3.widget.util.WidgetSizes;

/**
 * Represents the individual cell of the widget inside the widget tray. The preview is drawn
 * horizontally centered, and scaled down if needed.
 *
 * This view does not support padding. Since the image is scaled down to fit the view, padding will
 * further decrease the scaling factor. Drag-n-drop uses the view bounds for showing a smooth
 * transition from the view to drag view, so when adding padding support, DnD would need to
 * consider the appropriate scaling factor.
 */
public class WidgetCell extends LinearLayout implements OnLayoutChangeListener {

    private static final String TAG = "WidgetCell";
    private static final boolean DEBUG = false;

    private static final int FADE_IN_DURATION_MS = 90;

    /** Widget cell width is calculated by multiplying this factor to grid cell width. */
    private static final float WIDTH_SCALE = 3f;

    /** Widget preview width is calculated by multiplying this factor to the widget cell width. */
    private static final float PREVIEW_SCALE = 0.8f;

    /**
     * The maximum dimension that can be used as the size in
     * {@link android.view.View.MeasureSpec#makeMeasureSpec(int, int)}.
     *
     * <p>This is equal to (1 << MeasureSpec.MODE_SHIFT) - 1.
     */
    private static final int MAX_MEASURE_SPEC_DIMENSION = (1 << 30) - 1;

    /**
     * The target preview width, in pixels, of a widget or a shortcut.
     *
     * <p>The actual preview width may be smaller than or equal to this value subjected to scaling.
     */
    protected int mTargetPreviewWidth;

    /**
     * The target preview height, in pixels, of a widget or a shortcut.
     *
     * <p>The actual preview height may be smaller than or equal to this value subjected to scaling.
     */
    protected int mTargetPreviewHeight;

    protected int mPresetPreviewSize;

    private int mCellSize;

    /**
     * The scale of the preview container.
     */
    private float mPreviewContainerScale = 1f;

    private FrameLayout mWidgetImageContainer;
    private WidgetImageView mWidgetImage;
    private TextView mWidgetName;
    private TextView mWidgetDims;
    private TextView mWidgetDescription;

    protected WidgetItem mItem;

    private WidgetPreviewLoader mWidgetPreviewLoader;

    protected CancellationSignal mActiveRequest;
    private boolean mAnimatePreview = true;

    private boolean mApplyBitmapDeferred = false;
    private Drawable mDeferredDrawable;

    protected final BaseActivity mActivity;
    private final CheckLongPressHelper mLongPressHelper;
    private final float mEnforcedCornerRadius;

    private RemoteViews mRemoteViewsPreview;
    private NavigableAppWidgetHostView mAppWidgetHostViewPreview;
    private float mAppWidgetHostViewScale = 1f;
    private int mSourceContainer = CONTAINER_WIDGETS_TRAY;

    public WidgetCell(Context context) {
        this(context, null);
    }

    public WidgetCell(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WidgetCell(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mActivity = BaseActivity.fromContext(context);
        mLongPressHelper = new CheckLongPressHelper(this);
        mLongPressHelper.setLongPressTimeoutFactor(1);

        setContainerWidth();
        setWillNotDraw(false);
        setClipToPadding(false);
        setAccessibilityDelegate(mActivity.getAccessibilityDelegate());
        mEnforcedCornerRadius = RoundedCornerEnforcement.computeEnforcedRadius(context);
    }

    private void setContainerWidth() {
        mCellSize = (int) (mActivity.getDeviceProfile().allAppsIconSizePx * WIDTH_SCALE);
        mPresetPreviewSize = (int) (mCellSize * PREVIEW_SCALE);
        mTargetPreviewWidth = mTargetPreviewHeight = mPresetPreviewSize;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mWidgetImageContainer = findViewById(R.id.widget_preview_container);
        mWidgetImage = findViewById(R.id.widget_preview);
        mWidgetName = findViewById(R.id.widget_name);
        mWidgetDims = findViewById(R.id.widget_dims);
        mWidgetDescription = findViewById(R.id.widget_description);
    }

    public void setRemoteViewsPreview(RemoteViews view) {
        mRemoteViewsPreview = view;
    }

    @Nullable
    public RemoteViews getRemoteViewsPreview() {
        return mRemoteViewsPreview;
    }

    /** Returns the app widget host view scale, which is a value between [0f, 1f]. */
    public float getAppWidgetHostViewScale() {
        return mAppWidgetHostViewScale;
    }

    /**
     * Called to clear the view and free attached resources. (e.g., {@link Bitmap}
     */
    public void clear() {
        if (DEBUG) {
            Log.d(TAG, "reset called on:" + mWidgetName.getText());
        }
        mWidgetImage.animate().cancel();
        mWidgetImage.setDrawable(null);
        mWidgetImage.setVisibility(View.VISIBLE);
        mWidgetName.setText(null);
        mWidgetDims.setText(null);
        mWidgetDescription.setText(null);
        mWidgetDescription.setVisibility(GONE);
        mTargetPreviewWidth = mTargetPreviewHeight = mPresetPreviewSize;

        if (mActiveRequest != null) {
            mActiveRequest.cancel();
            mActiveRequest = null;
        }
        mRemoteViewsPreview = null;
        if (mAppWidgetHostViewPreview != null) {
            mWidgetImageContainer.removeView(mAppWidgetHostViewPreview);
        }
        mAppWidgetHostViewPreview = null;
        mAppWidgetHostViewScale = 1f;
        mItem = null;
    }

    public void setSourceContainer(int sourceContainer) {
        this.mSourceContainer = sourceContainer;
    }

    public void applyFromCellItem(WidgetItem item, WidgetPreviewLoader loader) {
        applyPreviewOnAppWidgetHostView(item);

        Context context = getContext();
        mItem = item;
        mWidgetName.setText(mItem.label);
        mWidgetName.setContentDescription(
                context.getString(R.string.widget_preview_context_description, mItem.label));
        mWidgetDims.setText(context.getString(R.string.widget_dims_format,
                mItem.spanX, mItem.spanY));
        mWidgetDims.setContentDescription(context.getString(
                R.string.widget_accessible_dims_format, mItem.spanX, mItem.spanY));
        if (ATLEAST_S && mItem.widgetInfo != null) {
            CharSequence description = mItem.widgetInfo.loadDescription(context);
            if (description != null && description.length() > 0) {
                mWidgetDescription.setText(description);
                mWidgetDescription.setVisibility(VISIBLE);
            } else {
                mWidgetDescription.setVisibility(GONE);
            }
        }

        mWidgetPreviewLoader = loader;
        if (item.activityInfo != null) {
            setTag(new PendingAddShortcutInfo(item.activityInfo));
        } else {
            setTag(new PendingAddWidgetInfo(item.widgetInfo, mSourceContainer));
        }
    }


    private void applyPreviewOnAppWidgetHostView(WidgetItem item) {
        if (mRemoteViewsPreview != null) {
            mAppWidgetHostViewPreview = createAppWidgetHostView(getContext());
            setAppWidgetHostViewPreview(mAppWidgetHostViewPreview, item.widgetInfo,
                    mRemoteViewsPreview);
            return;
        }

        if (!item.hasPreviewLayout()) return;

        Context context = getContext();
        // If the context is a Launcher activity, DragView will show mAppWidgetHostViewPreview as
        // a preview during drag & drop. And thus, we should use LauncherAppWidgetHostView, which
        // supports applying local color extraction during drag & drop.
        mAppWidgetHostViewPreview = isLauncherContext(context)
                ? new LauncherAppWidgetHostView(context)
                : createAppWidgetHostView(context);
        LauncherAppWidgetProviderInfo launcherAppWidgetProviderInfo =
                LauncherAppWidgetProviderInfo.fromProviderInfo(context, item.widgetInfo.clone());
        // A hack to force the initial layout to be the preview layout since there is no API for
        // rendering a preview layout for work profile apps yet. For non-work profile layout, a
        // proper solution is to use RemoteViews(PackageName, LayoutId).
        launcherAppWidgetProviderInfo.initialLayout = item.widgetInfo.previewLayout;
        setAppWidgetHostViewPreview(mAppWidgetHostViewPreview,
                launcherAppWidgetProviderInfo, /* remoteViews= */ null);
    }

    private void setAppWidgetHostViewPreview(
            NavigableAppWidgetHostView appWidgetHostViewPreview,
            LauncherAppWidgetProviderInfo providerInfo,
            @Nullable RemoteViews remoteViews) {
        appWidgetHostViewPreview.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        appWidgetHostViewPreview.setAppWidget(/* appWidgetId= */ -1, providerInfo);
        appWidgetHostViewPreview.updateAppWidget(remoteViews);
    }

    public WidgetImageView getWidgetView() {
        return mWidgetImage;
    }

    @Nullable
    public NavigableAppWidgetHostView getAppWidgetHostViewPreview() {
        return mAppWidgetHostViewPreview;
    }

    /**
     * Sets if applying bitmap preview should be deferred. The UI will still load the bitmap, but
     * will not cause invalidate, so that when deferring is disabled later, all the bitmaps are
     * ready.
     * This prevents invalidates while the animation is running.
     */
    public void setApplyBitmapDeferred(boolean isDeferred) {
        if (mApplyBitmapDeferred != isDeferred) {
            mApplyBitmapDeferred = isDeferred;
            if (!mApplyBitmapDeferred && mDeferredDrawable != null) {
                applyPreview(mDeferredDrawable);
                mDeferredDrawable = null;
            }
        }
    }

    public void setAnimatePreview(boolean shouldAnimate) {
        mAnimatePreview = shouldAnimate;
    }

    public void applyPreview(Bitmap bitmap) {
        FastBitmapDrawable drawable = new FastBitmapDrawable(bitmap);
        applyPreview(new RoundDrawableWrapper(drawable, mEnforcedCornerRadius));
    }

    private void applyPreview(Drawable drawable) {
        if (mApplyBitmapDeferred) {
            mDeferredDrawable = drawable;
            return;
        }
        if (drawable != null) {
            // Scale down the preview size if it's wider than the cell.
            float scale = 1f;
            if (mTargetPreviewWidth > 0) {
                float maxWidth = mTargetPreviewWidth;
                float previewWidth = drawable.getIntrinsicWidth() * mPreviewContainerScale;
                scale = Math.min(maxWidth / previewWidth, 1);
            }
            setContainerSize(
                    Math.round(drawable.getIntrinsicWidth() * scale * mPreviewContainerScale),
                    Math.round(drawable.getIntrinsicHeight() * scale * mPreviewContainerScale));
            mWidgetImage.setDrawable(drawable);
            mWidgetImage.setVisibility(View.VISIBLE);
            if (mAppWidgetHostViewPreview != null) {
                removeView(mAppWidgetHostViewPreview);
                mAppWidgetHostViewPreview = null;
            }
        }
        if (mAnimatePreview) {
            mWidgetImageContainer.setAlpha(0f);
            ViewPropertyAnimator anim = mWidgetImageContainer.animate();
            anim.alpha(1.0f).setDuration(FADE_IN_DURATION_MS);
        } else {
            mWidgetImageContainer.setAlpha(1f);
        }
    }

    private void setContainerSize(int width, int height) {
        LayoutParams layoutParams = (LayoutParams) mWidgetImageContainer.getLayoutParams();
        layoutParams.width = width;
        layoutParams.height = height;
        mWidgetImageContainer.setLayoutParams(layoutParams);
    }

    public void ensurePreview() {
        if (mAppWidgetHostViewPreview != null) {
            int containerWidth = (int) (mTargetPreviewWidth * mPreviewContainerScale);
            int containerHeight = (int) (mTargetPreviewHeight * mPreviewContainerScale);
            setContainerSize(containerWidth, containerHeight);
            if (mAppWidgetHostViewPreview.getChildCount() == 1) {
                View widgetContent = mAppWidgetHostViewPreview.getChildAt(0);
                ViewGroup.LayoutParams layoutParams = widgetContent.getLayoutParams();
                // We only scale preview if both the width & height of the outermost view group are
                // not set to MATCH_PARENT.
                boolean shouldScale =
                        layoutParams.width != MATCH_PARENT && layoutParams.height != MATCH_PARENT;
                if (shouldScale) {
                    setNoClip(mWidgetImageContainer);
                    setNoClip(mAppWidgetHostViewPreview);
                    mAppWidgetHostViewScale = measureAndComputeWidgetPreviewScale();
                    mAppWidgetHostViewPreview.setScaleToFit(mAppWidgetHostViewScale);
                }
            }
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    containerWidth, containerHeight, Gravity.FILL);
            mAppWidgetHostViewPreview.setLayoutParams(params);
            mWidgetImageContainer.addView(mAppWidgetHostViewPreview, /* index= */ 0);
            mWidgetImage.setVisibility(View.GONE);
            applyPreview((Drawable) null);
            return;
        }
        if (mActiveRequest != null) {
            return;
        }
        mActiveRequest = mWidgetPreviewLoader.loadPreview(
                BaseActivity.fromContext(getContext()), mItem,
                new Size(mTargetPreviewWidth, mTargetPreviewHeight),
                this::applyPreview);
    }

    /** Sets the widget preview image size in number of cells. */
    public Size setPreviewSize(WidgetItem widgetItem) {
        return setPreviewSize(widgetItem, 1f);
    }

    /** Sets the widget preview image size, in number of cells, and preview scale. */
    public Size setPreviewSize(WidgetItem widgetItem, float previewScale) {
        DeviceProfile deviceProfile = mActivity.getDeviceProfile();
        Size widgetSize = WidgetSizes.getWidgetItemSizePx(getContext(), deviceProfile, widgetItem);
        mTargetPreviewWidth = widgetSize.getWidth();
        mTargetPreviewHeight = widgetSize.getHeight();
        mPreviewContainerScale = previewScale;
        return widgetSize;
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        removeOnLayoutChangeListener(this);
        ensurePreview();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        super.onTouchEvent(ev);
        mLongPressHelper.onTouchEvent(ev);
        return true;
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        mLongPressHelper.cancelLongPress();
    }

    /**
     * Helper method to get the string info of the tag.
     */
    private String getTagToString() {
        if (getTag() instanceof PendingAddWidgetInfo ||
                getTag() instanceof PendingAddShortcutInfo) {
            return getTag().toString();
        }
        return "";
    }

    private static NavigableAppWidgetHostView createAppWidgetHostView(Context context) {
        return new NavigableAppWidgetHostView(context) {
            @Override
            protected boolean shouldAllowDirectClick() {
                return false;
            }
        };
    }

    private static boolean isLauncherContext(Context context) {
        try {
            Launcher.getLauncher(context);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return WidgetCell.class.getName();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }

    private static void setNoClip(ViewGroup view) {
        view.setClipChildren(false);
        view.setClipToPadding(false);
    }

    private float measureAndComputeWidgetPreviewScale() {
        if (mAppWidgetHostViewPreview.getChildCount() != 1) {
            return 1f;
        }

        // Measure the largest possible width & height that the app widget wants to display.
        mAppWidgetHostViewPreview.measure(
                makeMeasureSpec(MAX_MEASURE_SPEC_DIMENSION, MeasureSpec.UNSPECIFIED),
                makeMeasureSpec(MAX_MEASURE_SPEC_DIMENSION, MeasureSpec.UNSPECIFIED));
        if (mRemoteViewsPreview != null) {
            // If RemoteViews contains multiple sizes, the best fit sized RemoteViews will be
            // selected in onLayout. To work out the right measurement, let's layout and then
            // measure again.
            mAppWidgetHostViewPreview.layout(
                    /* left= */ 0,
                    /* top= */ 0,
                    /* right= */ mTargetPreviewWidth,
                    /* bottom= */ mTargetPreviewHeight);
            mAppWidgetHostViewPreview.measure(
                    makeMeasureSpec(mTargetPreviewWidth, MeasureSpec.UNSPECIFIED),
                    makeMeasureSpec(mTargetPreviewHeight, MeasureSpec.UNSPECIFIED));

        }
        View widgetContent = mAppWidgetHostViewPreview.getChildAt(0);
        int appWidgetContentWidth = widgetContent.getMeasuredWidth();
        int appWidgetContentHeight = widgetContent.getMeasuredHeight();
        if (appWidgetContentWidth == 0 || appWidgetContentHeight == 0) {
            return 1f;
        }

        // If the width / height of the widget content is set to wrap content, overrides the width /
        // height with the measured dimension. This avoids incorrect measurement after scaling.
        FrameLayout.LayoutParams layoutParam =
                (FrameLayout.LayoutParams) widgetContent.getLayoutParams();
        if (layoutParam.width == WRAP_CONTENT) {
            layoutParam.width = widgetContent.getMeasuredWidth();
        }
        if (layoutParam.height == WRAP_CONTENT) {
            layoutParam.height = widgetContent.getMeasuredHeight();
        }
        widgetContent.setLayoutParams(layoutParam);

        int horizontalPadding = mAppWidgetHostViewPreview.getPaddingStart()
                + mAppWidgetHostViewPreview.getPaddingEnd();
        int verticalPadding = mAppWidgetHostViewPreview.getPaddingTop()
                + mAppWidgetHostViewPreview.getPaddingBottom();
        return Math.min(
                (mTargetPreviewWidth - horizontalPadding) * mPreviewContainerScale
                        / appWidgetContentWidth,
                (mTargetPreviewHeight - verticalPadding) * mPreviewContainerScale
                        / appWidgetContentHeight);
    }
}
