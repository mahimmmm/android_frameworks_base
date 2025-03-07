/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Color;
import android.icu.text.NumberFormat;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.ViewController;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

/**
 * Controller for an AnimatableClockView on the keyguard. Instantiated by
 * {@link KeyguardClockSwitchController}.
 */
public class AnimatableClockController extends ViewController<AnimatableClockView> {
    private static final String TAG = "AnimatableClockCtrl";
    private static final int FORMAT_NUMBER = 1234567890;

    private final StatusBarStateController mStatusBarStateController;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final BatteryController mBatteryController;
    private final int mDozingColor = Color.WHITE;
    private int mLockScreenColor;

    private boolean mIsDozing;
    private boolean mIsCharging;
    private float mDozeAmount;
    boolean mKeyguardShowing;
    private Locale mLocale;

    private final NumberFormat mBurmeseNf = NumberFormat.getInstance(Locale.forLanguageTag("my"));
    private final String mBurmeseNumerals;
    private final float mBurmeseLineSpacing;
    private final float mDefaultLineSpacing;
    private final float mBrokenFontLineSpacing;

    public AnimatableClockController(
            AnimatableClockView view,
            StatusBarStateController statusBarStateController,
            BroadcastDispatcher broadcastDispatcher,
            BatteryController batteryController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            @Main Resources resources
    ) {
        super(view);
        mStatusBarStateController = statusBarStateController;
        mBroadcastDispatcher = broadcastDispatcher;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mBatteryController = batteryController;

        mBurmeseNumerals = mBurmeseNf.format(FORMAT_NUMBER);
        mBurmeseLineSpacing = resources.getFloat(
                R.dimen.keyguard_clock_line_spacing_scale_burmese);
        mDefaultLineSpacing = resources.getFloat(
                R.dimen.keyguard_clock_line_spacing_scale);
        mBrokenFontLineSpacing = resources.getFloat(
                R.dimen.keyguard_clock_line_spacing_scale_broken);
    }

    private void reset() {
        mView.animateDoze(mIsDozing, false);
    }

