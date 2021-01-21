// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <android-base/properties.h>
#include <android-base/stringprintf.h>
#include <android/binder_interface_utils.h>
#include <gtest/gtest.h>

#include "flags/flags.h"
#include "src/StatsLogProcessor.h"
#include "src/storage/StorageManager.h"
#include "tests/statsd_test_util.h"

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

using android::base::SetProperty;
using android::base::StringPrintf;
using ::ndk::SharedRefBase;
using namespace std;

// Tests that only run with the partial config update feature turned on.
namespace {
// Setup for test fixture.
class ConfigUpdateE2eTest : public ::testing::Test {
private:
    string originalFlagValue;
public:
    void SetUp() override {
        originalFlagValue = getFlagBool(PARTIAL_CONFIG_UPDATE_FLAG, "");
        string rawFlagName =
                StringPrintf("persist.device_config.%s.%s", STATSD_NATIVE_NAMESPACE.c_str(),
                             PARTIAL_CONFIG_UPDATE_FLAG.c_str());
        SetProperty(rawFlagName, "true");
    }

    void TearDown() override {
        string rawFlagName =
                StringPrintf("persist.device_config.%s.%s", STATSD_NATIVE_NAMESPACE.c_str(),
                             PARTIAL_CONFIG_UPDATE_FLAG.c_str());
        SetProperty(rawFlagName, originalFlagValue);
    }
};

void ValidateSubsystemSleepDimension(const DimensionsValue& value, string name) {
    EXPECT_EQ(value.field(), util::SUBSYSTEM_SLEEP_STATE);
    ASSERT_EQ(value.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(value.value_tuple().dimensions_value(0).field(), 1 /* subsystem name field */);
    EXPECT_EQ(value.value_tuple().dimensions_value(0).value_str(), name);
}

}  // Anonymous namespace.

TEST_F(ConfigUpdateE2eTest, TestCountMetric) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");

    AtomMatcher syncStartMatcher = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = syncStartMatcher;
    AtomMatcher wakelockAcquireMatcher = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = wakelockAcquireMatcher;
    AtomMatcher wakelockReleaseMatcher = CreateReleaseWakelockAtomMatcher();
    *config.add_atom_matcher() = wakelockReleaseMatcher;
    AtomMatcher screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = screenOnMatcher;
    AtomMatcher screenOffMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = screenOffMatcher;

    Predicate holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by first attribution node by uid.
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *config.add_predicate() = holdingWakelockPredicate;

    Predicate screenOnPredicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = screenOnPredicate;

    Predicate* combination = config.add_predicate();
    combination->set_id(StringToId("ScreenOnAndHoldingWL)"));
    combination->mutable_combination()->set_operation(LogicalOperation::AND);
    addPredicateToPredicateCombination(screenOnPredicate, combination);
    addPredicateToPredicateCombination(holdingWakelockPredicate, combination);

    State uidProcessState = CreateUidProcessState();
    *config.add_state() = uidProcessState;

    CountMetric countPersist =
            createCountMetric("CountSyncPerUidWhileScreenOnHoldingWLSliceProcessState",
                              syncStartMatcher.id(), combination->id(), {uidProcessState.id()});
    *countPersist.mutable_dimensions_in_what() =
            CreateAttributionUidDimensions(util::SYNC_STATE_CHANGED, {Position::FIRST});
    // Links between sync state atom and condition of uid is holding wakelock.
    MetricConditionLink* links = countPersist.add_links();
    links->set_condition(holdingWakelockPredicate.id());
    *links->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::SYNC_STATE_CHANGED, {Position::FIRST});
    *links->mutable_fields_in_condition() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    MetricStateLink* stateLink = countPersist.add_state_link();
    stateLink->set_state_atom_id(util::UID_PROCESS_STATE_CHANGED);
    *stateLink->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::SYNC_STATE_CHANGED, {Position::FIRST});
    *stateLink->mutable_fields_in_state() =
            CreateDimensions(util::UID_PROCESS_STATE_CHANGED, {1 /*uid*/});

    CountMetric countChange = createCountMetric("Count*WhileScreenOn", syncStartMatcher.id(),
                                                screenOnPredicate.id(), {});
    CountMetric countRemove = createCountMetric("CountSync", syncStartMatcher.id(), nullopt, {});
    *config.add_count_metric() = countRemove;
    *config.add_count_metric() = countPersist;
    *config.add_count_metric() = countChange;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(TEN_MINUTES) * 1000000LL;
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    int app1Uid = 123, app2Uid = 456;
    vector<int> attributionUids1 = {app1Uid};
    vector<string> attributionTags1 = {"App1"};
    vector<int> attributionUids2 = {app2Uid};
    vector<string> attributionTags2 = {"App2"};

    // Initialize log events before update. Counts are for countPersist since others are simpler.
    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 2 * NS_PER_SEC, app1Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND));
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 3 * NS_PER_SEC, app2Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND));
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 5 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // Not counted.
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 10 * NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_ON));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 15 * NS_PER_SEC,
                                                attributionUids1, attributionTags1, "wl1"));
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 20 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // Counted. uid1 = 1.
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 21 * NS_PER_SEC, attributionUids2,
                                          attributionTags2, "sync_name"));  // Not counted.
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 25 * NS_PER_SEC,
                                                attributionUids2, attributionTags2, "wl2"));
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 30 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // Counted. uid1 = 2.
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 31 * NS_PER_SEC, attributionUids2,
                                          attributionTags2, "sync_name"));  // Counted. uid2 = 1
    events.push_back(CreateReleaseWakelockEvent(bucketStartTimeNs + 35 * NS_PER_SEC,
                                                attributionUids1, attributionTags1, "wl1"));
    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    // Do update. Add matchers/conditions in different order to force indices to change.
    StatsdConfig newConfig;
    newConfig.add_allowed_log_source("AID_ROOT");

    *newConfig.add_atom_matcher() = screenOnMatcher;
    *newConfig.add_atom_matcher() = screenOffMatcher;
    *newConfig.add_atom_matcher() = syncStartMatcher;
    *newConfig.add_atom_matcher() = wakelockAcquireMatcher;
    *newConfig.add_atom_matcher() = wakelockReleaseMatcher;
    *newConfig.add_predicate() = *combination;
    *newConfig.add_predicate() = holdingWakelockPredicate;
    *newConfig.add_predicate() = screenOnPredicate;
    *newConfig.add_state() = uidProcessState;

    countChange.set_what(screenOnMatcher.id());
    *newConfig.add_count_metric() = countChange;
    CountMetric countNew = createCountMetric("CountWlWhileScreenOn", wakelockAcquireMatcher.id(),
                                             screenOnPredicate.id(), {});
    *newConfig.add_count_metric() = countNew;
    *newConfig.add_count_metric() = countPersist;

    int64_t updateTimeNs = bucketStartTimeNs + 60 * NS_PER_SEC;
    processor->OnConfigUpdated(updateTimeNs, key, newConfig);

    // Send events after the update. Counts reset to 0 since this is a new bucket.
    events.clear();
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 65 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // Not counted.
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 66 * NS_PER_SEC, attributionUids2,
                                          attributionTags2, "sync_name"));  // Counted. uid2 = 1.
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 70 * NS_PER_SEC,
                                                attributionUids1, attributionTags1, "wl1"));
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 75 * NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_OFF));
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 80 * NS_PER_SEC, attributionUids2,
                                          attributionTags2, "sync_name"));  // Not counted.
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 85 * NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_ON));
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 90 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // Counted. uid1 = 1.
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 11 * NS_PER_SEC, attributionUids2,
                                          attributionTags2, "sync_name"));  // Counted. uid2 = 2.
    // Flushes bucket.
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs + NS_PER_SEC,
                                                attributionUids1, attributionTags1, "wl2"));
    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }
    uint64_t dumpTimeNs = bucketStartTimeNs + bucketSizeNs + 10 * NS_PER_SEC;
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 2);

    // Report from before update.
    ConfigMetricsReport report = reports.reports(0);
    ASSERT_EQ(report.metrics_size(), 3);
    // Count syncs. There were 5 syncs before the update.
    StatsLogReport countRemoveBefore = report.metrics(0);
    EXPECT_EQ(countRemoveBefore.metric_id(), countRemove.id());
    EXPECT_TRUE(countRemoveBefore.has_count_metrics());
    ASSERT_EQ(countRemoveBefore.count_metrics().data_size(), 1);
    auto data = countRemoveBefore.count_metrics().data(0);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), bucketStartTimeNs, updateTimeNs, 5);

    // Uid 1 had 2 syncs, uid 2 had 1 sync.
    StatsLogReport countPersistBefore = report.metrics(1);
    EXPECT_EQ(countPersistBefore.metric_id(), countPersist.id());
    EXPECT_TRUE(countPersistBefore.has_count_metrics());
    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(countPersistBefore.count_metrics(), &countMetrics);
    ASSERT_EQ(countMetrics.data_size(), 2);
    data = countMetrics.data(0);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::SYNC_STATE_CHANGED, app1Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), bucketStartTimeNs, updateTimeNs, 2);

    data = countMetrics.data(1);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::SYNC_STATE_CHANGED, app2Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), bucketStartTimeNs, updateTimeNs, 1);

    // Counts syncs while screen on. There were 4 before the update.
    StatsLogReport countChangeBefore = report.metrics(2);
    EXPECT_EQ(countChangeBefore.metric_id(), countChange.id());
    EXPECT_TRUE(countChangeBefore.has_count_metrics());
    ASSERT_EQ(countChangeBefore.count_metrics().data_size(), 1);
    data = countChangeBefore.count_metrics().data(0);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), bucketStartTimeNs, updateTimeNs, 4);

    // Report from after update.
    report = reports.reports(1);
    ASSERT_EQ(report.metrics_size(), 3);
    // Count screen on while screen is on. There was 1 after the update.
    StatsLogReport countChangeAfter = report.metrics(0);
    EXPECT_EQ(countChangeAfter.metric_id(), countChange.id());
    EXPECT_TRUE(countChangeAfter.has_count_metrics());
    ASSERT_EQ(countChangeAfter.count_metrics().data_size(), 1);
    data = countChangeAfter.count_metrics().data(0);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), updateTimeNs, bucketStartTimeNs + bucketSizeNs, 1);

    // Count wl acquires while screen on. There were 2, one in each bucket.
    StatsLogReport countNewAfter = report.metrics(1);
    EXPECT_EQ(countNewAfter.metric_id(), countNew.id());
    EXPECT_TRUE(countNewAfter.has_count_metrics());
    ASSERT_EQ(countNewAfter.count_metrics().data_size(), 1);
    data = countNewAfter.count_metrics().data(0);
    ASSERT_EQ(data.bucket_info_size(), 2);
    ValidateCountBucket(data.bucket_info(0), updateTimeNs, bucketStartTimeNs + bucketSizeNs, 1);
    ValidateCountBucket(data.bucket_info(1), bucketStartTimeNs + bucketSizeNs, dumpTimeNs, 1);

    // Uid 1 had 1 sync, uid 2 had 2 syncs.
    StatsLogReport countPersistAfter = report.metrics(2);
    EXPECT_EQ(countPersistAfter.metric_id(), countPersist.id());
    EXPECT_TRUE(countPersistAfter.has_count_metrics());
    countMetrics.Clear();
    sortMetricDataByDimensionsValue(countPersistAfter.count_metrics(), &countMetrics);
    ASSERT_EQ(countMetrics.data_size(), 2);
    data = countMetrics.data(0);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::SYNC_STATE_CHANGED, app1Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), updateTimeNs, bucketStartTimeNs + bucketSizeNs, 1);

    data = countMetrics.data(1);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::SYNC_STATE_CHANGED, app2Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), updateTimeNs, bucketStartTimeNs + bucketSizeNs, 2);
}

