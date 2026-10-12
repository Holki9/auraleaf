/*
   Copyright 2012 Harri Smatt

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.holki.auraleaf.curl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;

/**
 * Storage class for page textures, blend colors and possibly some other values
 * in the future.
 * 
 * @author harism
 */
public class CurlPage {

	public static final int SIDE_BACK = 2;
	public static final int SIDE_BOTH = 3;
	public static final int SIDE_FRONT = 1;

	private int mColorBack;
	private int mColorFront;
	private Bitmap mTextureBack;
	private Bitmap mTextureFront;
	private boolean mTexturesChanged;

	/**
	 * Default constructor.
	 */
	public CurlPage() {
		reset();
	}

	/**
	 * Getter for color.
	 */
	public int getColor(int side) {
		switch (side) {
		case SIDE_FRONT:
			return mColorFront;
		default:
			return mColorBack;
		}
	}

	/**
	 * Calculates the next highest power of two for a given integer.
	 */
	private int getNextHighestPO2(int n) {
		n -= 1;
		n = n | (n >> 1);
		n = n | (n >> 2);
		n = n | (n >> 4);
		n = n | (n >> 8);
		n = n | (n >> 16);
		n = n | (n >> 32);
		return n + 1;
	}

	/**
	 * Generates nearest power of two sized Bitmap for give Bitmap. Returns this
	 * new Bitmap using default return statement + original texture coordinates
	 * are stored into RectF.
	 */
	private Bitmap getTexture(Bitmap bitmap, RectF textureRect) {
		if (bitmap == null || bitmap.isRecycled()) {
			Bitmap dummy = Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565);
			dummy.eraseColor(Color.WHITE);
			textureRect.set(0f, 0f, 1f, 1f);
			return dummy;
		}

		int w = bitmap.getWidth();
		int h = bitmap.getHeight();
		int newW = Math.max(2, getNextHighestPO2(w));
		int newH = Math.max(2, getNextHighestPO2(h));

		Bitmap.Config config = bitmap.getConfig() != null ? bitmap.getConfig() : Bitmap.Config.RGB_565;
		Bitmap bitmapTex = Bitmap.createBitmap(newW, newH, config);
		Canvas c = new Canvas(bitmapTex);
		c.drawColor(Color.WHITE);
		c.drawBitmap(bitmap, 0, 0, null);

		float texX = (float) w / newW;
		float texY = (float) h / newH;
		textureRect.set(0f, 0f, texX, texY);

		return bitmapTex;
	}

	public Bitmap getTexture(RectF textureRect, int side) {
		switch (side) {
		case SIDE_FRONT:
			return getTexture(mTextureFront, textureRect);
		default:
			return getTexture(mTextureBack, textureRect);
		}
	}

	public boolean getTexturesChanged() {
		return mTexturesChanged;
	}

	public boolean hasBackTexture() {
		return mTextureBack != null && !mTextureBack.equals(mTextureFront);
	}

	public void recycle() {
		mTextureFront = null;
		mTextureBack = null;
		mTexturesChanged = false;
	}

	public void reset() {
		mColorBack = Color.WHITE;
		mColorFront = Color.WHITE;
		recycle();
	}

	public void setColor(int color, int side) {
		switch (side) {
		case SIDE_FRONT:
			mColorFront = color;
			break;
		case SIDE_BACK:
			mColorBack = color;
			break;
		default:
			mColorFront = mColorBack = color;
			break;
		}
	}

	public void setTexture(Bitmap texture, int side) {
		switch (side) {
		case SIDE_FRONT:
			mTextureFront = texture;
			break;
		case SIDE_BACK:
			mTextureBack = texture;
			break;
		case SIDE_BOTH:
			mTextureFront = mTextureBack = texture;
			break;
		}
		mTexturesChanged = true;
	}

}
