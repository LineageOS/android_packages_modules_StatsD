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
package android.cts.statsd.metric;

import android.cts.statsd.atom.DeviceAtomTestCase;

import com.android.internal.os.StatsdConfigProto;
import com.android.internal.os.StatsdConfigProto.AtomMatcher;
import com.android.internal.os.StatsdConfigProto.FieldMatcher;
import com.android.internal.os.StatsdConfigProto.FieldValueMatcher;
import com.android.internal.os.StatsdConfigProto.Position;
import com.android.internal.os.StatsdConfigProto.Predicate;
import com.android.internal.os.StatsdConfigProto.SimpleAtomMatcher;
import com.android.internal.os.StatsdConfigProto.SimplePredicate;
import com.android.os.AtomsProto.AppHook;
import com.android.os.AtomsProto.Atom;
import com.android.os.StatsLog.ConfigMetricsReport;
import com.android.os.StatsLog.ConfigMetricsReportList;
import com.android.os.StatsLog.DurationBucketInfo;
import com.android.os.StatsLog.StatsLogReport;
import com.android.tradefed.device.DeviceNotAvailableException;

import java.util.List;

public class DurationMetricsTests extends DeviceAtomTestCase {

    private static final int APP_HOOK_A_MATCH_START_ID = 0;
    private static final int APP_HOOK_A_MATCH_STOP_ID = 1;
    private static final int APP_HOOK_B_MATCH_START_ID = 2;
    private static final int APP_HOOK_B_MATCH_STOP_ID = 3;

    public void testDurationMetric() throws Exception {
        // Add AtomMatcher's.
        AtomMatcher startAtomMatcher = startAtomMatcher(APP_HOOK_A_MATCH_START_ID);
        AtomMatcher stopAtomMatcher = stopAtomMatcher(APP_HOOK_A_MATCH_STOP_ID);

        StatsdConfigProto.StatsdConfig.Builder builder = MetricsUtils.getEmptyConfig();
        builder.addAtomMatcher(startAtomMatcher);
        builder.addAtomMatcher(stopAtomMatcher);

        // Add Predicate's.
        SimplePredicate simplePredicate = SimplePredicate.newBuilder()
                .setStart(APP_HOOK_A_MATCH_START_ID)
                .setStop(APP_HOOK_A_MATCH_STOP_ID)
                .build();
        Predicate predicate = Predicate.newBuilder()
                .setId(StringToId("Predicate"))
                .setSimplePredicate(simplePredicate)
                .build();
        builder.addPredicate(predicate);

        // Add DurationMetric.
        builder.addDurationMetric(StatsdConfigProto.DurationMetric.newBuilder()
                .setId(MetricsUtils.DURATION_METRIC_ID)
                .setWhat(StringToId("Predicate"))
                .setAggregationType(StatsdConfigProto.DurationMetric.AggregationType.SUM)
                .setBucket(StatsdConfigProto.TimeUnit.CTS));

        // Upload config.
        uploadConfig(builder);

        // Create AppHook Start/Stop events.
        doAppHookStart(1);
        Thread.sleep(2000);
        doAppHookStop(1);

        // Wait for the metrics to propagate to statsd.
        Thread.sleep(2000);

        StatsLogReport metricReport = getStatsLogReport();
        assertEquals(MetricsUtils.DURATION_METRIC_ID, metricReport.getMetricId());
        assertTrue(metricReport.hasDurationMetrics());
        StatsLogReport.DurationMetricDataWrapper durationData
                = metricReport.getDurationMetrics();
        assertTrue(durationData.getDataCount() == 1);
        assertTrue(durationData.getData(0).getBucketInfo(0).getDurationNanos() > 0);
        assertTrue(durationData.getData(0).getBucketInfo(0).getDurationNanos() < 1e10);
    }

