/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.cts.statsd;

import com.android.internal.os.StatsdConfigProto.Alert;
import com.android.internal.os.StatsdConfigProto.AtomMatcher;
import com.android.internal.os.StatsdConfigProto.Bucket;
import com.android.internal.os.StatsdConfigProto.CountMetric;
import com.android.internal.os.StatsdConfigProto.DurationMetric;
import com.android.internal.os.StatsdConfigProto.EventMetric;
import com.android.internal.os.StatsdConfigProto.FieldFilter;
import com.android.internal.os.StatsdConfigProto.GaugeMetric;
import com.android.internal.os.StatsdConfigProto.KeyMatcher;
import com.android.internal.os.StatsdConfigProto.KeyValueMatcher;
import com.android.internal.os.StatsdConfigProto.Predicate;
import com.android.internal.os.StatsdConfigProto.SimpleAtomMatcher;
import com.android.internal.os.StatsdConfigProto.SimplePredicate;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.internal.os.StatsdConfigProto.ValueMetric;
import com.android.os.AtomsProto.Atom;
import com.android.os.AtomsProto.KernelWakelock;
import com.android.os.AtomsProto.ScreenStateChanged;
import com.android.os.StatsLog.ConfigMetricsReport;
import com.android.os.StatsLog.ConfigMetricsReportList;

/**
 * Statsd atom tests that are done via adb (hostside).
 */
public class HostAtomTests extends AtomTestCase {

    private static final String TAG = "Statsd.HostAtomTests";

    private static final boolean TESTS_ENABLED = false;
    // For tests that require incidentd. Keep as true until TESTS_ENABLED is permanently enabled.
    private static final boolean INCIDENTD_TESTS_ENABLED = false;