TEST_F(ConfigUpdateE2eTest, TestGaugeMetric) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");
    config.add_default_pull_packages("AID_ROOT");  // Fake puller is registered with root.

    AtomMatcher appStartMatcher = CreateSimpleAtomMatcher("AppStart", util::APP_START_OCCURRED);
    *config.add_atom_matcher() = appStartMatcher;
    AtomMatcher backgroundMatcher = CreateMoveToBackgroundAtomMatcher();
    *config.add_atom_matcher() = backgroundMatcher;
    AtomMatcher foregroundMatcher = CreateMoveToForegroundAtomMatcher();
    *config.add_atom_matcher() = foregroundMatcher;
    AtomMatcher screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = screenOnMatcher;
    AtomMatcher screenOffMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = screenOffMatcher;
    AtomMatcher subsystemSleepMatcher =
            CreateSimpleAtomMatcher("SubsystemSleep", util::SUBSYSTEM_SLEEP_STATE);
    *config.add_atom_matcher() = subsystemSleepMatcher;

    Predicate isInBackgroundPredicate = CreateIsInBackgroundPredicate();
    *isInBackgroundPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /*uid field*/});
    *config.add_predicate() = isInBackgroundPredicate;

    Predicate screenOnPredicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = screenOnPredicate;

    GaugeMetric gaugePullPersist =
            createGaugeMetric("SubsystemSleepWhileScreenOn", subsystemSleepMatcher.id(),
                              GaugeMetric::RANDOM_ONE_SAMPLE, screenOnPredicate.id(), {});
    *gaugePullPersist.mutable_dimensions_in_what() =
            CreateDimensions(util::SUBSYSTEM_SLEEP_STATE, {1 /* subsystem name */});

    GaugeMetric gaugePushPersist =
            createGaugeMetric("AppStartWhileInBg", appStartMatcher.id(),
                              GaugeMetric::FIRST_N_SAMPLES, isInBackgroundPredicate.id(), nullopt);
    *gaugePushPersist.mutable_dimensions_in_what() =
            CreateDimensions(util::APP_START_OCCURRED, {1 /*uid field*/});
    // Links between sync state atom and condition of uid is holding wakelock.
    MetricConditionLink* links = gaugePushPersist.add_links();
    links->set_condition(isInBackgroundPredicate.id());
    *links->mutable_fields_in_what() =
            CreateDimensions(util::APP_START_OCCURRED, {1 /*uid field*/});
    *links->mutable_fields_in_condition() =
            CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /*uid field*/});

    GaugeMetric gaugeChange = createGaugeMetric("GaugeScrOn", screenOnMatcher.id(),
                                                GaugeMetric::RANDOM_ONE_SAMPLE, nullopt, nullopt);
    GaugeMetric gaugeRemove =
            createGaugeMetric("GaugeSubsysTriggerScr", subsystemSleepMatcher.id(),
                              GaugeMetric::FIRST_N_SAMPLES, nullopt, screenOnMatcher.id());
    *gaugeRemove.mutable_dimensions_in_what() =
            CreateDimensions(util::SUBSYSTEM_SLEEP_STATE, {1 /* subsystem name */});
    *config.add_gauge_metric() = gaugeRemove;
    *config.add_gauge_metric() = gaugePullPersist;
    *config.add_gauge_metric() = gaugeChange;
    *config.add_gauge_metric() = gaugePushPersist;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = getElapsedRealtimeNs();  // 0:10
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(TEN_MINUTES) * 1000000LL;
    sp<StatsLogProcessor> processor = CreateStatsLogProcessor(
            bucketStartTimeNs, bucketStartTimeNs, config, key,
            SharedRefBase::make<FakeSubsystemSleepCallback>(), util::SUBSYSTEM_SLEEP_STATE);

    int app1Uid = 123, app2Uid = 456;

    // Initialize log events before update.
    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(CreateMoveToBackgroundEvent(bucketStartTimeNs + 5 * NS_PER_SEC, app1Uid));
    events.push_back(CreateAppStartOccurredEvent(bucketStartTimeNs + 10 * NS_PER_SEC, app1Uid,
                                                 "app1", AppStartOccurred::WARM, "", "", true,
                                                 /*start_msec*/ 101));  // Kept by gaugePushPersist.
    events.push_back(
            CreateAppStartOccurredEvent(bucketStartTimeNs + 15 * NS_PER_SEC, app2Uid, "app2",
                                        AppStartOccurred::WARM, "", "", true,
                                        /*start_msec*/ 201));  // Not kept by gaugePushPersist.
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 20 * NS_PER_SEC,
            android::view::DISPLAY_STATE_ON));  // Pulls gaugePullPersist and gaugeRemove.
    events.push_back(CreateMoveToBackgroundEvent(bucketStartTimeNs + 25 * NS_PER_SEC, app2Uid));
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 30 * NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_OFF));
    events.push_back(CreateAppStartOccurredEvent(bucketStartTimeNs + 35 * NS_PER_SEC, app1Uid,
                                                 "app1", AppStartOccurred::WARM, "", "", true,
                                                 /*start_msec*/ 102));  // Kept by gaugePushPersist.
    events.push_back(CreateAppStartOccurredEvent(bucketStartTimeNs + 40 * NS_PER_SEC, app2Uid,
                                                 "app2", AppStartOccurred::WARM, "", "", true,
                                                 /*start_msec*/ 202));  // Kept by gaugePushPersist.
    events.push_back(CreateScreenStateChangedEvent(
            bucketStartTimeNs + 45 * NS_PER_SEC,
            android::view::DisplayStateEnum::DISPLAY_STATE_ON));  // Pulls gaugeRemove only.
    events.push_back(CreateMoveToForegroundEvent(bucketStartTimeNs + 50 * NS_PER_SEC, app1Uid));

    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->mPullerManager->ForceClearPullerCache();
        processor->OnLogEvent(event.get());
    }
    processor->mPullerManager->ForceClearPullerCache();

    // Do the update. Add matchers/conditions in different order to force indices to change.
    StatsdConfig newConfig;
    newConfig.add_allowed_log_source("AID_ROOT");
    newConfig.add_default_pull_packages("AID_ROOT");  // Fake puller is registered with root.

    *newConfig.add_atom_matcher() = screenOffMatcher;
    *newConfig.add_atom_matcher() = foregroundMatcher;
    *newConfig.add_atom_matcher() = appStartMatcher;
    *newConfig.add_atom_matcher() = subsystemSleepMatcher;
    *newConfig.add_atom_matcher() = backgroundMatcher;
    *newConfig.add_atom_matcher() = screenOnMatcher;

    *newConfig.add_predicate() = isInBackgroundPredicate;
    *newConfig.add_predicate() = screenOnPredicate;

    gaugeChange.set_sampling_type(GaugeMetric::FIRST_N_SAMPLES);
    *newConfig.add_gauge_metric() = gaugeChange;
    GaugeMetric gaugeNew = createGaugeMetric("GaugeSubsys", subsystemSleepMatcher.id(),
                                             GaugeMetric::RANDOM_ONE_SAMPLE, {}, {});
    *gaugeNew.mutable_dimensions_in_what() =
            CreateDimensions(util::SUBSYSTEM_SLEEP_STATE, {1 /* subsystem name */});
    *newConfig.add_gauge_metric() = gaugeNew;
    *newConfig.add_gauge_metric() = gaugePushPersist;
    *newConfig.add_gauge_metric() = gaugePullPersist;

    int64_t updateTimeNs = bucketStartTimeNs + 60 * NS_PER_SEC;
    // Update pulls gaugePullPersist and gaugeNew.
    processor->OnConfigUpdated(updateTimeNs, key, newConfig);

    // Verify puller manager is properly set.
    sp<StatsPullerManager> pullerManager = processor->mPullerManager;
    EXPECT_EQ(pullerManager->mNextPullTimeNs, bucketStartTimeNs + bucketSizeNs);
    ASSERT_EQ(pullerManager->mReceivers.size(), 1);
    ASSERT_EQ(pullerManager->mReceivers.begin()->second.size(), 2);

    // Send events after the update. Counts reset to 0 since this is a new bucket.
    events.clear();
    events.push_back(
            CreateAppStartOccurredEvent(bucketStartTimeNs + 65 * NS_PER_SEC, app1Uid, "app1",
                                        AppStartOccurred::WARM, "", "", true,
                                        /*start_msec*/ 103));  // Not kept by gaugePushPersist.
    events.push_back(CreateAppStartOccurredEvent(bucketStartTimeNs + 70 * NS_PER_SEC, app2Uid,
                                                 "app2", AppStartOccurred::WARM, "", "", true,
                                                 /*start_msec*/ 203));  // Kept by gaugePushPersist.
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 75 * NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_OFF));
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 80 * NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_ON));
    events.push_back(CreateMoveToBackgroundEvent(bucketStartTimeNs + 85 * NS_PER_SEC, app1Uid));
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 90 * NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_OFF));
    events.push_back(CreateAppStartOccurredEvent(bucketStartTimeNs + 95 * NS_PER_SEC, app1Uid,
                                                 "app1", AppStartOccurred::WARM, "", "", true,
                                                 /*start_msec*/ 104));  // Kept by gaugePushPersist.
    events.push_back(CreateAppStartOccurredEvent(bucketStartTimeNs + 100 * NS_PER_SEC, app2Uid,
                                                 "app2", AppStartOccurred::WARM, "", "", true,
                                                 /*start_msec*/ 204));  // Kept by gaugePushPersist.
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 105 * NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_ON));
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 110 * NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_OFF));
    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->mPullerManager->ForceClearPullerCache();
        processor->OnLogEvent(event.get());
    }
    processor->mPullerManager->ForceClearPullerCache();
    // Pulling alarm arrive, triggering a bucket split. Only gaugeNew keeps the data since the
    // condition is false for gaugeNew.
    processor->informPullAlarmFired(bucketStartTimeNs + bucketSizeNs);

    uint64_t dumpTimeNs = bucketStartTimeNs + bucketSizeNs + 10 * NS_PER_SEC;
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 2);

    int64_t roundedBucketStartNs = MillisToNano(NanoToMillis(bucketStartTimeNs));
    int64_t roundedUpdateTimeNs = MillisToNano(NanoToMillis(updateTimeNs));
    int64_t roundedBucketEndNs = MillisToNano(NanoToMillis(bucketStartTimeNs + bucketSizeNs));
    int64_t roundedDumpTimeNs = MillisToNano(NanoToMillis(dumpTimeNs));

    // Report from before update.
    ConfigMetricsReport report = reports.reports(0);
    ASSERT_EQ(report.metrics_size(), 4);
    // Gauge subsystem sleep state trigger screen on. 2 pulls occurred.
    StatsLogReport gaugeRemoveBefore = report.metrics(0);
    EXPECT_EQ(gaugeRemoveBefore.metric_id(), gaugeRemove.id());
    EXPECT_TRUE(gaugeRemoveBefore.has_gauge_metrics());
    StatsLogReport::GaugeMetricDataWrapper gaugeMetrics;
    sortMetricDataByDimensionsValue(gaugeRemoveBefore.gauge_metrics(), &gaugeMetrics);
    ASSERT_EQ(gaugeMetrics.data_size(), 2);
    auto data = gaugeMetrics.data(0);
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_1");
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedBucketStartNs, roundedUpdateTimeNs,
                             {(int64_t)(bucketStartTimeNs + 20 * NS_PER_SEC),
                              (int64_t)(bucketStartTimeNs + 45 * NS_PER_SEC)});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 2);
    EXPECT_EQ(data.bucket_info(0).atom(0).subsystem_sleep_state().time_millis(), 101);
    EXPECT_EQ(data.bucket_info(0).atom(1).subsystem_sleep_state().time_millis(), 401);

    data = gaugeMetrics.data(1);
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_2");
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedBucketStartNs, roundedUpdateTimeNs,
                             {(int64_t)(bucketStartTimeNs + 20 * NS_PER_SEC),
                              (int64_t)(bucketStartTimeNs + 45 * NS_PER_SEC)});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 2);
    EXPECT_EQ(data.bucket_info(0).atom(0).subsystem_sleep_state().time_millis(), 102);
    EXPECT_EQ(data.bucket_info(0).atom(1).subsystem_sleep_state().time_millis(), 402);

    // Gauge subsystem sleep state when screen is on. One pull when the screen turned on
    StatsLogReport gaugePullPersistBefore = report.metrics(1);
    EXPECT_EQ(gaugePullPersistBefore.metric_id(), gaugePullPersist.id());
    EXPECT_TRUE(gaugePullPersistBefore.has_gauge_metrics());
    gaugeMetrics.Clear();
    sortMetricDataByDimensionsValue(gaugePullPersistBefore.gauge_metrics(), &gaugeMetrics);
    ASSERT_EQ(gaugeMetrics.data_size(), 2);
    data = gaugeMetrics.data(0);
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_1");
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedBucketStartNs, roundedUpdateTimeNs,
                             {(int64_t)(bucketStartTimeNs + 20 * NS_PER_SEC)});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 1);
    EXPECT_EQ(data.bucket_info(0).atom(0).subsystem_sleep_state().time_millis(), 101);

    data = gaugeMetrics.data(1);
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_2");
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedBucketStartNs, roundedUpdateTimeNs,
                             {(int64_t)(bucketStartTimeNs + 20 * NS_PER_SEC)});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 1);
    EXPECT_EQ(data.bucket_info(0).atom(0).subsystem_sleep_state().time_millis(), 102);

    // Gauge screen on events, one per bucket.
    StatsLogReport gaugeChangeBefore = report.metrics(2);
    EXPECT_EQ(gaugeChangeBefore.metric_id(), gaugeChange.id());
    EXPECT_TRUE(gaugeChangeBefore.has_gauge_metrics());
    gaugeMetrics.Clear();
    sortMetricDataByDimensionsValue(gaugeChangeBefore.gauge_metrics(), &gaugeMetrics);
    ASSERT_EQ(gaugeMetrics.data_size(), 1);
    data = gaugeMetrics.data(0);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedBucketStartNs, roundedUpdateTimeNs,
                             {(int64_t)(bucketStartTimeNs + 20 * NS_PER_SEC)});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 1);
    EXPECT_EQ(data.bucket_info(0).atom(0).screen_state_changed().state(),
              android::view::DISPLAY_STATE_ON);

    // Gauge app start while app is in the background. App 1 started twice, app 2 started once.
    StatsLogReport gaugePushPersistBefore = report.metrics(3);
    EXPECT_EQ(gaugePushPersistBefore.metric_id(), gaugePushPersist.id());
    EXPECT_TRUE(gaugePushPersistBefore.has_gauge_metrics());
    gaugeMetrics.Clear();
    sortMetricDataByDimensionsValue(gaugePushPersistBefore.gauge_metrics(), &gaugeMetrics);
    ASSERT_EQ(gaugeMetrics.data_size(), 2);
    data = gaugeMetrics.data(0);
    ValidateUidDimension(data.dimensions_in_what(), util::APP_START_OCCURRED, app1Uid);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedBucketStartNs, roundedUpdateTimeNs,
                             {(int64_t)(bucketStartTimeNs + 10 * NS_PER_SEC),
                              (int64_t)(bucketStartTimeNs + 35 * NS_PER_SEC)});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 2);
    EXPECT_EQ(data.bucket_info(0).atom(0).app_start_occurred().pkg_name(), "app1");
    EXPECT_EQ(data.bucket_info(0).atom(0).app_start_occurred().activity_start_millis(), 101);
    EXPECT_EQ(data.bucket_info(0).atom(1).app_start_occurred().pkg_name(), "app1");
    EXPECT_EQ(data.bucket_info(0).atom(1).app_start_occurred().activity_start_millis(), 102);

    data = gaugeMetrics.data(1);
    ValidateUidDimension(data.dimensions_in_what(), util::APP_START_OCCURRED, app2Uid);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedBucketStartNs, roundedUpdateTimeNs,
                             {(int64_t)(bucketStartTimeNs + 40 * NS_PER_SEC)});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 1);
    EXPECT_EQ(data.bucket_info(0).atom(0).app_start_occurred().pkg_name(), "app2");
    EXPECT_EQ(data.bucket_info(0).atom(0).app_start_occurred().activity_start_millis(), 202);

    // Report from after update.
    report = reports.reports(1);
    ASSERT_EQ(report.metrics_size(), 4);
    // Gauge screen on events FIRST_N_SAMPLES. There were 2.
    StatsLogReport gaugeChangeAfter = report.metrics(0);
    EXPECT_EQ(gaugeChangeAfter.metric_id(), gaugeChange.id());
    EXPECT_TRUE(gaugeChangeAfter.has_gauge_metrics());
    gaugeMetrics.Clear();
    sortMetricDataByDimensionsValue(gaugeChangeAfter.gauge_metrics(), &gaugeMetrics);
    ASSERT_EQ(gaugeMetrics.data_size(), 1);
    data = gaugeMetrics.data(0);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs,
                             {(int64_t)(bucketStartTimeNs + 80 * NS_PER_SEC),
                              (int64_t)(bucketStartTimeNs + 105 * NS_PER_SEC)});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 2);
    EXPECT_EQ(data.bucket_info(0).atom(0).screen_state_changed().state(),
              android::view::DISPLAY_STATE_ON);
    EXPECT_EQ(data.bucket_info(0).atom(1).screen_state_changed().state(),
              android::view::DISPLAY_STATE_ON);

    // Gauge subsystem sleep state, random one sample, no condition.
    // Pulled at update time and after the normal bucket end.
    StatsLogReport gaugeNewAfter = report.metrics(1);
    EXPECT_EQ(gaugeNewAfter.metric_id(), gaugeNew.id());
    EXPECT_TRUE(gaugeNewAfter.has_gauge_metrics());
    gaugeMetrics.Clear();
    sortMetricDataByDimensionsValue(gaugeNewAfter.gauge_metrics(), &gaugeMetrics);
    ASSERT_EQ(gaugeMetrics.data_size(), 2);
    data = gaugeMetrics.data(0);
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_1");
    ASSERT_EQ(data.bucket_info_size(), 2);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs,
                             {updateTimeNs});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 1);
    EXPECT_EQ(data.bucket_info(0).atom(0).subsystem_sleep_state().time_millis(), 901);
    ValidateGaugeBucketTimes(data.bucket_info(1), roundedBucketEndNs, roundedDumpTimeNs,
                             {(int64_t)(bucketStartTimeNs + bucketSizeNs)});
    ASSERT_EQ(data.bucket_info(1).atom_size(), 1);
    EXPECT_EQ(data.bucket_info(1).atom(0).subsystem_sleep_state().time_millis(), 1601);

    data = gaugeMetrics.data(1);
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_2");
    ASSERT_EQ(data.bucket_info_size(), 2);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs,
                             {updateTimeNs});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 1);
    EXPECT_EQ(data.bucket_info(0).atom(0).subsystem_sleep_state().time_millis(), 902);
    ValidateGaugeBucketTimes(data.bucket_info(1), roundedBucketEndNs, roundedDumpTimeNs,
                             {(int64_t)(bucketStartTimeNs + bucketSizeNs)});
    ASSERT_EQ(data.bucket_info(1).atom_size(), 1);
    EXPECT_EQ(data.bucket_info(1).atom(0).subsystem_sleep_state().time_millis(), 1602);

    // Gauge app start while app is in the background. App 1 started once, app 2 started twice.
    StatsLogReport gaugePushPersistAfter = report.metrics(2);
    EXPECT_EQ(gaugePushPersistAfter.metric_id(), gaugePushPersist.id());
    EXPECT_TRUE(gaugePushPersistAfter.has_gauge_metrics());
    gaugeMetrics.Clear();
    sortMetricDataByDimensionsValue(gaugePushPersistAfter.gauge_metrics(), &gaugeMetrics);
    ASSERT_EQ(gaugeMetrics.data_size(), 2);
    data = gaugeMetrics.data(0);
    ValidateUidDimension(data.dimensions_in_what(), util::APP_START_OCCURRED, app1Uid);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs,
                             {(int64_t)(bucketStartTimeNs + 95 * NS_PER_SEC)});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 1);
    EXPECT_EQ(data.bucket_info(0).atom(0).app_start_occurred().pkg_name(), "app1");
    EXPECT_EQ(data.bucket_info(0).atom(0).app_start_occurred().activity_start_millis(), 104);

    data = gaugeMetrics.data(1);
    ValidateUidDimension(data.dimensions_in_what(), util::APP_START_OCCURRED, app2Uid);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs,
                             {(int64_t)(bucketStartTimeNs + 70 * NS_PER_SEC),
                              (int64_t)(bucketStartTimeNs + 100 * NS_PER_SEC)});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 2);
    EXPECT_EQ(data.bucket_info(0).atom(0).app_start_occurred().pkg_name(), "app2");
    EXPECT_EQ(data.bucket_info(0).atom(0).app_start_occurred().activity_start_millis(), 203);
    EXPECT_EQ(data.bucket_info(0).atom(1).app_start_occurred().pkg_name(), "app2");
    EXPECT_EQ(data.bucket_info(0).atom(1).app_start_occurred().activity_start_millis(), 204);

    // Gauge subsystem sleep state when screen is on. One pull at update since screen is on then.
    StatsLogReport gaugePullPersistAfter = report.metrics(3);
    EXPECT_EQ(gaugePullPersistAfter.metric_id(), gaugePullPersist.id());
    EXPECT_TRUE(gaugePullPersistAfter.has_gauge_metrics());
    gaugeMetrics.Clear();
    sortMetricDataByDimensionsValue(gaugePullPersistAfter.gauge_metrics(), &gaugeMetrics);
    ASSERT_EQ(gaugeMetrics.data_size(), 2);
    data = gaugeMetrics.data(0);
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_1");
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs,
                             {updateTimeNs});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 1);
    EXPECT_EQ(data.bucket_info(0).atom(0).subsystem_sleep_state().time_millis(), 901);

    data = gaugeMetrics.data(1);
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_2");
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateGaugeBucketTimes(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs,
                             {updateTimeNs});
    ASSERT_EQ(data.bucket_info(0).atom_size(), 1);
    EXPECT_EQ(data.bucket_info(0).atom(0).subsystem_sleep_state().time_millis(), 902);
}

