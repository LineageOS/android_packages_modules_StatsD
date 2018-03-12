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
package com.android.server.cts.device.statsd;

import android.app.PendingIntent;
import android.app.StatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.StatsDimensionsValue;
import android.support.test.InstrumentationRegistry;
import android.util.Log;
import android.util.StatsLog;
import com.android.internal.os.StatsdConfigProto.Alert;
import com.android.internal.os.StatsdConfigProto.AtomMatcher;
import com.android.internal.os.StatsdConfigProto.BroadcastSubscriberDetails;
import com.android.internal.os.StatsdConfigProto.CountMetric;
import com.android.internal.os.StatsdConfigProto.FieldMatcher;
import com.android.internal.os.StatsdConfigProto.FieldValueMatcher;
import com.android.internal.os.StatsdConfigProto.SimpleAtomMatcher;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.internal.os.StatsdConfigProto.Subscription;
import com.android.internal.os.StatsdConfigProto.TimeUnit;
import com.android.os.AtomsProto.AppBreadcrumbReported;
import com.android.os.AtomsProto.Atom;

import java.util.concurrent.CountDownLatch;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SubscriberTests {
    public static final String TAG = "statsd.SubscriberTests";

    private Context mContext;
    private StatsManager mStatsManager;

    private PendingIntent mPendingIntent1;
    private PendingIntent mPendingIntent2;
    private static CountDownLatch sLatch1; // static since used by receiver
    private static CountDownLatch sLatch2; // static since used by receiver
    private static final int UID = android.os.Process.myUid();  // static since used by receiver

    public static final int SLEEP_TIME_AFTER_SET_SUBSCRIBER_MS = 2_000;

    public static final int CONFIG_ID = 12;
    public static final int ATOM_MATCHER_ID = 1;
    public static final int METRIC_ID = 1;
    public static final int ALERT_ID = 1;
    public static final int SUBSCRIPTION_ID_1 = 1;
    public static final int SUBSCRIPTION_ID_2 = 2;
    public static final int SUBSCRIBER_ID_1 = 1;
    public static final int SUBSCRIBER_ID_2 = 2;
    public static final String COOKIE_1_A = "COOKIE_1_A";
    public static final String COOKIE_1_B = "COOKIE_1_B";

    private static final StatsdConfig CONFIG = StatsdConfig.newBuilder().setId(CONFIG_ID)
            .addAtomMatcher(AtomMatcher.newBuilder()
                    .setId(ATOM_MATCHER_ID)
                    .setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder()
                            .setAtomId(Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER)
                            // Event only when the uid is this app's uid.
                            .addFieldValueMatcher(FieldValueMatcher.newBuilder()
                                    .setField(AppBreadcrumbReported.UID_FIELD_NUMBER)
                                    .setEqInt(UID)
                            )
                    )
            )
            .addCountMetric(CountMetric.newBuilder()
                    .setId(METRIC_ID)
                    .setWhat(ATOM_MATCHER_ID)
                    .setBucket(TimeUnit.CTS)
                    // Slice by uid (since that's the typical case)
                    .setDimensionsInWhat(FieldMatcher.newBuilder()
                            .setField(Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER)
                            .addChild(FieldMatcher.newBuilder().setField(
                                    AppBreadcrumbReported.UID_FIELD_NUMBER))
                    )
            )
            .addAlert(Alert.newBuilder()
                    .setId(ALERT_ID)
                    .setMetricId(METRIC_ID)
                    .setNumBuckets(4)
                    .setRefractoryPeriodSecs(0)
                    .setTriggerIfSumGt(0) // even a single event triggers it
            )
            .addSubscription(Subscription.newBuilder()
                    .setId(SUBSCRIPTION_ID_1)
                    .setRuleType(Subscription.RuleType.ALERT)
                    .setRuleId(ALERT_ID)
                    .setBroadcastSubscriberDetails(BroadcastSubscriberDetails.newBuilder()
                            .setSubscriberId(SUBSCRIBER_ID_1)
                            .addCookie(COOKIE_1_A)
                            .addCookie(COOKIE_1_B)
                    )
            )
            .addSubscription(Subscription.newBuilder()
                    .setId(SUBSCRIPTION_ID_2)
                    .setRuleType(Subscription.RuleType.ALERT)
                    .setRuleId(ALERT_ID)
                    .setBroadcastSubscriberDetails(BroadcastSubscriberDetails.newBuilder()
                            .setSubscriberId(SUBSCRIBER_ID_2))
            )
            .build();


    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mStatsManager = (StatsManager) mContext.getSystemService("stats");
        mPendingIntent1 = PendingIntent.getBroadcast(mContext, 1,
                new Intent(mContext, BroadcastSubscriberReceiver1.class), 0);
        mPendingIntent2 = PendingIntent.getBroadcast(mContext, 2,
                new Intent(mContext, BroadcastSubscriberReceiver2.class), 0);
    }

    /** Tests that setBroadcastSubscriber() works as intended. */
    @Test
    public void testBroadcastSubscriber() {
        Log.d(TAG, "Running testBroadcastSubscriber under UID=" + UID);
        try {
            logPendingIntent(mPendingIntent1);
            logPendingIntent(mPendingIntent2);
            sLatch1 = new CountDownLatch(1);
            sLatch2 = new CountDownLatch(1);
            mStatsManager.removeConfiguration(CONFIG_ID);
            mStatsManager.addConfiguration(CONFIG_ID, CONFIG.toByteArray());

            mStatsManager.setBroadcastSubscriber(CONFIG_ID, SUBSCRIBER_ID_1, mPendingIntent1);
            mStatsManager.setBroadcastSubscriber(CONFIG_ID, SUBSCRIBER_ID_2, mPendingIntent2);
            AtomTests.sleep(SLEEP_TIME_AFTER_SET_SUBSCRIBER_MS);
            StatsLog.write(StatsLog.APP_BREADCRUMB_REPORTED, UID, /* label */ 1,
                    StatsLog.APP_BREADCRUMB_REPORTED__STATE__START);

            Assert.assertTrue(waitForLatch(sLatch1, 10_000));
            Assert.assertTrue(waitForLatch(sLatch2, 10_000));
        } finally {
            mStatsManager.removeConfiguration(CONFIG_ID);
        }
    }

    /** Tests that setBroadcastSubscriber(,, null) works as intended. */
    @Test
    public void testUnsetBroadcastSubscriber() {
        Log.d(TAG, "Running testUnsetBroadcastSubscriber under UID=" + UID);
        try {
            logPendingIntent(mPendingIntent1);
            logPendingIntent(mPendingIntent2);
            sLatch1 = new CountDownLatch(2);
            sLatch2 = new CountDownLatch(2);
            mStatsManager.removeConfiguration(CONFIG_ID);
            mStatsManager.addConfiguration(CONFIG_ID, CONFIG.toByteArray());

            mStatsManager.setBroadcastSubscriber(CONFIG_ID, SUBSCRIBER_ID_1, mPendingIntent1);
            mStatsManager.setBroadcastSubscriber(CONFIG_ID, SUBSCRIBER_ID_2, mPendingIntent2);
            AtomTests.sleep(SLEEP_TIME_AFTER_SET_SUBSCRIBER_MS);
            StatsLog.write(StatsLog.APP_BREADCRUMB_REPORTED, UID, /* label */ 1,
                    StatsLog.APP_BREADCRUMB_REPORTED__STATE__START);
            AtomTests.sleep(SLEEP_TIME_AFTER_SET_SUBSCRIBER_MS);
            // During sleep, both latches count down by 1, as tested in testBroadcastSubscriber.

            // Now remove subscriber2 and make sure only subscriber1 receives broadcast.
            mStatsManager.setBroadcastSubscriber(CONFIG_ID, SUBSCRIBER_ID_2, null);
            AtomTests.sleep(SLEEP_TIME_AFTER_SET_SUBSCRIBER_MS);
            StatsLog.write(StatsLog.APP_BREADCRUMB_REPORTED, UID, /* label */ 1,
                    StatsLog.APP_BREADCRUMB_REPORTED__STATE__START);
            Assert.assertTrue(waitForLatch(sLatch1, 10_000));
            Log.d(TAG, "About to wait for a latch, expecting it to not finish");
            Assert.assertFalse(waitForLatch(sLatch2, 2_000)); // should fail

            // Now add subscriber2 back again and make sure it receives this time.
            mStatsManager.setBroadcastSubscriber(CONFIG_ID, SUBSCRIBER_ID_2, mPendingIntent2);
            AtomTests.sleep(SLEEP_TIME_AFTER_SET_SUBSCRIBER_MS);
            StatsLog.write(StatsLog.APP_BREADCRUMB_REPORTED, UID, /* label */ 1,
                    StatsLog.APP_BREADCRUMB_REPORTED__STATE__START);
            Assert.assertTrue(waitForLatch(sLatch2, 10_000));
        } finally {
            mStatsManager.removeConfiguration(CONFIG_ID);
        }
    }

    /** Prints some information about the PendingIntent to logcat. */
    private static void logPendingIntent(PendingIntent pendingIntent) {
        Log.d(TAG, String.format("Created PendingIntent with " +
                        "CreatorPackage=%s, CreatorUid=%d, toString=%s",
                pendingIntent.getCreatorPackage(),
                pendingIntent.getCreatorUid(),
                pendingIntent.toString()));
    }

    /** Checks that the intent (presumably received from a broadcast) is as expected. */
    private static void checkIntent(Intent intent, long subscriptionId) {
        Log.d(TAG, "Received " + (intent != null ? intent.toString() : "null intent")
        + "\nwith extras " + (intent != null && intent.getExtras() != null ?
                intent.getExtras().toString() : "(null intent)"));

        Assert.assertNotNull(intent);

        long intentConfigUid = intent.getLongExtra(StatsManager.EXTRA_STATS_CONFIG_UID, -1);
        long intentConfigKey = intent.getLongExtra(StatsManager.EXTRA_STATS_CONFIG_KEY, -1);
        long intentSubscrId = intent.getLongExtra(StatsManager.EXTRA_STATS_SUBSCRIPTION_ID, -1);
        long intentRuleId = intent.getLongExtra(StatsManager.EXTRA_STATS_SUBSCRIPTION_RULE_ID, -1);
        List<String> intentBroadcastCookies = intent.getStringArrayListExtra(
                StatsManager.EXTRA_STATS_BROADCAST_SUBSCRIBER_COOKIES);
        StatsDimensionsValue intentDimsValue =
                intent.getParcelableExtra(StatsManager.EXTRA_STATS_DIMENSIONS_VALUE);
        String intentDimsValueStr =
                intentDimsValue != null ? intentDimsValue.toString() : "null";

        Log.d(TAG, "Relevant extras are"
                + " {ConfigUid=" + intentConfigUid
                + ", ConfigKey=" + intentConfigKey
                + ", SubscriptionId=" + intentSubscrId
                + ", RuleId=" + intentRuleId
                + ", ExtraInts=" + intentBroadcastCookies.toString()
                + ", DimensionsValue=" + intentDimsValueStr
                + "}");

        Assert.assertEquals(UID, intentConfigUid);
        Assert.assertEquals(CONFIG_ID, intentConfigKey);
        Assert.assertEquals(subscriptionId, intentSubscrId);
        Assert.assertEquals(METRIC_ID, intentRuleId);

        if (intentSubscrId == SUBSCRIBER_ID_1) {
            Assert.assertEquals(2, intentBroadcastCookies.size());
            Assert.assertTrue(intentBroadcastCookies.contains(COOKIE_1_A));
            Assert.assertTrue(intentBroadcastCookies.contains(COOKIE_1_B));
        } else {
            Assert.assertEquals(0, intentBroadcastCookies.size());
        }

        String expectedDimValue = String.format("%d:{%d:%d|}",
                Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER, AppBreadcrumbReported.UID_FIELD_NUMBER,
                UID);
        Assert.assertEquals(expectedDimValue, intentDimsValueStr);

        Assert.assertEquals(Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER, intentDimsValue.getField());
        List<StatsDimensionsValue> intentTuple = intentDimsValue.getTupleValueList();
        Assert.assertEquals(1, intentTuple.size());
        StatsDimensionsValue intentTupleValue = intentTuple.get(0);
        Assert.assertEquals(AppBreadcrumbReported.UID_FIELD_NUMBER, intentTupleValue.getField());
        Assert.assertTrue(intentTupleValue.isValueType(StatsDimensionsValue.INT_VALUE_TYPE));
        Assert.assertEquals(UID, intentTupleValue.getIntValue());
    }

    public final static class BroadcastSubscriberReceiver1 extends BroadcastReceiver {
        @Override
        public void onReceive(Context mContext, Intent intent) {
            Log.d(TAG, "Broadcast received by BroadcastSubscriberReceiver1");
            checkIntent(intent, SUBSCRIPTION_ID_1);
            Assert.assertNotNull(sLatch1);
            sLatch1.countDown();
        }
    }
    public final static class BroadcastSubscriberReceiver2 extends BroadcastReceiver {
        @Override
        public void onReceive(Context mContext, Intent intent) {
            Log.d(TAG, "Broadcast received by BroadcastSubscriberReceiver2");
            checkIntent(intent, SUBSCRIPTION_ID_2);
            Assert.assertNotNull(sLatch2);
            sLatch2.countDown();
        }
    }

    /** Waits for up to maxWaitTimeMs for the latch to count down, and returns whether it did so. */
    private static boolean waitForLatch(CountDownLatch latch, int maxWaitTimeMs) {
        try {
            boolean didFinish
                    = latch.await(maxWaitTimeMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (didFinish) {
                Log.v(TAG, "Latch finished");
                return true;
            } else {
                Log.w(TAG, "Latch did not finish in specified time.");
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted exception while awaiting latch to finish", e);
        }
        return false;
    }
}