    public void testScreenOnAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}
        StatsdConfig config = getDefaultConfig()
                .addEventMetric(
                        EventMetric.newBuilder().setName("METRIC").setWhat("SCREEN_TURNED_ON"))
                .build();
        uploadConfig(config);

        turnScreenOff();
        Thread.sleep(2000);
        turnScreenOn();
        Thread.sleep(2000);

        ConfigMetricsReportList reportList = getReportList();

        assertTrue(reportList.getReportsCount() == 1);
        ConfigMetricsReport report = reportList.getReports(0);
        assertTrue(report.getMetricsCount() == 1);
        assertTrue(report.getMetrics(0).getEventMetrics().getDataCount() == 1);
        assertTrue(report.getMetrics(0).getEventMetrics().getData(
                0).getAtom().getScreenStateChanged()
                .getDisplayState().getNumber() ==
                ScreenStateChanged.State.STATE_ON_VALUE);
    }

    public void testScreenOffAtom() throws Exception {
        if (!TESTS_ENABLED) {return;}
        StatsdConfig config = getDefaultConfig()
                .addEventMetric(
                        EventMetric.newBuilder().setName("METRIC").setWhat("SCREEN_TURNED_OFF"))
                .build();
        uploadConfig(config);

        turnScreenOn();
        Thread.sleep(2000);
        turnScreenOff();
        Thread.sleep(2000);

        ConfigMetricsReportList reportList = getReportList();

        assertTrue(reportList.getReportsCount() == 1);
        ConfigMetricsReport report = reportList.getReports(0);
        assertTrue(report.getMetricsCount() == 1);
        // one of them can be DOZE
        assertTrue(report.getMetrics(0).getEventMetrics().getDataCount() >= 1);
        assertTrue(report.getMetrics(0).getEventMetrics().getData(
                0).getAtom().getScreenStateChanged()
                .getDisplayState().getNumber() == ScreenStateChanged.State.STATE_OFF_VALUE ||
                report.getMetrics(0).getEventMetrics().getData(0).getAtom().getScreenStateChanged()
                        .getDisplayState().getNumber()
                        == ScreenStateChanged.State.STATE_DOZE_VALUE);
    }

    // TODO: Anomaly detection will be moved to general statsd device-side tests.
    // Tests that anomaly detection for count works.
    // Also tests that anomaly detection works when spanning multiple buckets.
    public void testCountAnomalyDetection() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!INCIDENTD_TESTS_ENABLED) return;
        // TODO: Don't use screen-state as the atom.
        StatsdConfig config = getDefaultConfig()
                .addCountMetric(CountMetric.newBuilder()
                    .setName("METRIC")
                    .setWhat("SCREEN_TURNED_ON")
                    .setBucket(Bucket.newBuilder().setBucketSizeMillis(5_000))
                )
                .addAlert(Alert.newBuilder()
                    .setName("testCountAnomalyDetectionAlert")
                    .setMetricName("METRIC")
                    .setNumberOfBuckets(4)
                    .setRefractoryPeriodSecs(20)
                    .setTriggerIfSumGt(2)
                    .setIncidentdDetails(Alert.IncidentdDetails.newBuilder()
                        .addSection(-1)
                    )
                )
                .build();
        uploadConfig(config);

        String markDeviceDate = getCurrentLogcatDate();
        turnScreenOn(); // count -> 1 (not an anomaly, since not "greater than 2")
        Thread.sleep(1000);
        turnScreenOff();
        Thread.sleep(3000);
        assertFalse(didIncidentdFireSince(markDeviceDate));

        turnScreenOn(); // count ->2 (not an anomaly, since not "greater than 2")
        Thread.sleep(1000);
        turnScreenOff();
        Thread.sleep(1000);
        assertFalse(didIncidentdFireSince(markDeviceDate));

        turnScreenOn(); // count ->3 (anomaly, since "greater than 2"!)
        Thread.sleep(1000);
        assertTrue(didIncidentdFireSince(markDeviceDate));

        turnScreenOff();
    }

    // Tests that anomaly detection for duration works.
    // Also tests that refractory periods in anomaly detection work.
    public void testDurationAnomalyDetection() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!INCIDENTD_TESTS_ENABLED) return;
        // TODO: Do NOT use screenState for this, since screens auto-turn-off after a variable time.
        StatsdConfig config = getDefaultConfig()
                .addDurationMetric(DurationMetric.newBuilder()
                        .setName("METRIC")
                        .setWhat("SCREEN_IS_ON")
                        .setAggregationType(DurationMetric.AggregationType.SUM)
                        .setBucket(Bucket.newBuilder().setBucketSizeMillis(5_000))
                )
                .addAlert(Alert.newBuilder()
                        .setName("testDurationAnomalyDetectionAlert")
                        .setMetricName("METRIC")
                        .setNumberOfBuckets(12)
                        .setRefractoryPeriodSecs(20)
                        .setTriggerIfSumGt(15_000_000_000L) // 15 seconds in nanoseconds
                        .setIncidentdDetails(Alert.IncidentdDetails.newBuilder()
                                .addSection(-1)
                        )
                )
                .build();
        uploadConfig(config);

        // Test that alarm doesn't fire early.
        String markDeviceDate = getCurrentLogcatDate();
        turnScreenOn();
        Thread.sleep(6_000);
        assertFalse(didIncidentdFireSince(markDeviceDate));

        turnScreenOff();
        Thread.sleep(1_000);
        assertFalse(didIncidentdFireSince(markDeviceDate));

        // Test that alarm does fire when it is supposed to.
        turnScreenOn();
        Thread.sleep(13_000);
        assertTrue(didIncidentdFireSince(markDeviceDate));

        // Now test that the refractory period is obeyed.
        markDeviceDate = getCurrentLogcatDate();
        turnScreenOff();
        Thread.sleep(1_000);
        turnScreenOn();
        Thread.sleep(1_000);
        assertFalse(didIncidentdFireSince(markDeviceDate));

        // Test that detection works again after refractory period finishes.
        turnScreenOff();
        Thread.sleep(20_000);
        turnScreenOn();
        Thread.sleep(15_000);
        assertTrue(didIncidentdFireSince(markDeviceDate));
    }

    // TODO: There is no value anomaly detection code yet! So this will fail.
    // Tests that anomaly detection for value works.
    public void testValueAnomalyDetection() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!INCIDENTD_TESTS_ENABLED) return;
        // TODO: Definitely don't use screen-state as the atom. This MUST be changed.
        StatsdConfig config = getDefaultConfig()
                .addValueMetric(ValueMetric.newBuilder()
                        .setName("METRIC")
                        .setWhat("SCREEN_TURNED_ON")
                        .setValueField(ScreenStateChanged.DISPLAY_STATE_FIELD_NUMBER)
                        .setBucket(Bucket.newBuilder().setBucketSizeMillis(5_000))
                )
                .addAlert(Alert.newBuilder()
                        .setName("testValueAnomalyDetectionAlert")
                        .setMetricName("METRIC")
                        .setNumberOfBuckets(4)
                        .setRefractoryPeriodSecs(20)
                        .setTriggerIfSumGt(ScreenStateChanged.State.STATE_OFF.getNumber())
                        .setIncidentdDetails(Alert.IncidentdDetails.newBuilder()
                                .addSection(-1)
                        )
                )
                .build();
        uploadConfig(config);

        turnScreenOff();
        String markDeviceDate = getCurrentLogcatDate();
        turnScreenOff(); // value = STATE_OFF = 1 (probably)
        Thread.sleep(2000);
        assertFalse(didIncidentdFireSince(markDeviceDate));
        turnScreenOn(); // value = STATE_ON = 2 (probably)
        Thread.sleep(2000);
        assertTrue(didIncidentdFireSince(markDeviceDate));

        turnScreenOff();
    }

    // Tests that anomaly detection for gauge works.
    public void testGaugeAnomalyDetection() throws Exception {
        if (!TESTS_ENABLED) return;
        if (!INCIDENTD_TESTS_ENABLED) return;
        // TODO: Definitely don't use screen-state as the atom. This MUST be changed.
        StatsdConfig config = getDefaultConfig()
                .addGaugeMetric(GaugeMetric.newBuilder()
                        .setName("METRIC")
                        .setWhat("SCREEN_TURNED_ON")
                        .setGaugeFields(FieldFilter.newBuilder()
                                .addFieldNum(ScreenStateChanged.DISPLAY_STATE_FIELD_NUMBER))
                        .setBucket(Bucket.newBuilder().setBucketSizeMillis(10_000))
                )
                .addAlert(Alert.newBuilder()
                        .setName("testGaugeAnomalyDetectionAlert")
                        .setMetricName("METRIC")
                        .setNumberOfBuckets(1)
                        .setRefractoryPeriodSecs(20)
                        .setTriggerIfSumGt(ScreenStateChanged.State.STATE_OFF.getNumber())
                        .setIncidentdDetails(Alert.IncidentdDetails.newBuilder()
                                .addSection(-1)
                        )
                )
                .build();
        uploadConfig(config);

        turnScreenOff();
        String markDeviceDate = getCurrentLogcatDate();
        turnScreenOff(); // gauge = STATE_OFF = 1 (probably)
        Thread.sleep(2000);
        assertFalse(didIncidentdFireSince(markDeviceDate));
        turnScreenOn(); // gauge = STATE_ON = 2 (probably)
        Thread.sleep(2000);
        assertTrue(didIncidentdFireSince(markDeviceDate));

        turnScreenOff();
    }

    public void testKernelWakelock() throws Exception {
        if (!TESTS_ENABLED) {return;}
        StatsdConfig config = getDefaultConfig()
                .addGaugeMetric(
                        GaugeMetric.newBuilder()
                                .setName("METRIC")
                                .setWhat("KERNEL_WAKELOCK")
                                .setCondition("SCREEN_IS_ON")
                                .addDimension(KeyMatcher.newBuilder()
                                        .setKey(KernelWakelock.NAME_FIELD_NUMBER))
                                .setGaugeFields(FieldFilter.newBuilder().
                                        setIncludeAll(true))
                                .setBucket(Bucket.newBuilder().setBucketSizeMillis(1000)))
                .build();

        turnScreenOff();

        uploadConfig(config);

        Thread.sleep(2000);
        turnScreenOn();
        Thread.sleep(2000);

        ConfigMetricsReportList reportList = getReportList();

        assertTrue(reportList.getReportsCount() == 1);
        ConfigMetricsReport report = reportList.getReports(0);
        assertTrue(report.getMetricsCount() >= 1);
        assertTrue(report.getMetrics(0).getGaugeMetrics().getDataCount() >= 1);
        Atom atom = report.getMetrics(0).getGaugeMetrics().getData(1).getBucketInfo(0).getAtom();
        assertTrue(!atom.getKernelWakelock().getName().equals(""));
        assertTrue(atom.getKernelWakelock().hasCount());
        assertTrue(atom.getKernelWakelock().hasVersion());
        assertTrue(atom.getKernelWakelock().getVersion() > 0);
        assertTrue(atom.getKernelWakelock().hasTime());
    }

    /**
     * Get default config builder for atoms CTS testing.
     * All matchers are included. One just need to add event metric for pushed events or
     * gauge metric for pulled metric.
     */
    protected StatsdConfig.Builder getDefaultConfig() {
        StatsdConfig.Builder configBuilder = StatsdConfig.newBuilder();
        configBuilder.setName("12345");
        configBuilder
            .addAtomMatcher(AtomMatcher.newBuilder()
                .setName("SCREEN_TURNED_ON")
                .setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder()
                    .setTag(Atom.SCREEN_STATE_CHANGED_FIELD_NUMBER)
                    .addKeyValueMatcher(KeyValueMatcher.newBuilder()
                        .setKeyMatcher(KeyMatcher.newBuilder()
                            .setKey(ScreenStateChanged.DISPLAY_STATE_FIELD_NUMBER)
                        )
                        .setEqInt(ScreenStateChanged.State.STATE_ON_VALUE)
                    )
                )
            )
            .addAtomMatcher(AtomMatcher.newBuilder()
                .setName("SCREEN_TURNED_OFF")
                .setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder()
                    .setTag(Atom.SCREEN_STATE_CHANGED_FIELD_NUMBER)
                    .addKeyValueMatcher(KeyValueMatcher.newBuilder()
                        .setKeyMatcher(KeyMatcher.newBuilder()
                            .setKey(ScreenStateChanged.DISPLAY_STATE_FIELD_NUMBER)
                        )
                        .setEqInt(ScreenStateChanged.State.STATE_OFF_VALUE)
                    )
                )
            )
            .addAtomMatcher(AtomMatcher.newBuilder()
                .setName("UID_PROCESS_STATE_CHANGED")
                .setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder()
                    .setTag(Atom.UID_PROCESS_STATE_CHANGED_FIELD_NUMBER)
                )
            )
            .addAtomMatcher(AtomMatcher.newBuilder()
                .setName("KERNEL_WAKELOCK")
                .setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder()
                    .setTag(Atom.KERNEL_WAKELOCK_FIELD_NUMBER)
                )
            )
            .addAtomMatcher(AtomMatcher.newBuilder()
                .setName("CPU_TIME_PER_UID")
                .setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder()
                    .setTag(Atom.CPU_TIME_PER_UID_FIELD_NUMBER))
            )
            .addAtomMatcher(AtomMatcher.newBuilder()
                .setName("CPU_TIME_PER_FREQ")
                .setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder()
                    .setTag(Atom.CPU_TIME_PER_FREQ_FIELD_NUMBER))
            )
            .addPredicate(Predicate.newBuilder()
                .setName("SCREEN_IS_ON")
                .setSimplePredicate(SimplePredicate.newBuilder()
                    .setStart("SCREEN_TURNED_ON")
                    .setStop("SCREEN_TURNED_OFF")
                    .setCountNesting(false)
                )
            );
        return configBuilder;
    }
}