TEST_F(ConfigUpdateE2eTest, TestValueMetric) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");
    config.add_default_pull_packages("AID_ROOT");  // Fake puller is registered with root.

    AtomMatcher brightnessMatcher = CreateScreenBrightnessChangedAtomMatcher();
    *config.add_atom_matcher() = brightnessMatcher;
    AtomMatcher screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = screenOnMatcher;
    AtomMatcher screenOffMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = screenOffMatcher;
    AtomMatcher batteryPluggedUsbMatcher = CreateBatteryStateUsbMatcher();
    *config.add_atom_matcher() = batteryPluggedUsbMatcher;
    AtomMatcher unpluggedMatcher = CreateBatteryStateNoneMatcher();
    *config.add_atom_matcher() = unpluggedMatcher;
    AtomMatcher subsystemSleepMatcher =
            CreateSimpleAtomMatcher("SubsystemSleep", util::SUBSYSTEM_SLEEP_STATE);
    *config.add_atom_matcher() = subsystemSleepMatcher;

    Predicate screenOnPredicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = screenOnPredicate;
    Predicate unpluggedPredicate = CreateDeviceUnpluggedPredicate();
    *config.add_predicate() = unpluggedPredicate;

    State screenState = CreateScreenState();
    *config.add_state() = screenState;

    ValueMetric valuePullPersist =
            createValueMetric("SubsystemSleepWhileUnpluggedSliceScreen", subsystemSleepMatcher, 4,
                              unpluggedPredicate.id(), {screenState.id()});
    *valuePullPersist.mutable_dimensions_in_what() =
            CreateDimensions(util::SUBSYSTEM_SLEEP_STATE, {1 /* subsystem name */});

    ValueMetric valuePushPersist = createValueMetric(
            "MinScreenBrightnessWhileScreenOn", brightnessMatcher, 1, screenOnPredicate.id(), {});
    valuePushPersist.set_aggregation_type(ValueMetric::MIN);

    ValueMetric valueChange =
            createValueMetric("SubsystemSleep", subsystemSleepMatcher, 4, nullopt, {});
    *valueChange.mutable_dimensions_in_what() =
            CreateDimensions(util::SUBSYSTEM_SLEEP_STATE, {1 /* subsystem name */});

    ValueMetric valueRemove =
            createValueMetric("AvgScreenBrightness", brightnessMatcher, 1, nullopt, {});
    valueRemove.set_aggregation_type(ValueMetric::AVG);

    *config.add_value_metric() = valuePullPersist;
    *config.add_value_metric() = valueRemove;
    *config.add_value_metric() = valuePushPersist;
    *config.add_value_metric() = valueChange;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = getElapsedRealtimeNs();
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(TEN_MINUTES) * 1000000LL;
    // Config creation triggers pull #1.
    sp<StatsLogProcessor> processor = CreateStatsLogProcessor(
            bucketStartTimeNs, bucketStartTimeNs, config, key,
            SharedRefBase::make<FakeSubsystemSleepCallback>(), util::SUBSYSTEM_SLEEP_STATE);

    // Initialize log events before update.
    // ValuePushPersist and ValuePullPersist will skip the bucket due to condition unknown.
    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(CreateScreenBrightnessChangedEvent(bucketStartTimeNs + 5 * NS_PER_SEC, 5));
    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + 10 * NS_PER_SEC,
                                                   android::view::DISPLAY_STATE_ON));
    events.push_back(CreateScreenBrightnessChangedEvent(bucketStartTimeNs + 15 * NS_PER_SEC, 15));
    events.push_back(CreateBatteryStateChangedEvent(
            bucketStartTimeNs + 20 * NS_PER_SEC,
            BatteryPluggedStateEnum::BATTERY_PLUGGED_NONE));  // Pull #2.
    events.push_back(CreateScreenBrightnessChangedEvent(bucketStartTimeNs + 25 * NS_PER_SEC, 40));

    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->mPullerManager->ForceClearPullerCache();
        processor->OnLogEvent(event.get());
    }
    processor->mPullerManager->ForceClearPullerCache();

    // Do the update. Add matchers/conditions in different order to force indices to change.
    StatsdConfig newConfig;
    newConfig.add_allowed_log_source("AID_ROOT");
    newConfig.add_default_pull_packages("AID_ROOT");  // Fake puller is registered with root.

    *newConfig.add_atom_matcher() = screenOffMatcher;
    *newConfig.add_atom_matcher() = unpluggedMatcher;
    *newConfig.add_atom_matcher() = batteryPluggedUsbMatcher;
    *newConfig.add_atom_matcher() = subsystemSleepMatcher;
    *newConfig.add_atom_matcher() = brightnessMatcher;
    *newConfig.add_atom_matcher() = screenOnMatcher;

    *newConfig.add_predicate() = unpluggedPredicate;
    *newConfig.add_predicate() = screenOnPredicate;

    *config.add_state() = screenState;

    valueChange.set_condition(screenOnPredicate.id());
    *newConfig.add_value_metric() = valueChange;
    ValueMetric valueNew = createValueMetric("MaxScrBrightness", brightnessMatcher, 1, nullopt, {});
    valueNew.set_aggregation_type(ValueMetric::MAX);
    *newConfig.add_value_metric() = valueNew;
    *newConfig.add_value_metric() = valuePushPersist;
    *newConfig.add_value_metric() = valuePullPersist;

    int64_t updateTimeNs = bucketStartTimeNs + 30 * NS_PER_SEC;
    // Update pulls valuePullPersist and valueNew. Pull #3.
    processor->OnConfigUpdated(updateTimeNs, key, newConfig);

    // Verify puller manager is properly set.
    sp<StatsPullerManager> pullerManager = processor->mPullerManager;
    EXPECT_EQ(pullerManager->mNextPullTimeNs, bucketStartTimeNs + bucketSizeNs);
    ASSERT_EQ(pullerManager->mReceivers.size(), 1);
    ASSERT_EQ(pullerManager->mReceivers.begin()->second.size(), 2);

    // Send events after the update. Values reset since this is a new bucket.
    events.clear();
    events.push_back(CreateScreenBrightnessChangedEvent(bucketStartTimeNs + 35 * NS_PER_SEC, 30));
    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + 40 * NS_PER_SEC,
                                                   android::view::DISPLAY_STATE_OFF));  // Pull #4.
    events.push_back(CreateScreenBrightnessChangedEvent(bucketStartTimeNs + 45 * NS_PER_SEC, 20));
    events.push_back(CreateBatteryStateChangedEvent(
            bucketStartTimeNs + 50 * NS_PER_SEC,
            BatteryPluggedStateEnum::BATTERY_PLUGGED_USB));  // Pull #5.
    events.push_back(CreateScreenBrightnessChangedEvent(bucketStartTimeNs + 55 * NS_PER_SEC, 25));
    events.push_back(CreateScreenStateChangedEvent(bucketStartTimeNs + 60 * NS_PER_SEC,
                                                   android::view::DISPLAY_STATE_ON));  // Pull #6.
    events.push_back(CreateBatteryStateChangedEvent(
            bucketStartTimeNs + 65 * NS_PER_SEC,
            BatteryPluggedStateEnum::BATTERY_PLUGGED_NONE));  // Pull #7.
    events.push_back(CreateScreenBrightnessChangedEvent(bucketStartTimeNs + 70 * NS_PER_SEC, 40));

    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->mPullerManager->ForceClearPullerCache();
        processor->OnLogEvent(event.get());
    }
    processor->mPullerManager->ForceClearPullerCache();

    // Pulling alarm arrive, triggering a bucket split.
    // Both valuePullPersist and valueChange use the value since both conditions are true. Pull #8.
    processor->informPullAlarmFired(bucketStartTimeNs + bucketSizeNs);
    processor->OnLogEvent(CreateScreenBrightnessChangedEvent(
                                  bucketStartTimeNs + bucketSizeNs + 5 * NS_PER_SEC, 50)
                                  .get());

    uint64_t dumpTimeNs = bucketStartTimeNs + bucketSizeNs + 10 * NS_PER_SEC;
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 2);

    int64_t roundedBucketStartNs = MillisToNano(NanoToMillis(bucketStartTimeNs));
    int64_t roundedUpdateTimeNs = MillisToNano(NanoToMillis(updateTimeNs));
    int64_t roundedBucketEndNs = MillisToNano(NanoToMillis(bucketStartTimeNs + bucketSizeNs));
    int64_t roundedDumpTimeNs = MillisToNano(NanoToMillis(dumpTimeNs));

    // Report from before update.
    ConfigMetricsReport report = reports.reports(0);
    ASSERT_EQ(report.metrics_size(), 4);
    // Pull subsystem sleep while unplugged slice screen. Bucket skipped due to condition unknown.
    StatsLogReport valuePullPersistBefore = report.metrics(0);
    EXPECT_EQ(valuePullPersistBefore.metric_id(), valuePullPersist.id());
    EXPECT_TRUE(valuePullPersistBefore.has_value_metrics());
    ASSERT_EQ(valuePullPersistBefore.value_metrics().data_size(), 0);
    ASSERT_EQ(valuePullPersistBefore.value_metrics().skipped_size(), 1);
    StatsLogReport::SkippedBuckets skipBucket = valuePullPersistBefore.value_metrics().skipped(0);
    EXPECT_EQ(skipBucket.start_bucket_elapsed_nanos(), roundedBucketStartNs);
    EXPECT_EQ(skipBucket.end_bucket_elapsed_nanos(), roundedUpdateTimeNs);
    ASSERT_EQ(skipBucket.drop_event_size(), 1);
    EXPECT_EQ(skipBucket.drop_event(0).drop_reason(), BucketDropReason::CONDITION_UNKNOWN);

    // Average screen brightness. Values were 5, 15, 40. Avg: 20.
    StatsLogReport valueRemoveBefore = report.metrics(1);
    EXPECT_EQ(valueRemoveBefore.metric_id(), valueRemove.id());
    EXPECT_TRUE(valueRemoveBefore.has_value_metrics());
    StatsLogReport::ValueMetricDataWrapper valueMetrics;
    sortMetricDataByDimensionsValue(valueRemoveBefore.value_metrics(), &valueMetrics);
    ASSERT_EQ(valueMetrics.data_size(), 1);
    ValueMetricData data = valueMetrics.data(0);
    EXPECT_FALSE(data.has_dimensions_in_what());
    EXPECT_EQ(data.slice_by_state_size(), 0);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateValueBucket(data.bucket_info(0), roundedBucketStartNs, roundedUpdateTimeNs, 20, 0);

    // Min screen brightness while screen on. Bucket skipped due to condition unknown.
    StatsLogReport valuePushPersistBefore = report.metrics(2);
    EXPECT_EQ(valuePushPersistBefore.metric_id(), valuePushPersist.id());
    EXPECT_TRUE(valuePushPersistBefore.has_value_metrics());
    ASSERT_EQ(valuePushPersistBefore.value_metrics().data_size(), 0);
    ASSERT_EQ(valuePushPersistBefore.value_metrics().skipped_size(), 1);
    skipBucket = valuePushPersistBefore.value_metrics().skipped(0);
    EXPECT_EQ(skipBucket.start_bucket_elapsed_nanos(), roundedBucketStartNs);
    EXPECT_EQ(skipBucket.end_bucket_elapsed_nanos(), roundedUpdateTimeNs);
    ASSERT_EQ(skipBucket.drop_event_size(), 1);
    EXPECT_EQ(skipBucket.drop_event(0).drop_reason(), BucketDropReason::CONDITION_UNKNOWN);

    // Pull Subsystem sleep state. Value is Pull #3 (900) - Pull#1 (100).
    StatsLogReport valueChangeBefore = report.metrics(3);
    EXPECT_EQ(valueChangeBefore.metric_id(), valueChange.id());
    EXPECT_TRUE(valueChangeBefore.has_value_metrics());
    valueMetrics.Clear();
    sortMetricDataByDimensionsValue(valueChangeBefore.value_metrics(), &valueMetrics);
    ASSERT_EQ(valueMetrics.data_size(), 2);
    data = valueMetrics.data(0);
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_1");
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateValueBucket(data.bucket_info(0), roundedBucketStartNs, roundedUpdateTimeNs, 800, 0);
    data = valueMetrics.data(1);
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_2");
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateValueBucket(data.bucket_info(0), roundedBucketStartNs, roundedUpdateTimeNs, 800, 0);

    // Report from after update.
    report = reports.reports(1);
    ASSERT_EQ(report.metrics_size(), 4);
    // Pull subsystem sleep while screen on.
    // Pull#4 (1600) - pull#3 (900) + pull#8 (6400) - pull#6 (3600)
    StatsLogReport valueChangeAfter = report.metrics(0);
    EXPECT_EQ(valueChangeAfter.metric_id(), valueChange.id());
    EXPECT_TRUE(valueChangeAfter.has_value_metrics());
    valueMetrics.Clear();
    sortMetricDataByDimensionsValue(valueChangeAfter.value_metrics(), &valueMetrics);
    int64_t conditionTrueNs = bucketSizeNs - 60 * NS_PER_SEC + 10 * NS_PER_SEC;
    ASSERT_EQ(valueMetrics.data_size(), 2);
    data = valueMetrics.data(0);
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_1");
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateValueBucket(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs, 3500,
                        conditionTrueNs);
    ASSERT_EQ(valueMetrics.data_size(), 2);
    data = valueMetrics.data(1);
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_2");
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateValueBucket(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs, 3500,
                        conditionTrueNs);

    ASSERT_EQ(valueChangeAfter.value_metrics().skipped_size(), 1);
    skipBucket = valueChangeAfter.value_metrics().skipped(0);
    EXPECT_EQ(skipBucket.start_bucket_elapsed_nanos(), roundedBucketEndNs);
    EXPECT_EQ(skipBucket.end_bucket_elapsed_nanos(), roundedDumpTimeNs);
    ASSERT_EQ(skipBucket.drop_event_size(), 1);
    EXPECT_EQ(skipBucket.drop_event(0).drop_reason(), BucketDropReason::DUMP_REPORT_REQUESTED);

    // Max screen brightness, no condition. Val is 40 in first bucket, 50 in second.
    StatsLogReport valueNewAfter = report.metrics(1);
    EXPECT_EQ(valueNewAfter.metric_id(), valueNew.id());
    EXPECT_TRUE(valueNewAfter.has_value_metrics());
    valueMetrics.Clear();
    sortMetricDataByDimensionsValue(valueNewAfter.value_metrics(), &valueMetrics);
    ASSERT_EQ(valueMetrics.data_size(), 1);
    data = valueMetrics.data(0);
    ASSERT_EQ(data.bucket_info_size(), 2);
    ValidateValueBucket(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs, 40, 0);
    ValidateValueBucket(data.bucket_info(1), roundedBucketEndNs, roundedDumpTimeNs, 50, 0);

    // Min screen brightness when screen on. Val is 30 in first bucket, 50 in second.
    StatsLogReport valuePushPersistAfter = report.metrics(2);
    EXPECT_EQ(valuePushPersistAfter.metric_id(), valuePushPersist.id());
    EXPECT_TRUE(valuePushPersistAfter.has_value_metrics());
    valueMetrics.Clear();
    sortMetricDataByDimensionsValue(valuePushPersistAfter.value_metrics(), &valueMetrics);
    ASSERT_EQ(valueMetrics.data_size(), 1);
    data = valueMetrics.data(0);
    ASSERT_EQ(data.bucket_info_size(), 2);
    conditionTrueNs = bucketSizeNs - 60 * NS_PER_SEC + 10 * NS_PER_SEC;
    ValidateValueBucket(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs, 30,
                        conditionTrueNs);
    ValidateValueBucket(data.bucket_info(1), roundedBucketEndNs, roundedDumpTimeNs, 50,
                        10 * NS_PER_SEC);

    // Subsystem sleep state while unplugged slice screen.
    StatsLogReport valuePullPersistAfter = report.metrics(3);
    EXPECT_EQ(valuePullPersistAfter.metric_id(), valuePullPersist.id());
    EXPECT_TRUE(valuePullPersistAfter.has_value_metrics());
    valueMetrics.Clear();
    sortMetricDataByDimensionsValue(valuePullPersistAfter.value_metrics(), &valueMetrics);
    ASSERT_EQ(valueMetrics.data_size(), 4);
    // Name 1, screen OFF. Pull#5 (2500) - pull#4 (1600).
    data = valueMetrics.data(0);
    conditionTrueNs = 10 * NS_PER_SEC;
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_1");
    ValidateStateValue(data.slice_by_state(), util::SCREEN_STATE_CHANGED,
                       android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateValueBucket(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs, 900,
                        conditionTrueNs);
    // Name 1, screen ON. Pull#4 (1600) - pull#3 (900) + pull#8 (6400) - pull#7 (4900).
    data = valueMetrics.data(1);
    conditionTrueNs = 10 * NS_PER_SEC + bucketSizeNs - 65 * NS_PER_SEC;
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_1");
    ValidateStateValue(data.slice_by_state(), util::SCREEN_STATE_CHANGED,
                       android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateValueBucket(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs, 2200,
                        conditionTrueNs);
    // Name 2, screen OFF. Pull#5 (2500) - pull#4 (1600).
    data = valueMetrics.data(2);
    conditionTrueNs = 10 * NS_PER_SEC;
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_2");
    ValidateStateValue(data.slice_by_state(), util::SCREEN_STATE_CHANGED,
                       android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateValueBucket(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs, 900,
                        conditionTrueNs);
    // Name 2, screen ON. Pull#4 (1600) - pull#3 (900) + pull#8 (6400) - pull#7 (4900).
    data = valueMetrics.data(3);
    conditionTrueNs = 10 * NS_PER_SEC + bucketSizeNs - 65 * NS_PER_SEC;
    ValidateSubsystemSleepDimension(data.dimensions_in_what(), "subsystem_name_2");
    ValidateStateValue(data.slice_by_state(), util::SCREEN_STATE_CHANGED,
                       android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateValueBucket(data.bucket_info(0), roundedUpdateTimeNs, roundedBucketEndNs, 2200,
                        conditionTrueNs);

    ASSERT_EQ(valuePullPersistAfter.value_metrics().skipped_size(), 1);
    skipBucket = valuePullPersistAfter.value_metrics().skipped(0);
    EXPECT_EQ(skipBucket.start_bucket_elapsed_nanos(), roundedBucketEndNs);
    EXPECT_EQ(skipBucket.end_bucket_elapsed_nanos(), roundedDumpTimeNs);
    ASSERT_EQ(skipBucket.drop_event_size(), 1);
    EXPECT_EQ(skipBucket.drop_event(0).drop_reason(), BucketDropReason::DUMP_REPORT_REQUESTED);
}

TEST_F(ConfigUpdateE2eTest, TestNewDurationExistingWhat) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();

    Predicate holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    *config.add_predicate() = holdingWakelockPredicate;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(FIVE_MINUTES) * 1000000LL;
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    int app1Uid = 123;
    vector<int> attributionUids1 = {app1Uid};
    vector<string> attributionTags1 = {"App1"};
    // Create a wakelock acquire, causing the condition to be true.
    unique_ptr<LogEvent> event = CreateAcquireWakelockEvent(bucketStartTimeNs + 10 * NS_PER_SEC,
                                                            attributionUids1, attributionTags1,
                                                            "wl1");  // 0:10
    processor->OnLogEvent(event.get());

    // Add metric.
    DurationMetric* durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->set_aggregation_type(DurationMetric::SUM);
    durationMetric->set_bucket(FIVE_MINUTES);

    uint64_t updateTimeNs = bucketStartTimeNs + 60 * NS_PER_SEC;  // 1:00
    processor->OnConfigUpdated(updateTimeNs, key, config);

    event = CreateReleaseWakelockEvent(bucketStartTimeNs + 80 * NS_PER_SEC, attributionUids1,
                                       attributionTags1,
                                       "wl1");  // 1:20
    processor->OnLogEvent(event.get());
    uint64_t dumpTimeNs = bucketStartTimeNs + 90 * NS_PER_SEC;  // 1:30
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_TRUE(reports.reports(0).metrics(0).has_duration_metrics());

    StatsLogReport::DurationMetricDataWrapper metricData;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).duration_metrics(), &metricData);
    ASSERT_EQ(metricData.data_size(), 1);
    DurationMetricData data = metricData.data(0);
    ASSERT_EQ(data.bucket_info_size(), 1);

    DurationBucketInfo bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.start_bucket_elapsed_nanos(), updateTimeNs);
    EXPECT_EQ(bucketInfo.end_bucket_elapsed_nanos(), dumpTimeNs);
    EXPECT_EQ(bucketInfo.duration_nanos(), 20 * NS_PER_SEC);
}

