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

package com.android.server.wifi;

import static com.android.server.wifi.ActiveModeManager.ROLE_CLIENT_PRIMARY;
import static com.android.server.wifi.ActiveModeManager.ROLE_CLIENT_SECONDARY_TRANSIENT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Manages Make-Before-Break connection switching.
 */
public class MakeBeforeBreakManager {
    private static final String TAG = "WifiMbbManager";

    private final ActiveModeWarden mActiveModeWarden;
    private final FrameworkFacade mFrameworkFacade;
    private final Context mContext;
    private final ClientModeImplMonitor mCmiMonitor;
    private final ClientModeManagerBroadcastQueue mBroadcastQueue;

    private boolean mVerboseLoggingEnabled = false;

    private static class MakeBeforeBreakInfo {
        @NonNull
        public final ConcreteClientModeManager oldPrimary;
        @NonNull
        public final ConcreteClientModeManager newPrimary;

        MakeBeforeBreakInfo(
                @NonNull ConcreteClientModeManager oldPrimary,
                @NonNull ConcreteClientModeManager newPrimary) {
            this.oldPrimary = oldPrimary;
            this.newPrimary = newPrimary;
        }

        @Override
        public String toString() {
            return "MakeBeforeBreakInfo{"
                    + "oldPrimary=" + oldPrimary
                    + ", newPrimary=" + newPrimary
                    + '}';
        }
    }

    @Nullable
    private MakeBeforeBreakInfo mMakeBeforeBreakInfo = null;

    public MakeBeforeBreakManager(
            @NonNull ActiveModeWarden activeModeWarden,
            @NonNull FrameworkFacade frameworkFacade,
            @NonNull Context context,
            @NonNull ClientModeImplMonitor cmiMonitor,
            @NonNull ClientModeManagerBroadcastQueue broadcastQueue) {
        mActiveModeWarden = activeModeWarden;
        mFrameworkFacade = frameworkFacade;
        mContext = context;
        mCmiMonitor = cmiMonitor;
        mBroadcastQueue = broadcastQueue;

        mActiveModeWarden.registerModeChangeCallback(new ModeChangeCallback());
        mCmiMonitor.registerListener(new ClientModeImplListener() {
            @Override
            public void onL3Validated(@NonNull ConcreteClientModeManager clientModeManager) {
                MakeBeforeBreakManager.this.onL3Validated(clientModeManager);
            }
        });
    }

    public void setVerboseLoggingEnabled(boolean enabled) {
        mVerboseLoggingEnabled = enabled;
    }

    private class ModeChangeCallback implements ActiveModeWarden.ModeChangeCallback {
        @Override
        public void onActiveModeManagerAdded(@NonNull ActiveModeManager activeModeManager) {
            if (!mActiveModeWarden.isMakeBeforeBreakEnabled()) {
                return;
            }
            if (!(activeModeManager instanceof ConcreteClientModeManager)) {
                return;
            }
            // just in case
            recoverPrimary();
        }

        @Override
        public void onActiveModeManagerRemoved(@NonNull ActiveModeManager activeModeManager) {
            if (!mActiveModeWarden.isMakeBeforeBreakEnabled()) {
                return;
            }
            if (!(activeModeManager instanceof ConcreteClientModeManager)) {
                return;
            }
            // if either the old or new primary stopped during MBB, abort the MBB attempt
            ConcreteClientModeManager clientModeManager =
                    (ConcreteClientModeManager) activeModeManager;
            if (mMakeBeforeBreakInfo != null) {
                boolean oldPrimaryStopped = clientModeManager == mMakeBeforeBreakInfo.oldPrimary;
                boolean newPrimaryStopped = clientModeManager == mMakeBeforeBreakInfo.newPrimary;
                if (oldPrimaryStopped || newPrimaryStopped) {
                    Log.i(TAG, "MBB CMM stopped, aborting:"
                            + " oldPrimary=" + mMakeBeforeBreakInfo.oldPrimary
                            + " stopped=" + oldPrimaryStopped
                            + " newPrimary=" + mMakeBeforeBreakInfo.newPrimary
                            + " stopped=" + newPrimaryStopped);
                    mMakeBeforeBreakInfo = null;
                }
            }
            recoverPrimary();
        }

        @Override
        public void onActiveModeManagerRoleChanged(@NonNull ActiveModeManager activeModeManager) {
            if (!mActiveModeWarden.isMakeBeforeBreakEnabled()) {
                return;
            }
            if (!(activeModeManager instanceof ConcreteClientModeManager)) {
                return;
            }
            ConcreteClientModeManager clientModeManager =
                    (ConcreteClientModeManager) activeModeManager;
            recoverPrimary();
            maybeContinueMakeBeforeBreak(clientModeManager);
        }
    }

