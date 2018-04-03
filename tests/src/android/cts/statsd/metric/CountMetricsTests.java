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
import com.android.os.AtomsProto.Atom;
import com.android.os.AtomsProto.AppBreadcrumbReported;
import com.android.os.StatsLog;
import com.android.os.StatsLog.StatsLogReport;
import com.android.tradefed.device.DeviceNotAvailableException;

import java.util.Arrays;
import java.util.List;

public class CountMetricsTests extends DeviceAtomTestCase {

    public void testSimpleEventCountMetric() throws Exception {
        int matcherId = 1;
        StatsdConfigProto.StatsdConfig.Builder builder = createConfigBuilder();
        builder.addCountMetric(StatsdConfigProto.CountMetric.newBuilder()
                .setId(MetricsUtils.COUNT_METRIC_ID)
                .setBucket(StatsdConfigProto.TimeUnit.CTS)
                .setWhat(matcherId))
                .addAtomMatcher(MetricsUtils.simpleAtomMatcher(matcherId));
        uploadConfig(builder);

        doAppBreadcrumbReportedStart(0);
        Thread.sleep(10);
        doAppBreadcrumbReportedStop(0);
        Thread.sleep(2000);  // Wait for the metrics to propagate to statsd.

        StatsLogReport metricReport = getStatsLogReport();
        assertEquals(MetricsUtils.COUNT_METRIC_ID, metricReport.getMetricId());
        assertTrue(metricReport.hasCountMetrics());

        StatsLogReport.CountMetricDataWrapper countData = metricReport.getCountMetrics();

        assertTrue(countData.getDataCount() > 0);
        assertEquals(2, countData.getData(0).getBucketInfo(0).getCount());
    }

    public void testEventCountWithCondition() throws Exception {
        int startMatcherId = 1;
        int endMatcherId = 2;
        int whatMatcherId = 3;
        int conditionId = 4;

        StatsdConfigProto.AtomMatcher whatMatcher =
               MetricsUtils.unspecifiedAtomMatcher(whatMatcherId);

        StatsdConfigProto.AtomMatcher predicateStartMatcher =
                MetricsUtils.startAtomMatcher(startMatcherId);

        StatsdConfigProto.AtomMatcher predicateEndMatcher =
                MetricsUtils.stopAtomMatcher(endMatcherId);

        StatsdConfigProto.Predicate p = StatsdConfigProto.Predicate.newBuilder()
                .setSimplePredicate(StatsdConfigProto.SimplePredicate.newBuilder()
                        .setStart(startMatcherId)
                        .setStop(endMatcherId)
                        .setCountNesting(false))
                .setId(conditionId)
                .build();

        StatsdConfigProto.StatsdConfig.Builder builder = createConfigBuilder()
                .addCountMetric(StatsdConfigProto.CountMetric.newBuilder()
                        .setId(MetricsUtils.COUNT_METRIC_ID)
                        .setBucket(StatsdConfigProto.TimeUnit.CTS)
                        .setWhat(whatMatcherId)
                        .setCondition(conditionId))
                .addAtomMatcher(whatMatcher)
                .addAtomMatcher(predicateStartMatcher)
                .addAtomMatcher(predicateEndMatcher)
                .addPredicate(p);

        uploadConfig(builder);

        doAppBreadcrumbReported(0, AppBreadcrumbReported.State.UNSPECIFIED.ordinal());
        Thread.sleep(10);
        doAppBreadcrumbReportedStart(0);
        Thread.sleep(10);
        doAppBreadcrumbReported(0, AppBreadcrumbReported.State.UNSPECIFIED.ordinal());
        Thread.sleep(10);
        doAppBreadcrumbReportedStop(0);
        Thread.sleep(10);
        doAppBreadcrumbReported(0, AppBreadcrumbReported.State.UNSPECIFIED.ordinal());
        Thread.sleep(2000);  // Wait for the metrics to propagate to statsd.

        StatsLogReport metricReport = getStatsLogReport();
        assertEquals(MetricsUtils.COUNT_METRIC_ID, metricReport.getMetricId());
        assertTrue(metricReport.hasCountMetrics());

        StatsLogReport.CountMetricDataWrapper countData = metricReport.getCountMetrics();

        assertTrue(countData.getDataCount() > 0);
        assertEquals(1, countData.getData(0).getBucketInfo(0).getCount());
    }
}