TEST_F(ConfigUpdateE2eTest, TestNewDurationExistingWhatSlicedCondition) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToBackgroundAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToForegroundAtomMatcher();

    Predicate holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by first attribution node by uid.
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *config.add_predicate() = holdingWakelockPredicate;

    Predicate isInBackgroundPredicate = CreateIsInBackgroundPredicate();
    *isInBackgroundPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /*uid*/});
    *config.add_predicate() = isInBackgroundPredicate;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(FIVE_MINUTES) * 1000000LL;
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    int app1Uid = 123, app2Uid = 456;
    vector<int> attributionUids1 = {app1Uid};
    vector<string> attributionTags1 = {"App1"};
    vector<int> attributionUids2 = {app2Uid};
    vector<string> attributionTags2 = {"App2"};
    unique_ptr<LogEvent> event = CreateAcquireWakelockEvent(bucketStartTimeNs + 10 * NS_PER_SEC,
                                                            attributionUids1, attributionTags1,
                                                            "wl1");  // 0:10
    processor->OnLogEvent(event.get());
    event = CreateMoveToBackgroundEvent(bucketStartTimeNs + 22 * NS_PER_SEC, app1Uid);  // 0:22
    processor->OnLogEvent(event.get());
    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 35 * NS_PER_SEC, attributionUids2,
                                       attributionTags2,
                                       "wl1");  // 0:35
    processor->OnLogEvent(event.get());

    // Add metric.
    DurationMetric* durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->set_condition(isInBackgroundPredicate.id());
    durationMetric->set_aggregation_type(DurationMetric::SUM);
    // The metric is dimensioning by first attribution node and only by uid.
    *durationMetric->mutable_dimensions_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    durationMetric->set_bucket(FIVE_MINUTES);
    // Links between wakelock state atom and condition of app is in background.
    auto links = durationMetric->add_links();
    links->set_condition(isInBackgroundPredicate.id());
    *links->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *links->mutable_fields_in_condition() =
            CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /*uid*/});

    uint64_t updateTimeNs = bucketStartTimeNs + 60 * NS_PER_SEC;  // 1:00
    processor->OnConfigUpdated(updateTimeNs, key, config);

    event = CreateMoveToBackgroundEvent(bucketStartTimeNs + 73 * NS_PER_SEC, app2Uid);  // 1:13
    processor->OnLogEvent(event.get());
    event = CreateReleaseWakelockEvent(bucketStartTimeNs + 84 * NS_PER_SEC, attributionUids1,
                                       attributionTags1, "wl1");  // 1:24
    processor->OnLogEvent(event.get());

    uint64_t dumpTimeNs = bucketStartTimeNs + 90 * NS_PER_SEC;  //  1:30
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_TRUE(reports.reports(0).metrics(0).has_duration_metrics());

    StatsLogReport::DurationMetricDataWrapper metricData;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).duration_metrics(), &metricData);
    ASSERT_EQ(metricData.data_size(), 2);

    DurationMetricData data = metricData.data(0);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app1Uid);
    ASSERT_EQ(data.bucket_info_size(), 1);
    DurationBucketInfo bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 24 * NS_PER_SEC);

    data = metricData.data(1);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app2Uid);
    ASSERT_EQ(data.bucket_info_size(), 1);
    bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 17 * NS_PER_SEC);
}