    /**
     * Failsafe: if there is no primary CMM but there exists exactly one CMM in
     * {@link ActiveModeManager#ROLE_CLIENT_SECONDARY_TRANSIENT}, or multiple and MBB is not
     * in progress (to avoid interfering with MBB), make it primary.
     */
    private void recoverPrimary() {
        // already have a primary, do nothing
        if (mActiveModeWarden.getPrimaryClientModeManagerNullable() != null) {
            return;
        }
        List<ConcreteClientModeManager> secondaryTransientCmms =
                mActiveModeWarden.getClientModeManagersInRoles(ROLE_CLIENT_SECONDARY_TRANSIENT);
        // exactly 1 secondary transient, or > 1 secondary transient and MBB is not in progress
        if (secondaryTransientCmms.size() == 1
                || (mMakeBeforeBreakInfo == null && secondaryTransientCmms.size() > 1)) {
            ConcreteClientModeManager manager = secondaryTransientCmms.get(0);
            manager.setRole(ROLE_CLIENT_PRIMARY, mFrameworkFacade.getSettingsWorkSource(mContext));
            Log.i(TAG, "recoveryPrimary kicking in, making " + manager + " primary and stopping"
                    + " all other SECONDARY_TRANSIENT ClientModeManagers");
            // tear down the extra secondary transient CMMs (if they exist)
            for (int i = 1; i < secondaryTransientCmms.size(); i++) {
                secondaryTransientCmms.get(i).stop();
            }
        }
    }

    /**
     * A ClientModeImpl instance has been L3 validated. This will begin the Make-Before-Break
     * transition to make this the new primary network.
     *
     * Change the previous primary ClientModeManager to role
     * {@link ActiveModeManager#ROLE_CLIENT_SECONDARY_TRANSIENT} and change the new
     * primary to role {@link ActiveModeManager#ROLE_CLIENT_PRIMARY}.
     *
     * @param newPrimary the corresponding ConcreteClientModeManager instance for the ClientModeImpl
     *                   that has been L3 validated.
     */
    private void onL3Validated(@NonNull ConcreteClientModeManager newPrimary) {
        if (!mActiveModeWarden.isMakeBeforeBreakEnabled()) {
            return;
        }
        if (newPrimary.getRole() != ROLE_CLIENT_SECONDARY_TRANSIENT) {
            return;
        }

        ConcreteClientModeManager currentPrimary =
                mActiveModeWarden.getPrimaryClientModeManagerNullable();

        if (currentPrimary == null) {
            Log.e(TAG, "changePrimaryClientModeManager(): current primary CMM is null!");
            newPrimary.setRole(
                    ROLE_CLIENT_PRIMARY, mFrameworkFacade.getSettingsWorkSource(mContext));
            return;
        }

        Log.i(TAG, "Starting MBB switch primary from " + currentPrimary + " to " + newPrimary
                + " by setting current primary's role to ROLE_CLIENT_SECONDARY_TRANSIENT");

        currentPrimary.setRole(
                ROLE_CLIENT_SECONDARY_TRANSIENT, ActiveModeWarden.INTERNAL_REQUESTOR_WS);
        // immediately send fake disconnection broadcasts upon changing primary CMM's role to
        // SECONDARY_TRANSIENT, because as soon as the CMM becomes SECONDARY_TRANSIENT, its
        // broadcasts will never be sent out again (BroadcastQueue only sends broadcasts for the
        // current primary CMM). This is to preserve the legacy single STA behavior.
        mBroadcastQueue.fakeDisconnectionBroadcasts();
        mMakeBeforeBreakInfo = new MakeBeforeBreakInfo(currentPrimary, newPrimary);
    }

    private void maybeContinueMakeBeforeBreak(
            @NonNull ConcreteClientModeManager roleChangedClientModeManager) {
        // not in the middle of MBB
        if (mMakeBeforeBreakInfo == null) {
            return;
        }
        // not the CMM we're looking for, keep monitoring
        if (roleChangedClientModeManager != mMakeBeforeBreakInfo.oldPrimary) {
            return;
        }
        try {
            // if old primary didn't transition to secondary transient, abort the MBB attempt
            if (mMakeBeforeBreakInfo.oldPrimary.getRole() != ROLE_CLIENT_SECONDARY_TRANSIENT) {
                Log.i(TAG, "old primary is no longer secondary transient, aborting MBB: "
                        + mMakeBeforeBreakInfo.oldPrimary);
                return;
            }

            // if somehow the next primary is no longer secondary transient, abort the MBB attempt
            if (mMakeBeforeBreakInfo.newPrimary.getRole() != ROLE_CLIENT_SECONDARY_TRANSIENT) {
                Log.i(TAG, "new primary is no longer secondary transient, abort MBB: "
                        + mMakeBeforeBreakInfo.newPrimary);
                return;
            }

            Log.i(TAG, "Continue MBB switch primary from " + mMakeBeforeBreakInfo.oldPrimary
                    + " to " + mMakeBeforeBreakInfo.newPrimary
                    + " by setting new Primary's role to ROLE_CLIENT_PRIMARY and reducing network"
                    + " score");

            // otherwise, actually set the new primary's role to primary.
            mMakeBeforeBreakInfo.newPrimary.setRole(
                    ROLE_CLIENT_PRIMARY, mFrameworkFacade.getSettingsWorkSource(mContext));

            // linger old primary
            // TODO(b/160346062): maybe do this after the new primary was fully transitioned to
            //  ROLE_CLIENT_PRIMARY (since setRole() is asynchronous)
            mMakeBeforeBreakInfo.oldPrimary.setShouldReduceNetworkScore(true);
        } finally {
            // end the MBB attempt
            mMakeBeforeBreakInfo = null;
            mActiveModeWarden.removeNetworkRequestForMbb();
        }
    }

    /** Dump fields for debugging. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of MakeBeforeBreakManager");
        pw.println("mMakeBeforeBreakInfo=" + mMakeBeforeBreakInfo);
    }
}
