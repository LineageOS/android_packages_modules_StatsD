/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.cts.statsd.alert;

import android.cts.statsd.atom.DeviceAtomTestCase;
import com.android.tradefed.log.LogUtil.CLog;

/**
 * Statsd broadcast subscriber tests. Some may be done device-side.
 */
public class BroadcastSubscriberTests extends DeviceAtomTestCase {

    private static final String TAG = "Statsd.BroadcastSubscriberTests";

    // These tests currently require selinux policy to be disabled, so keep false!
    // TODO: Fix this.
    private static final boolean TESTS_ENABLED = false;

    public static final int SUBSCRIBER_TESTS_CONFIG_ID = 12;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (!TESTS_ENABLED) {
            CLog.w(TAG, TAG + " tests are disabled by a flag. Change flag to true to run.");
        }
    }

    @Override
    protected void tearDown() throws Exception {
        getDevice().executeShellCommand(
                String.join(" ", REMOVE_CONFIG_CMD, String.valueOf(getUid()),
                        String.valueOf(SUBSCRIBER_TESTS_CONFIG_ID)));
        super.tearDown();
    }

    /** Tests that anomaly detection generic subscribers work. */
    public void testBroadcastSubscriber() throws Exception {
        runAlertTest("testBroadcastSubscriber");
    }

    /** Tests that unsetting generic subscribers work. */
    public void testUnsetBroadcastSubscriber() throws Exception {
        runAlertTest("testUnsetBroadcastSubscriber");
    }

    public void runAlertTest(String methodName) throws Exception {
        if (!TESTS_ENABLED) return;
        CLog.d("\nPerforming device-side test of " + methodName + " for uid " + getUid());
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".SubscriberTests", methodName);
    }
}