TEST_F(ConfigUpdateE2eTest, TestNewDurationExistingWhatSlicedState) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();

    Predicate holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by first attribution node by uid.
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *config.add_predicate() = holdingWakelockPredicate;

    auto uidProcessState = CreateUidProcessState();
    *config.add_state() = uidProcessState;

    // Count metric. We don't care about this one. Only use it so the StateTracker gets persisted.
    CountMetric* countMetric = config.add_count_metric();
    countMetric->set_id(StringToId("Tmp"));
    countMetric->set_what(config.atom_matcher(0).id());
    countMetric->add_slice_by_state(uidProcessState.id());
    // The metric is dimensioning by first attribution node and only by uid.
    *countMetric->mutable_dimensions_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    countMetric->set_bucket(FIVE_MINUTES);
    auto stateLink = countMetric->add_state_link();
    stateLink->set_state_atom_id(util::UID_PROCESS_STATE_CHANGED);
    *stateLink->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *stateLink->mutable_fields_in_state() =
            CreateDimensions(util::UID_PROCESS_STATE_CHANGED, {1 /*uid*/});
    config.add_no_report_metric(countMetric->id());

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(FIVE_MINUTES) * 1000000LL;
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    int app1Uid = 123, app2Uid = 456;
    vector<int> attributionUids1 = {app1Uid};
    vector<string> attributionTags1 = {"App1"};
    vector<int> attributionUids2 = {app2Uid};
    vector<string> attributionTags2 = {"App2"};
    unique_ptr<LogEvent> event = CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 10 * NS_PER_SEC, app1Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);  // 0:10
    processor->OnLogEvent(event.get());
    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 22 * NS_PER_SEC, attributionUids1,
                                       attributionTags1,
                                       "wl1");  // 0:22
    processor->OnLogEvent(event.get());
    event = CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 30 * NS_PER_SEC, app2Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);  // 0:30
    processor->OnLogEvent(event.get());

    // Add metric.
    DurationMetric* durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->add_slice_by_state(uidProcessState.id());
    durationMetric->set_aggregation_type(DurationMetric::SUM);
    // The metric is dimensioning by first attribution node and only by uid.
    *durationMetric->mutable_dimensions_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    durationMetric->set_bucket(FIVE_MINUTES);
    // Links between wakelock state atom and condition of app is in background.
    stateLink = durationMetric->add_state_link();
    stateLink->set_state_atom_id(util::UID_PROCESS_STATE_CHANGED);
    *stateLink->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *stateLink->mutable_fields_in_state() =
            CreateDimensions(util::UID_PROCESS_STATE_CHANGED, {1 /*uid*/});

    uint64_t updateTimeNs = bucketStartTimeNs + 60 * NS_PER_SEC;  // 1:00
    processor->OnConfigUpdated(updateTimeNs, key, config);

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 72 * NS_PER_SEC, attributionUids2,
                                       attributionTags2,
                                       "wl1");  // 1:13
    processor->OnLogEvent(event.get());
    event = CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 75 * NS_PER_SEC, app1Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND);  // 1:15
    processor->OnLogEvent(event.get());
    event = CreateReleaseWakelockEvent(bucketStartTimeNs + 84 * NS_PER_SEC, attributionUids1,
                                       attributionTags1, "wl1");  // 1:24
    processor->OnLogEvent(event.get());

    uint64_t dumpTimeNs = bucketStartTimeNs + 90 * NS_PER_SEC;  //  1:30
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_TRUE(reports.reports(0).metrics(0).has_duration_metrics());

    StatsLogReport::DurationMetricDataWrapper metricData;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).duration_metrics(), &metricData);
    ASSERT_EQ(metricData.data_size(), 3);

    DurationMetricData data = metricData.data(0);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app1Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    DurationBucketInfo bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 15 * NS_PER_SEC);

    data = metricData.data(1);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app1Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 9 * NS_PER_SEC);

    data = metricData.data(2);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app2Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 18 * NS_PER_SEC);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
