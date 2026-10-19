package com.sony.playmemories.mobile.redesign;

import android.content.Context;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.TextView;

/** Fits one line against its measured cell, including Android font scaling. */
public final class V3FitText extends TextView {
    private final float minSp, maxSp;
    public V3FitText(Context context, float minSp, float maxSp) {
        super(context); this.minSp = minSp; this.maxSp = maxSp;
        setMaxLines(1); setHorizontallyScrolling(false); setEllipsize(TextUtils.TruncateAt.END);
        setIncludeFontPadding(false); setTextSize(TypedValue.COMPLEX_UNIT_SP, maxSp);
    }
    @Override protected void onMeasure(int width, int height) {
        int available = MeasureSpec.getSize(width) - getCompoundPaddingLeft() - getCompoundPaddingRight();
        int availableHeight = MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
                : MeasureSpec.getSize(height) - getCompoundPaddingTop() - getCompoundPaddingBottom();
        if (MeasureSpec.getMode(width) != MeasureSpec.UNSPECIFIED && available > 0) {
            Paint measuring = new Paint(getPaint());
            float scale = getResources().getDisplayMetrics().scaledDensity;
            float chosen = maxSp;
            for (; chosen > minSp; chosen -= .5f) {
                measuring.setTextSize(chosen * scale);
                Paint.FontMetrics metrics = measuring.getFontMetrics();
                if (measuring.measureText(getText().toString()) <= available
                        && Math.ceil(metrics.descent - metrics.ascent) <= availableHeight) break;
            }
            float pixels = Math.max(minSp, chosen) * scale;
            measuring.setTextSize(pixels);
            float textWidth = measuring.measureText(getText().toString());
            if (textWidth > available) pixels *= available / textWidth;
            measuring.setTextSize(pixels);
            Paint.FontMetrics metrics = measuring.getFontMetrics();
            float lineHeight = (float) Math.ceil(metrics.descent - metrics.ascent);
            if (availableHeight > 0 && lineHeight > availableHeight) pixels *= availableHeight / lineHeight;
            if (Math.abs(getTextSize() - pixels) > .1f) setTextSize(TypedValue.COMPLEX_UNIT_PX, pixels);
        }
        super.onMeasure(width, height);
    }
}