    private final BatteryController.BatteryStateChangeCallback mBatteryCallback =
            new BatteryController.BatteryStateChangeCallback() {
        @Override
        public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
            if (mKeyguardShowing && !mIsCharging && charging) {
                mView.animateCharge(mStatusBarStateController::isDozing);
            }
            mIsCharging = charging;
        }
    };

    private final BroadcastReceiver mLocaleBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateLocale();
        }
    };

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozeAmountChanged(float linear, float eased) {
                    boolean noAnimation = (mDozeAmount == 0f && linear == 1f)
                            || (mDozeAmount == 1f && linear == 0f);
                    boolean isDozing = linear > mDozeAmount;
                    mDozeAmount = linear;
                    if (mIsDozing != isDozing) {
                        mIsDozing = isDozing;
                        mView.animateDoze(mIsDozing, !noAnimation);
                    }
                }
            };

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            mKeyguardShowing = showing;
            if (!mKeyguardShowing) {
                // reset state (ie: after weight animations)
                reset();
            }
        }
    };

    @Override
    protected void onInit() {
        mIsDozing = mStatusBarStateController.isDozing();
    }

    @Override
    protected void onViewAttached() {
        updateLocale();
        mBroadcastDispatcher.registerReceiver(mLocaleBroadcastReceiver,
                new IntentFilter(Intent.ACTION_LOCALE_CHANGED));
        mDozeAmount = mStatusBarStateController.getDozeAmount();
        mIsDozing = mStatusBarStateController.isDozing() || mDozeAmount != 0;
        mBatteryController.addCallback(mBatteryCallback);
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);

        mStatusBarStateController.addCallback(mStatusBarStateListener);

        refreshTime();
        initColors();
        mView.animateDoze(mIsDozing, false);
    }

    @Override
    protected void onViewDetached() {
        mBroadcastDispatcher.unregisterReceiver(mLocaleBroadcastReceiver);
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
        mBatteryController.removeCallback(mBatteryCallback);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
    }

    /**
     * @return the number of pixels below the baseline. For fonts that support languages such as
     * Burmese, this space can be significant.
     */
    public float getBottom() {
        if (mView.getPaint() != null && mView.getPaint().getFontMetrics() != null) {
            return mView.getPaint().getFontMetrics().bottom;
        }

        return 0f;
    }

    /** Animate the clock appearance */
    public void animateAppear() {
        if (!mIsDozing) mView.animateAppearOnLockscreen();
    }

    /** Animate the clock appearance when a foldable device goes from fully-open/half-open state to
     * fully folded state and it goes to sleep (always on display screen) */
    public void animateFoldAppear() {
        mView.animateFoldAppear();
    }

    /**
     * Updates the time for the view.
     */
    public void refreshTime() {
        mView.refreshTime();
    }

    /**
     * Updates the timezone for the view.
     */
    public void onTimeZoneChanged(TimeZone timeZone) {
        mView.onTimeZoneChanged(timeZone);
    }

    /**
     * Trigger a time format update
     */
    public void refreshFormat() {
        mView.refreshFormat();
    }

    /**
     * Return locallly stored dozing state.
     */
    @VisibleForTesting
    public boolean isDozing() {
        return mIsDozing;
    }

    /**
     * Check if font is broken
     */
    public boolean isBrokenFont() {
        String font = getContext().getString(com.android.internal.R.string.config_headlineFontFamily);
        if (font.equalsIgnoreCase("nothingdot57")
             || font.equalsIgnoreCase("aclonica") 
             || font.equalsIgnoreCase("bariol")
             || font.equalsIgnoreCase("comfortaa")
             || font.equalsIgnoreCase("coolstory")
             || font.equalsIgnoreCase("jtleonor")
             || font.equalsIgnoreCase("linotte")
             || font.equalsIgnoreCase("misans")
             || font.equalsIgnoreCase("sans-serif")
             || font.equalsIgnoreCase("accuratist")
             || font.equalsIgnoreCase("inter_custom")
             || font.equalsIgnoreCase("opposans")
             || font.equalsIgnoreCase("robotocondensed")
             || font.equalsIgnoreCase("nokiapure")) {
          return true;
        } else {
          return false;
        }
    }
    
    private void updateLocale() {
        Locale currLocale = Locale.getDefault();
        boolean mIsBrokenFont = isBrokenFont();
        if (!Objects.equals(currLocale, mLocale)) {
            mLocale = currLocale;
            NumberFormat nf = NumberFormat.getInstance(mLocale);
            if (nf.format(FORMAT_NUMBER).equals(mBurmeseNumerals)) {
                mView.setLineSpacingScale(mBurmeseLineSpacing);
            } else if (mIsBrokenFont && !nf.format(FORMAT_NUMBER).equals(mBurmeseNumerals)) {
                mView.setLineSpacingScale(mBrokenFontLineSpacing);
            } else {
                mView.setLineSpacingScale(mDefaultLineSpacing);
            }
            mView.refreshFormat();
        }
    }

    private void initColors() {
        boolean isSecondaryColor = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.SECONDARY_COLOR_CLOCK, 0, UserHandle.USER_CURRENT) != 0;
        if (isSecondaryColor) {
        mLockScreenColor = Utils.getColorAttrDefaultColor(getContext(),
                com.android.systemui.R.attr.wallpaperTextColorSecondary);
        } else {
        mLockScreenColor = Utils.getColorAttrDefaultColor(getContext(),
                com.android.systemui.R.attr.wallpaperTextColorAccent);
	}
        mView.setColors(mDozingColor, mLockScreenColor);
        mView.animateDoze(mIsDozing, false);
    }

    /**
     * Dump information for debugging
     */
    public void dump(@NonNull PrintWriter pw) {
        pw.println(this);
        mView.dump(pw);
    }
}