    public void testDurationMetricWithDimension() throws Exception {
        // Add AtomMatcher's.
        AtomMatcher startAtomMatcherA = startAtomMatcher(APP_HOOK_A_MATCH_START_ID);
        AtomMatcher stopAtomMatcherA = stopAtomMatcher(APP_HOOK_A_MATCH_STOP_ID);
        AtomMatcher startAtomMatcherB = startAtomMatcher(APP_HOOK_B_MATCH_START_ID);
        AtomMatcher stopAtomMatcherB = stopAtomMatcher(APP_HOOK_B_MATCH_STOP_ID);

        StatsdConfigProto.StatsdConfig.Builder builder = MetricsUtils.getEmptyConfig();
        builder.addAtomMatcher(startAtomMatcherA);
        builder.addAtomMatcher(stopAtomMatcherA);
        builder.addAtomMatcher(startAtomMatcherB);
        builder.addAtomMatcher(stopAtomMatcherB);

        // Add Predicate's.
        SimplePredicate simplePredicateA = SimplePredicate.newBuilder()
                .setStart(APP_HOOK_A_MATCH_START_ID)
                .setStop(APP_HOOK_A_MATCH_STOP_ID)
                .build();
        Predicate predicateA = Predicate.newBuilder()
                .setId(StringToId("Predicate_A"))
                .setSimplePredicate(simplePredicateA)
                .build();
        builder.addPredicate(predicateA);

        FieldMatcher.Builder dimensionsBuilder = FieldMatcher.newBuilder()
                .setField(AppHook.STATE_FIELD_NUMBER);
        dimensionsBuilder.addChild(FieldMatcher.newBuilder()
                .setField(AppHook.LABEL_FIELD_NUMBER)
                .setPosition(Position.FIRST)
                .addChild(FieldMatcher.newBuilder().setField(AppHook.LABEL_FIELD_NUMBER)));
        Predicate predicateB = Predicate.newBuilder()
                .setId(StringToId("Predicate_B"))
                .setSimplePredicate(SimplePredicate.newBuilder()
                        .setStart(APP_HOOK_B_MATCH_START_ID)
                        .setStop(APP_HOOK_B_MATCH_STOP_ID)
                        .setDimensions(dimensionsBuilder.build())
                        .build())
                .build();
        builder.addPredicate(predicateB);

        // Add DurationMetric.
        builder.addDurationMetric(StatsdConfigProto.DurationMetric.newBuilder()
                .setId(MetricsUtils.DURATION_METRIC_ID)
                .setWhat(StringToId("Predicate_B"))
                .setCondition(StringToId("Predicate_A"))
                .setAggregationType(StatsdConfigProto.DurationMetric.AggregationType.SUM)
                .setBucket(StatsdConfigProto.TimeUnit.CTS)
                .setDimensionsInWhat(FieldMatcher.newBuilder()
                        .setField(Atom.BATTERY_SAVER_MODE_STATE_CHANGED_FIELD_NUMBER)
                        .addChild(FieldMatcher.newBuilder()
                                .setField(AppHook.STATE_FIELD_NUMBER)
                                .setPosition(Position.FIRST)
                                .addChild(FieldMatcher.newBuilder().setField(AppHook.LABEL_FIELD_NUMBER)))));

        // Upload config.
        uploadConfig(builder);

        // Trigger events.
        doAppHookStart(1);
        Thread.sleep(2000);
        doAppHookStart(2);
        Thread.sleep(2000);
        doAppHookStop(1);
        Thread.sleep(2000);
        doAppHookStop(2);

        // Wait for the metrics to propagate to statsd.
        Thread.sleep(2000);

        StatsLogReport metricReport = getStatsLogReport();
        assertEquals(MetricsUtils.DURATION_METRIC_ID, metricReport.getMetricId());
        assertTrue(metricReport.hasDurationMetrics());
        StatsLogReport.DurationMetricDataWrapper durationData
                = metricReport.getDurationMetrics();
        assertTrue(durationData.getDataCount() == 1);
        assertTrue(durationData.getData(0).getBucketInfoList().size() > 1);
        for (DurationBucketInfo bucketInfo : durationData.getData(0).getBucketInfoList()) {
            assertTrue(bucketInfo.getDurationNanos() > 0);
            assertTrue(bucketInfo.getDurationNanos() < 1e10);
        }
    }

    private AtomMatcher startAtomMatcher(int id) {
        return AtomMatcher.newBuilder()
                .setId(id)
                .setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder()
                        .setAtomId(Atom.APP_HOOK_FIELD_NUMBER)
                        .addFieldValueMatcher(FieldValueMatcher.newBuilder()
                                .setField(AppHook.STATE_FIELD_NUMBER)
                                .setEqInt(AppHook.State.START.ordinal())))
                .build();
    }

    private AtomMatcher stopAtomMatcher(int id) {
        return AtomMatcher.newBuilder()
                .setId(id)
                .setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder()
                        .setAtomId(Atom.APP_HOOK_FIELD_NUMBER)
                        .addFieldValueMatcher(FieldValueMatcher.newBuilder()
                                .setField(AppHook.STATE_FIELD_NUMBER)
                                .setEqInt(AppHook.State.STOP.ordinal())))
                .build();
    }

    private long StringToId(String str) {
        return str.hashCode();
    }
}
