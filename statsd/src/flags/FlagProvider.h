/*
 * Copyright (C) 2021 The Android Open Source Project
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

#pragma once

#include <android-modules-utils/sdk_level.h>
#include <gtest/gtest_prod.h>
#include <server_configurable_flags/get_flags.h>

#include <string>

namespace android {
namespace os {
namespace statsd {

using GetServerFlagFunc =
        std::function<std::string(const std::string&, const std::string&, const std::string&)>;
using IsAtLeastSFunc = std::function<bool()>;

const std::string STATSD_NATIVE_NAMESPACE = "statsd_native";

const std::string PARTIAL_CONFIG_UPDATE_FLAG = "partial_config_update";
const std::string KLL_METRIC_FLAG = "kll_metric";

const std::string FLAG_TRUE = "true";
const std::string FLAG_FALSE = "false";
const std::string FLAG_EMPTY = "";

class FlagProvider {
public:
    static FlagProvider& getInstance();

    std::string getFlagString(const std::string& flagName, const std::string& defaultValue) const;

    // Returns true IFF flagName has a value of "true".
    bool getFlagBool(const std::string& flagName, const std::string& defaultValue) const;

private:
    FlagProvider();

    void overrideFuncs(
            const IsAtLeastSFunc& isAtLeastSFunc = &android::modules::sdklevel::IsAtLeastS,
            const GetServerFlagFunc& getServerFlagFunc =
                    &server_configurable_flags::GetServerConfigurableFlag);

    inline void resetFuncs() {
        overrideFuncs();
    }

    IsAtLeastSFunc mIsAtLeastSFunc;
    GetServerFlagFunc mGetServerFlagFunc;

    friend class ConfigUpdateE2eTest;
    friend class ConfigUpdateTest;
    friend class FlagProviderTest_RMinus;
    friend class FlagProviderTest_SPlus;
    friend class KllMetricE2eAbTest;
    friend class MetricsManagerTest;

    FRIEND_TEST(ConfigUpdateE2eTest, TestKllMetric_KllDisabledBeforeConfigUpdate);
    FRIEND_TEST(FlagProviderTest_SPlus, TestGetFlagBoolServerFlagTrue);
    FRIEND_TEST(FlagProviderTest_SPlus, TestGetFlagBoolServerFlagFalse);
    FRIEND_TEST(FlagProviderTest_SPlus, TestGetFlagBoolServerFlagEmptyDefaultFalse);
    FRIEND_TEST(FlagProviderTest_SPlus, TestGetFlagBoolServerFlagEmptyDefaultTrue);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
