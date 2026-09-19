package com.autoledger.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public class CircleAvatarView extends View {
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private Bitmap avatarBitmap;
    private BitmapShader shader;
    private int ringColor;
    private float ringWidth;

    public CircleAvatarView(Context context) {
        super(context);
        init();
    }

    public CircleAvatarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ringPaint.setStyle(Paint.Style.STROKE);
    }

    public void setImageResource(int resourceId) {
        avatarBitmap = BitmapFactory.decodeResource(getResources(), resourceId);
        shader = avatarBitmap == null
                ? null
                : new BitmapShader(avatarBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        invalidate();
    }

    public void setRing(int color, float width) {
        ringColor = color;
        ringWidth = width;
        ringPaint.setColor(color);
        ringPaint.setStrokeWidth(width);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float radius = Math.min(centerX, centerY) - ringWidth / 2f;
        if (radius <= 0f) {
            return;
        }
        if (shader != null && avatarBitmap != null) {
            Bitmap bitmap = avatarBitmap;
            float scale = Math.max(
                    radius * 2f / bitmap.getWidth(),
                    radius * 2f / bitmap.getHeight()
            );
            shaderMatrix.reset();
            shaderMatrix.setScale(scale, scale);
            shaderMatrix.postTranslate(
                    centerX - bitmap.getWidth() * scale / 2f,
                    centerY - bitmap.getHeight() * scale / 2f
            );
            shader.setLocalMatrix(shaderMatrix);
            imagePaint.setShader(shader);
            canvas.drawCircle(centerX, centerY, radius, imagePaint);
        }
        if (ringWidth > 0f) {
            canvas.drawCircle(centerX, centerY, radius, ringPaint);
        }
    }
}
